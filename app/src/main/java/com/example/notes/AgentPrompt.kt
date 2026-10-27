package com.example.notes

import java.io.File

/**
 * The two prompt layers the app ships, and the rule that it never overwrites them.
 *
 * ① is how to use *this app*: the tools, the rules, the shape of a turn. ② is how to work in
 * *this project*, which starts empty and is meant to be edited by the person. Both are ordinary
 * text files in the account, so they can be read on the phone, edited with the editor, versioned
 * with everything else — and both are written when they are missing, because a default that
 * overwrites an edit is a default that loses work.
 *
 * **A file that exists is the person's.** There is no record of the texts this app used to ship
 * and no comparison with them: the app is being developed rather than released, so a ① on a phone
 * was written by this build or by a person, and "is this still our default?" is a question about
 * installations that do not exist. One rule, then: write it when it is missing or empty, and never
 * touch it otherwise.
 *
 * The third layer is not a file this class writes: it is the `in` rows of the record, and they
 * belong to the agent (see [AgentLog]).
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
        **The project folder is the repository.** Its files are the ones on GitHub, and the person
        pushes them; there is no second folder to find. It holds:
          agent/         the app's, not yours: two files, oldest line first, both readable.
                         context.log is the rows in your context, as `id size name body` —
                         what you look at to decide what to forget. session.log is every
                         event that happened, with `kind` and `ctx`. Read them. Do not
                         change them. They are in git's exclude list, so they never leave
                         the phone.
          project_prompt.txt  how to work in this project. Read it. It is short.
        You cannot see or change anything outside this project, another project included.

        HOW A TURN WORKS
        Call one or more tools. Every call gets a result whose first line is 0 (it worked) or 1
        (it did not, and the rest says why — read it, do not guess). Then you are asked again.
        A turn ends in exactly one of two ways:
          ask       you need the person. Ask one clear question and stop.
          conclude  the work is done. Say what you did, in a sentence or two.
        These two end the turn, so they are called ALONE. Anything called with them is refused.
        Words with no call end it too: if there is nothing to call, say what you did. That is a
        conclusion, not a mistake - the app keeps the conversation warm itself, so nothing is
        waiting on you to say that you are waiting.

        RULES THE APP ENFORCES
          - A locked file, and anything inside a locked folder, is read-only. A tool that would
            change it is refused, and the refusal is the answer: do not try another way.
          - Names that start with a dot, and the folders the app keeps, are not yours.
          - Save is a commit on the phone. Nothing you do is pushed to GitHub; the person pushes.
          - **A result that would not fit is written to a file and you are told where.** The answer
            gives the path under agent/background_results/ and how many characters it was. Read it in
            ranges — `from`/`to` for lines, `grep` for the ones that match — rather than guessing at
            what you did not see. Nothing is ever cut short.
          - **Any command can run in two ways.** Called directly, its result comes back here when it
            finishes, or it is stopped at the operator's limit. Called through `background`, the
            answer is a path under agent/background_results/ and the work carries on beside the
            conversation: read that file to see whether it has finished. A file reading "running"
            means the command has not gone wrong. Do not start the same command twice. When one does
            finish while you are idle, the app wakes you with a note naming the file: read it, then
            carry on or conclude. A command
            that changes files still changes them while the turn carries on, so put writing
            commands in the background only when the wait is the problem.
          - **A command that passed the operator's limit is stopped, and says so.** "stopped after
            60 s; nothing was kept" is a real answer: nothing was half-written. Do not retry the same
            command unchanged; either do it another way or tell the person it is too big.

        HOW TO BEHAVE
          - Read before you write. Guess only when reading is impossible.
          - Change as little as the task needs. A rename is not a rewrite.
          - Your record is the memory of this project. When it fills up, call `forget` with the
            ids of the rows that no longer matter to the task - a call together with its result,
            and never the newest task. A range is one argument: "2 5 7-12 14" names ten rows, both
            ends included, so a block of calls and their results goes in one call rather than ten.
            Nothing is lost: a forgotten row stays in the record, and you can still read it.
          - If the last thing you were told is missing, say so and ask. Silence is worse.

        FILES YOU MAY WRITE
          Anything in the project. The two exceptions: names that start with a dot (the app's, and
          `.git` above all), and `agent/`, which is the record and is yours to read, not to write.
          `forget` is the one thing you may do to your context, and it names ids.
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
     * **A file that exists is the person's, word for word.** There is no list of the texts the app
     * used to ship and no comparison with them: the app is being developed, not released, so a file
     * on a phone was written by this build or by a person, and a text from a round that no longer
     * exists is not a case.
     *
     * A blank file is not an edit that says "nothing" either: it is a file somebody emptied, and
     * the answer is the same as for a missing one — the agent must not be handed an empty
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
            text.ifBlank { fallback }
        }
    }

    /**
     * ① for [accountDir] — **read from the system project, where a person can open it.**
     *
     * It used to be a file at the account's root, which is a place the app draws as the project
     * list: nothing in the interface led to it, so "the system prompt is a file you can edit" was
     * true and unreachable. It lives in the system project now, which is a row like any other and
     * opens in the editor like any other.
     *
     * **One place, and no fallback.** A second path to the same prompt — "read the project's file,
     * or the one at the root if that is missing" — is compatibility machinery for a release nobody
     * runs: this app is being developed, not shipped, so there is no account holding an older
     * layout. Two places to look would also be two places to disagree, and the question "which
     * prompt is the agent given" would need a rule instead of a path.
     */
    fun system(accountDir: File): String =
        layer(File(File(accountDir, Workspace.SYSTEM_PROJECT), SYSTEM_FILE), SYSTEM_DEFAULT)

    /** ② for the project in [projectDir]. */
    fun project(projectDir: File): String =
        layer(File(projectDir, PROJECT_FILE), PROJECT_DEFAULT)

    /**
     * The two layers as they were at one moment, and the message they make together.
     *
     * **[read] is called at the start of a turn, never once at startup and never per request.**
     * A turn is one conversation: if the person edits the project's instructions while the agent
     * is working, the turn finishes under the brief it began with, and the next turn sees the
     * edit. Re-reading per request would send two different briefs inside one conversation, and
     * holding them in memory across turns would mean an edit that silently does not arrive.
     *
     * ① comes first and ② after it, so an edit to the project's instructions invalidates the
     * request only from ② onwards: a provider's implicit cache matches on the longest common
     * *prefix*, and the instructions above are still the same bytes.
     */
    data class Layers(val system: String, val project: String) {
        /** What the model is told: ①, then ②, and then nothing. */
        val message: String get() = withLine(null)

        /**
         * The same message with one app-authored line at the very end.
         *
         * **At the end, because of the cache.** A provider matches the longest common prefix, so a
         * line appended after ① and ② costs the line rather than the instructions — which is the
         * difference between a warning that is free and one that re-reads everything the app was
         * told, on every turn it appears.
         */
        fun withLine(line: String?): String = buildString {
            append(system.trim())
            append(SEPARATOR)
            append("# This project\n\n")
            append(project.trim())
            append("\n")
            if (line != null) {
                append("\n")
                append(line.trim())
                append("\n")
            }
        }
    }

    /**
     * The boundary between the two layers.
     *
     * A constant, so that "did only the project layer change?" is a question about the text
     * after this marker rather than about where the app happened to put a blank line.
     */
    const val SEPARATOR = "\n\n---\n\n"

    /** Reads both layers from their files, now. */
    fun read(accountDir: File, projectDir: File): Layers =
        Layers(system(accountDir), project(projectDir))
}
