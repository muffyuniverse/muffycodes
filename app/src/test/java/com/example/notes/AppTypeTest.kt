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
            "the template folder is the app's",
            Workspace.isTemplatePath("system/workflows/android.yml"),
        )
        assertFalse(
            "a project's own workflows are the project's",
            Workspace.isTemplatePath("my-app/.github/workflows/android.yml"),
        )
        assertFalse(
            "and a project that is merely called 'workflows' is not the template folder",
            Workspace.isTemplatePath("workflows/something.yml"),
        )
    }
}
