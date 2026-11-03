package com.example.notes

import java.io.File

/**
 * **What a new project starts with, now that a project is not one of five kinds.**
 *
 * ## The operator's decision
 *
 * *"remove project type selection. I think that we should just create empty system_data folder tree for
 * each project when it is added and put there subfolders for all of our doc types. And set up it so
 * that for example, project prompt just takes and inserts in the context all the files from
 * project_prompt subfolder. This way it can be flexible and user just can ask agent to copy or write
 * there whatever he wants and then lock those files so that they cant be changed without his agreement"*
 *
 * *"no migration, there are no users yet, remove this feature fully (in both ui and mechanics)"*
 *
 * So there is no `AppType`, no picker, no `Presets`, and no `ProjectMeta`. What is left is a folder
 * tree that every project gets, and a prompt assembled from a folder rather than a file.
 *
 * ## Why a folder and not a file
 *
 * A single `project_prompt.txt` is one document, and the only way to add a second one is to append to
 * it — which means the app's text and the person's text are the same text, and neither can be locked
 * or replaced on its own. A folder of documents makes each one an ordinary file: the agent can add one,
 * the person can lock one, and the prompt is whatever is in the folder at the moment the agent is
 * asked. That is the flexibility the operator asked for, and it needs no rule about which file wins.
 *
 * ## What is created
 *
 * The subfolders named here, empty. **Empty on purpose**: a starter document would be a type by another
 * name, and the point is that nothing is prescribed. A person (or the agent, on their instruction)
 * writes what belongs in each.
 */
object ProjectTree {

    /** Inside [AgentPrompt.DATA], which is `muffycodes_data`. */
    const val PROMPTS = AgentPrompt.PROMPTS

    /** The documents that make up the project prompt. Read in name order. */
    fun promptsDir(projectDir: File): File =
        File(File(projectDir, AgentPrompt.DATA), PROMPTS)

    /**
     * Every document in the prompts folder, in name order, as one string.
     *
     * **Name order, because a person can see it.** Numbering a file `01-…` is a way of saying "this
     * first" that needs no setting, no metadata and no explaining — and it survives being copied,
     * renamed and committed by tools that know nothing about this app.
     *
     * **There is nothing to migrate, and that is worth stating.** The single `project_prompt.txt` this
     * replaced lived at `muffycodes_data/prompts/project_prompt.txt` — *inside this very folder*. So
     * every project made before the change already has its brief where the folder read looks, and
     * this function picks it up as one more document. A migration step would have been a second path
     * to the same text, which is the thing this file's own rules keep refusing.
     */
    fun prompt(projectDir: File): String {
        val dir = promptsDir(projectDir)
        if (!dir.isDirectory) return ""
        val files = dir.listFiles()
            // **The marker is not a document.** An empty folder is invisible to git, so the folder
            // holds a hidden marker — and every file in here is read as part of the prompt, so
            // without this the brief would open with an empty section called `.gitkeep`. The rule
            // that a marker is not content lives here, where the reading happens, rather than in the
            // marker's name.
            ?.filter { it.isFile && it.name != Workspace.KEEPER }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (files.isEmpty()) return ""
        return files.joinToString("\n\n") { file ->
            // **The name is in the text**, because the agent is told to read these and a person
            // reading the prompt back needs to know which document said what.
            val body = try {
                file.readText()
            } catch (t: Throwable) {
                ""
            }
            if (body.isBlank()) "" else "# ${file.name}\n\n$body"
        }.trim()
    }

    /**
     * Makes the folders a project needs, and answers what it could not make.
     *
     * **It delegates to [Workspace.ensureProjectData]**, which already knows the set: `prompts`,
     * `dictionary`, `skills`, `wiki`, `mcp`, one list, so adding a folder is one line. A second copy
     * of that list here is a folder the agent reads that this does not make, and the two would drift
     * on the first rename — which is the exact reason `MCP_DIR` is derived rather than written twice.
     *
     * **The markers are files, so they DO show as changes** until they are committed. An empty folder
     * is invisible to git, and the marker is what makes it a real, committable part of the project —
     * so the caller commits them, which is why this answers a list rather than nothing.
     */
    fun create(projectDir: File): List<String> = Workspace.ensureProjectData(projectDir)
}
