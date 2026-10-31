package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a project is for, and what follows from it.
 *
 * **The field exists so that a keystore is never offered to a shell script.** Most of these tests are
 * about the two answers that are easy to get wrong: a project with no recorded type, and a type written
 * by a newer version of the app.
 */
class AppTypeTest {

    private fun temp(): File = File(System.getProperty("java.io.tmpdir"), "apptype-" + System.nanoTime())
        .apply { mkdirs() }

    @Test
    fun `a project with no metadata is an Android app, because every project before this was one`() {
        // **The default is the migration.** Defaulting to NONE would quietly take the release path
        // away from every project that already exists.
        val dir = temp()
        assertEquals(AppType.ANDROID, ProjectMeta.read(dir).type)
        dir.deleteRecursively()
    }

    @Test
    fun `a type is written and read back`() {
        val dir = temp()
        ProjectMeta.write(dir, ProjectMeta(AppType.WEB, template = WorkflowTemplates.WEB))

        val read = ProjectMeta.read(dir)
        assertEquals(AppType.WEB, read.type)
        assertEquals(WorkflowTemplates.WEB, read.template)
        dir.deleteRecursively()
    }

    @Test
    fun `a type this build has never heard of reads as the default rather than as nothing`() {
        // A metadata file written by a newer app may name a type this one does not know. Answering
        // with the default keeps the project usable; answering null would make it unopenable.
        val dir = temp()
        File(dir, Workspace.STATE_DIR).mkdirs()
        File(File(dir, Workspace.STATE_DIR), ProjectMeta.FILE)
            .writeText("""{"type":"kubernetes-operator"}""")

        assertEquals(AppType.ANDROID, ProjectMeta.read(dir).type)
        assertFalse("but the app can say it does not know it", AppType.known("kubernetes-operator"))
        dir.deleteRecursively()
    }

    @Test
    fun `a damaged metadata file does not make the folder unopenable`() {
        // The folder is the person's work; the metadata is the app's note about it. A note that will
        // not parse must not take the work with it.
        val dir = temp()
        File(dir, Workspace.STATE_DIR).mkdirs()
        File(File(dir, Workspace.STATE_DIR), ProjectMeta.FILE).writeText("{ not json at all")

        assertEquals(AppType.ANDROID, ProjectMeta.read(dir).type)
        dir.deleteRecursively()
    }

    @Test
    fun `only an Android app is signed`() {
        // **The whole reason the field exists.** A web app and a script have nothing to sign.
        assertTrue(AppType.ANDROID.signed)
        assertFalse(AppType.WEB.signed)
        assertFalse(AppType.PLAIN.signed)
        assertFalse(AppType.NONE.signed)
    }

    @Test
    fun `each type that can build has a template, and just files has none`() {
        // A folder of notes has no build, so a workflow for it would be a file that fails on every
        // push. That is worse than no file.
        assertNull(WorkflowTemplates.forType(AppType.NONE))
        for (type in listOf(AppType.ANDROID, AppType.WEB, AppType.PLAIN)) {
            val name = WorkflowTemplates.forType(type)
            assertTrue("$type has a template", name != null && name in WorkflowTemplates.ALL)
        }
    }

    @Test
    fun `the Android template reads the key from the environment, never from a repository secret`() {
        // **The rule the whole secret design rests on.** A repository secret is readable by every
        // workflow in the repository, including one the agent writes.
        val text = WorkflowTemplates.ALL.getValue(WorkflowTemplates.ANDROID)
        assertTrue("it names the environment", text.contains("environment: release"))
        assertTrue("and reads the four", text.contains("secrets.MUFFY_KEYSTORE_BASE64"))
        assertTrue("with a version name from the dispatch", text.contains("inputs.version_name"))
    }

    @Test
    fun `the templates that have nothing to sign do not mention a signing key`() {
        // A web app is trusted because of its domain and a script because of where it came from.
        for (name in listOf(WorkflowTemplates.WEB, WorkflowTemplates.PLAIN)) {
            val text = WorkflowTemplates.ALL.getValue(name)
            assertFalse("$name does not sign", text.contains("MUFFY_KEYSTORE"))
            assertFalse("$name names no environment for a key", text.contains("environment: release"))
        }
    }

    @Test
    fun `only main publishes, in every template that publishes`() {
        // A development push is something to install and look at; it is not a release. This is the
        // same rule the app's own repository follows.
        val android = WorkflowTemplates.ALL.getValue(WorkflowTemplates.ANDROID)
        assertTrue(android.contains("github.ref == 'refs/heads/main'"))
        val web = WorkflowTemplates.ALL.getValue(WorkflowTemplates.WEB)
        assertTrue(web.contains("github.ref == 'refs/heads/main'"))
    }

    // ---- the lock that must not be a name --------------------------------

    @Test
    fun `the templates folder is locked, and a project's own workflows folder is not`() {
        // **The bug this test exists for.** Every other system lock matches a folder *name*, and this
        // one must not: the folder is called `workflows`, and every project with CI has a
        // `.github/workflows/` the agent has to be able to read. A name rule would lock those too.
        assertTrue(
            "a preset is the app's",
            Workspace.isTemplatePath("system/project_types/android/workflows/android.yml"),
        )
        assertTrue(
            "and the preset's own prompt is too",
            Workspace.isTemplatePath("system/project_types/android/project_prompt.txt"),
        )
        assertFalse(
            "a project's own workflows are the project's",
            Workspace.isTemplatePath("my-app/.github/workflows/android.yml"),
        )
        assertFalse(
            "and so is the copy a project got when it was made",
            Workspace.isTemplatePath("my-app/.github/workflows/android.yml"),
        )
        assertFalse(
            "a project that is merely called 'workflows' is not the preset folder",
            Workspace.isTemplatePath("workflows/something.yml"),
        )
        assertFalse(
            "and neither is one called 'system' with no presets in it",
            Workspace.isTemplatePath("system/README.md"),
        )
    }

    // ---- copying a preset into a project ---------------------------------

    /** An account with the presets written, as `Workspace.ensure()` does on every refresh. */
    private fun account(): Workspace {
        val root = temp()
        val ws = Workspace(root)
        ws.ensure()
        return ws
    }

    @Test
    fun `a new project gets the workflow and the prompt for its type`() {
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }

        val copied = Presets.copy(ws.accountDir, project, AppType.ANDROID)

        assertTrue("both arrived: ${copied.written}", copied.written.size == 2)
        assertTrue(
            "the workflow is where GitHub looks for it",
            File(project, ".github/workflows/android.yml").isFile,
        )
        assertTrue("and so is the prompt", File(project, AgentPrompt.PROJECT_FILE).isFile)
        assertEquals("the type is recorded", AppType.ANDROID, ProjectMeta.read(project).type)
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `a project that already has its own files keeps them, word for word`() {
        // **This is what makes a preset a preset.** A person who adapted the workflow to their project
        // would lose it if the copy overwrote what was there.
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }
        val mine = File(project, ".github/workflows/android.yml")
        mine.parentFile?.mkdirs()
        mine.writeText("# mine, not the preset's")
        File(project, AgentPrompt.PROJECT_FILE).writeText("# my instructions")

        val copied = Presets.copy(ws.accountDir, project, AppType.ANDROID)

        assertEquals("nothing was written", emptyList<String>(), copied.written)
        assertEquals("both were kept", 2, copied.kept.size)
        assertEquals("and the file is unchanged", "# mine, not the preset's", mine.readText())
        assertEquals(
            "and so is the prompt",
            "# my instructions",
            File(project, AgentPrompt.PROJECT_FILE).readText(),
        )
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `changing type adds the new pair and leaves the old one`() {
        // A project that changes type is a project somebody has already worked in. Tidying up after
        // the change would delete work, and the app is not entitled to it.
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }
        Presets.copy(ws.accountDir, project, AppType.ANDROID)

        val second = Presets.copy(ws.accountDir, project, AppType.WEB)

        assertTrue(
            "the web workflow arrived",
            File(project, ".github/workflows/web.yml").isFile,
        )
        assertTrue(
            "and the android one is still there, untouched",
            File(project, ".github/workflows/android.yml").isFile,
        )
        assertEquals("the record says what it is now", AppType.WEB, ProjectMeta.read(project).type)
        assertTrue("the prompt was kept, not replaced", second.kept.contains(AgentPrompt.PROJECT_FILE))
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `just files gets no workflow, and says so rather than failing`() {
        val ws = account()
        val project = File(ws.accountDir, "notes").apply { mkdirs() }

        val copied = Presets.copy(ws.accountDir, project, AppType.NONE)

        assertFalse(
            "no workflow was invented for a folder of notes",
            File(project, ".github").exists(),
        )
        assertEquals("and the type is still recorded", AppType.NONE, ProjectMeta.read(project).type)
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `a preset a person deleted is not an error`() {
        // A project that keeps working without a preset is the right outcome; an app that refused
        // because its own template was missing would be an app that cannot be repaired by hand.
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }
        File(File(ws.accountDir, "system"), Workspace.TYPES).deleteRecursively()

        val copied = Presets.copy(ws.accountDir, project, AppType.ANDROID)

        assertEquals("nothing to copy", emptyList<String>(), copied.written)
        assertEquals("but the type is recorded", AppType.ANDROID, ProjectMeta.read(project).type)
        ws.accountDir.deleteRecursively()
    }

    // ---- the prompt a project gets ---------------------------------------

    @Test
    fun `the project prompt a project gets is the type's preset, copied`() {
        // **There is no second reading of the preset.** The design has three things in the agent's
        // context: the system prompt, the project prompt, and the log. The type's prompt is not a
        // layer — it is the starting text of the project's own file, copied in and then the
        // project's. A fourth layer would mean the preset is read twice and the project's edit
        // could never win outright.
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }

        Presets.copy(ws.accountDir, project, AppType.ANDROID)

        val copied = File(project, AgentPrompt.PROJECT_FILE).readText()
        val preset = File(
            File(File(File(ws.accountDir, "system"), Workspace.TYPES), "android"),
            AgentPrompt.PRESET_FILE,
        ).readText()
        assertEquals("the copy is the preset, word for word", preset.trim(), copied.trim())
        assertTrue(
            "and it says how to build an Android app",
            copied.contains("Gradle"),
        )
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `the message is the system prompt and then the project prompt, and nothing else`() {
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }
        Presets.copy(ws.accountDir, project, AppType.ANDROID)

        val layers = AgentPrompt.read(ws.accountDir, project)
        val message = layers.message

        // **Two separators means three parts: ①, the "# This project" heading, ②.** A third layer
        // would be a third separator, and this is the test that would catch it coming back.
        assertEquals(
            "exactly two layers",
            1,
            message.split(AgentPrompt.SEPARATOR).size - 1,
        )
        assertTrue("the system prompt is there", message.startsWith(layers.system.trim()))
        assertTrue("and the project's own", message.contains(layers.project.trim()))
        ws.accountDir.deleteRecursively()
    }

    @Test
    fun `a locked project prompt is refused, and the lock is the whole rule`() {
        // The agent may edit its brief; when the person locks it, it may not. There is no third
        // state and no special case for this path.
        val ws = account()
        val project = File(ws.accountDir, "my-app").apply { mkdirs() }
        Presets.copy(ws.accountDir, project, AppType.ANDROID)
        val brief = File(project, AgentPrompt.PROJECT_FILE)

        assertNull(
            "unlocked, the agent may edit it",
            ws.agentLockProblem(brief),
        )

        ws.setLocked(setOf(project.name + "/" + AgentPrompt.PROJECT_FILE))
        assertNotNull(
            "locked, it may not",
            ws.agentLockProblem(brief),
        )
        ws.accountDir.deleteRecursively()
    }
}
