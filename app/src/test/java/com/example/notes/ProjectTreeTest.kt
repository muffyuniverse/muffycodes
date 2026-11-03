package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **A project's prompt is a folder of documents, not one file.**
 *
 * The operator: *"project prompt just takes and inserts in the context all the files from project_prompt
 * subfolder. This way it can be flexible and user just can ask agent to copy or write there whatever he
 * wants and then lock those files so that they cant be changed without his agreement"*.
 *
 * What is tested here is the reading, which is the part that can be wrong. The writing is the agent's
 * ordinary file tools and needs no rule of its own.
 */
class ProjectTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun project(): File = tmp.newFolder("a-project")

    @Test
    fun `a new project gets a prompts folder`() {
        val dir = project()
        ProjectTree.create(dir)
        assertTrue("the folder is there", ProjectTree.promptsDir(dir).isDirectory)
    }

    @Test
    fun `the other subject folders are made too, with their markers`() {
        // `prompts/` is where the brief is assembled from; the subject folders are what a person
        // browses. Both belong to "the folders a project needs", and only the subjects get markers —
        // a marker in `prompts/` would become part of the prompt's own text.
        val dir = project()
        val wrote = ProjectTree.create(dir)
        for (name in listOf("dictionary", "skills", "wiki", "mcp")) {
            assertTrue("$name was made", File(File(dir, AgentPrompt.DATA), name).isDirectory)
        }
        assertTrue("the markers are reported: $wrote", wrote.isNotEmpty())
        assertEquals(
            "and prompts/ holds only the marker",
            listOf(Workspace.KEEPER),
            ProjectTree.promptsDir(dir).listFiles()?.map { it.name }.orEmpty(),
        )
    }

    @Test
    fun `the marker in the folder is not part of the prompt`() {
        // **The one rule that lets the folder be both trackable and content.** An empty folder is
        // invisible to git, so `prompts/` holds a hidden marker — and every file in there is read as
        // part of the brief, so without this the prompt would open with an empty section called
        // `.gitkeep`. A person would see it in their own instructions.
        val dir = project()
        ProjectTree.create(dir)
        assertTrue("the marker is there", File(ProjectTree.promptsDir(dir), Workspace.KEEPER).isFile)
        assertEquals("and it is not read", "", ProjectTree.prompt(dir))
    }

    @Test
    fun `the folder starts empty, because a starter would be a type by another name`() {
        // The point of removing project types was to stop prescribing what a project contains. A
        // starter document written into the folder on creation would be exactly that prescription,
        // moved one layer down.
        val dir = project()
        ProjectTree.create(dir)
        assertEquals("no documents, and no defaults", "", ProjectTree.prompt(dir))
    }

    @Test
    fun `every document in the folder is in the prompt`() {
        val dir = project()
        ProjectTree.create(dir)
        File(ProjectTree.promptsDir(dir), "how-we-work.md").writeText("Read the tests first.")
        File(ProjectTree.promptsDir(dir), "this-codebase.md").writeText("Kotlin, no frameworks.")
        val prompt = ProjectTree.prompt(dir)
        assertTrue("the first document is there", prompt.contains("Read the tests first."))
        assertTrue("and the second", prompt.contains("Kotlin, no frameworks."))
    }

    @Test
    fun `documents are read in name order, so numbering them orders them`() {
        // **Name order is the only ordering a person can see.** A file called `01-…` comes first
        // because of what it is called, not because a setting says so — and that survives being
        // copied, renamed and committed by tools that know nothing about this app.
        val dir = project()
        ProjectTree.create(dir)
        File(ProjectTree.promptsDir(dir), "02-second.md").writeText("SECOND")
        File(ProjectTree.promptsDir(dir), "01-first.md").writeText("FIRST")
        val prompt = ProjectTree.prompt(dir)
        assertTrue("the numbered first one is first", prompt.indexOf("FIRST") < prompt.indexOf("SECOND"))
    }

    @Test
    fun `each document is named in the prompt it is part of`() {
        // A person reading the prompt back needs to know which document said what, and the agent is
        // told to read these — so the name travels with the text.
        val dir = project()
        ProjectTree.create(dir)
        File(ProjectTree.promptsDir(dir), "how-we-work.md").writeText("Read the tests first.")
        assertTrue("the file's name is in the text", ProjectTree.prompt(dir).contains("how-we-work.md"))
    }

    @Test
    fun `the old single file is read, because it was always in this folder`() {
        // **There is no migration, and this is why.** The `project_prompt.txt` this replaced lived at
        // `muffycodes_data/prompts/project_prompt.txt` — inside the very folder the read walks. So
        // every existing project's brief is picked up as one more document, and a migration step
        // would have been a second path to the same text.
        val dir = project()
        val old = AgentPrompt.projectFile(dir)
        old.parentFile?.mkdirs()
        old.writeText("The old brief, still true.")
        assertTrue(
            "the old file is in the prompt: ${ProjectTree.prompt(dir)}",
            ProjectTree.prompt(dir).contains("The old brief, still true."),
        )
    }

    @Test
    fun `an old file and a new document are both in the prompt, in name order`() {
        // **No precedence rule, because there is nothing to arbitrate.** The old file is a document
        // in the folder like any other, so a project that kept it and added to it gets both — which
        // is the flexible behaviour the folder was chosen for. A "folder wins" rule would silently
        // drop text a person had written and never said which.
        val dir = project()
        val old = AgentPrompt.projectFile(dir)
        old.parentFile?.mkdirs()
        old.writeText("OLD")
        File(ProjectTree.promptsDir(dir), "zz-later.md").writeText("NEW")
        val prompt = ProjectTree.prompt(dir)
        assertTrue("both are there: $prompt", prompt.contains("OLD") && prompt.contains("NEW"))
        assertTrue(
            "and the old one comes first, because p sorts before z",
            prompt.indexOf("OLD") < prompt.indexOf("NEW"),
        )
    }

    @Test
    fun `a folder that is not there is empty, not a failure`() {
        // A folder deleted by hand is a project with no brief, which works. Throwing here would take
        // down whatever asked.
        assertEquals("nothing, and no exception", "", ProjectTree.prompt(tmp.newFolder("gone")))
    }

    @Test
    fun `the prompt the agent is given is the folder's text`() {
        // **The seam that matters**: `AgentPrompt.project` is what the agent actually receives, and
        // it has to be the folder read rather than the old single file.
        val dir = project()
        ProjectTree.create(dir)
        File(ProjectTree.promptsDir(dir), "rules.md").writeText("Never push without asking.")
        assertTrue("the document reaches the agent", AgentPrompt.project(dir).contains("Never push without asking."))
    }

    @Test
    fun `an empty project still gets the app's default brief`() {
        // A project with nothing written yet must not hand the agent an empty brief — the default is
        // what makes a brand new project work before anybody has written a word.
        val dir = project()
        assertTrue("the default is there", AgentPrompt.project(dir).isNotBlank())
    }
}
