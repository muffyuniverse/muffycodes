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
    fun `a new project gets an empty prompts folder`() {
        val dir = project()
        val failed = ProjectTree.create(dir)
        assertTrue("nothing failed: $failed", failed.isEmpty())
        assertTrue("the folder is there", ProjectTree.promptsDir(dir).isDirectory)
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
    fun `a project with the old single file still gets its prompt`() {
        // **The one case that would lose work.** Every project made before this change has a single
        // `project_prompt.txt`. It is read as one more document rather than being moved, because
        // relocating a person's text to tidy up after a feature removal is how work goes missing.
        val dir = project()
        val old = AgentPrompt.projectFile(dir)
        old.parentFile?.mkdirs()
        old.writeText("The old brief, still true.")
        assertEquals("the old file is read", "The old brief, still true.", ProjectTree.prompt(dir))
    }

    @Test
    fun `the folder wins over the old file when both are there`() {
        // Somebody who has started using the folder means it. Reading both would put a stale brief
        // in front of the agent for ever, and nothing would ever say why.
        val dir = project()
        ProjectTree.create(dir)
        val old = AgentPrompt.projectFile(dir)
        old.parentFile?.mkdirs()
        old.writeText("STALE")
        File(ProjectTree.promptsDir(dir), "now.md").writeText("CURRENT")
        val prompt = ProjectTree.prompt(dir)
        assertTrue("the current text is there", prompt.contains("CURRENT"))
        assertTrue("and the stale one is not", !prompt.contains("STALE"))
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
