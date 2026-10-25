package com.example.notes

import java.io.File

/**
 * The two prompt layers the app ships, and the rule that it never overwrites them.
 *
 * ① is how to use *this app*: the tools, the rules, the shape of a turn. ② is how to work in
 * *this project*, which starts empty and is meant to be edited by the person. Both are ordinary
 * text files in the account, so they can be read on the phone, edited with the editor, versioned
 * with everything else — and both are written **only when they are missing**, because a default
 * that overwrites an edit is a default that loses work.
 *
 * The third layer is not a file this class writes: it is `agent/context.log`, and it belongs to
 * the agent (see [AgentLog]).
 */
object AgentPrompt {

    const val SYSTEM_FILE = "system_prompt.txt"
    const val PROJECT_FILE = "project_prompt.txt"

    /**
     * ① — the instructions.
     *
     * Written for a model that has no other idea what this thing is. The rules it states are the
     * ones the loop also *enforces*, and that duplication is deliberate: a rule only in the
     * prompt is a rule a model can talk itself out of, and a rule only in the code is a rule the
     * model will trip over without being told.
     */
    val SYSTEM_DEFAULT = """
        You are working inside a phone app. A person is talking to you about one project of
        theirs, and you change their files for them.

        WHERE YOU ARE
        The project folder holds:
          GitHub/        the files that live on GitHub. This is the only part that syncs.
          agent/         your own record. session.log is the evidence; context.log is your
                         context, and you may edit it.
          project_prompt.txt  how to work in this project. Read it. It is short.
        You cannot see or change anything outside this project, another project included.

        HOW A TURN WORKS
        Call one or more tools. Every call gets a result whose first line is 0 (it worked) or 1
        (it did not, and the rest says why — read it, do not guess). Then you are asked again.
        A turn ends in exactly one of three ways:
          ask       you need the person. Ask one clear question and stop.
          conclude  the work is done. Say what you did, in a sentence or two.
          wait      you are idle and the person has not spoken yet.
        These three end the turn, so they are called ALONE. Anything called with them is refused.

        RULES THE APP ENFORCES
          - A locked file, and anything inside a locked folder, is read-only. A tool that would
            change it is refused, and the refusal is the answer: do not try another way.
          - Names that start with a dot, and the folders the app keeps, are not yours.
          - Save is a commit on the phone. Nothing you do is pushed to GitHub; the person pushes.
          - A result is never silently shortened. If it says "showing first N of M", it is
            truncated, and you may ask for the rest by range.

        HOW TO BEHAVE
          - Read before you write. Guess only when reading is impossible.
          - Change as little as the task needs. A rename is not a rewrite.
          - Your record is the memory of this project: write a note when something is true and
            worth remembering, and clear out notes that stopped being true. Do not annotate.
          - If the last thing you were told is missing, say so and ask. Silence is worse.

        FILES YOU MAY WRITE
        Anything inside GitHub/, and your own agent/ folder. Nothing else.
    """.trimIndent()

    /** ② — the project's own instructions, empty until somebody writes them. */
    val PROJECT_DEFAULT = """
        # How to work in this project

        Nothing written here yet. The person who owns this project can edit this file, and you
        should follow what it says. Until then, work from the task you were given.
    """.trimIndent()

    /**
     * Reads a layer, writing the shipped text first when there is no file.
     *
     * A blank file is not an edit that says "nothing": it is a file somebody emptied, and the
     * answer to it is the same as for a missing one — the agent must not be handed an empty
     * instruction set because a save was interrupted.
     */
    fun layer(file: File, fallback: String): String {
        val text = try {
            if (file.isFile) file.readText() else ""
        } catch (t: Throwable) {
            ""
        }
        if (text.isNotBlank()) return text
        return try {
            file.parentFile?.mkdirs()
            file.writeText(fallback)
            fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    /** ① for [accountDir]. */
    fun system(accountDir: File): String =
        layer(File(accountDir, SYSTEM_FILE), SYSTEM_DEFAULT)

    /** ② for the project in [projectDir]. */
    fun project(projectDir: File): String =
        layer(File(projectDir, PROJECT_FILE), PROJECT_DEFAULT)

    /**
     * What the model is told: ① then ②, and then nothing.
     *
     * The context is *not* concatenated here. It is sent as the conversation before the burst,
     * so that clearing a row out of it shortens the request from that row onwards instead of
     * rewriting one large message — which is the difference between a cache hit and a full
     * re-read of everything the app was told.
     */
    fun systemMessage(accountDir: File, projectDir: File): String = buildString {
        append(system(accountDir).trim())
        append("\n\n---\n\n")
        append("# This project\n\n")
        append(project(projectDir).trim())
        append("\n")
    }
}
