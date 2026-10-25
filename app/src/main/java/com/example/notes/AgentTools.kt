package com.example.notes

import org.json.JSONObject
import java.io.File

/**
 * What the agent can do, and what it is refused.
 *
 * **Every write goes through [Workspace]**, the same funnel the screens use, so a lock, a
 * hidden name and the structural folders are enforced here by construction rather than by this
 * file remembering them. That is the whole reason the lock moved into the funnel before the
 * agent existed: "the agent honours locks" needs no agent code at all.
 *
 * A result's **first line is `0` or `1`, always**, and a result is never silently shortened — a
 * truncated body says so and says how much it is showing. Both are promises the model is told
 * about, and both are things a silent tool would break: an agent whose tool goes quiet is an
 * agent that either hangs or invents.
 */
class AgentTools(
    private val ws: Workspace,
    private val project: String,
    private val git: ProjectGit,
    private val log: AgentLog,
    private val maxChars: Int = MAX_RESULT,
) {

    /** The project's own folder: what the agent stands in. */
    val projectDir: File get() = File(ws.projectsDir, project)

    /** The part of it that lives on GitHub. */
    val worktree: File get() = ws.repoOf(project)

    companion object {
        /** Enough for a file, a listing or a diff; past this a result is truncated and says so. */
        const val MAX_RESULT = 20_000

        /** The tools that end a turn. They are called alone; anything beside them is refused. */
        val TERMINATING = setOf("ask", "conclude", "wait")

        /** The tools that change a file. */
        val WRITING = setOf("edit", "create", "delete", "move", "clone", "restore")

        /**
         * How many calls in a row may change nothing and answer nothing before the loop stops.
         *
         * **Not a turn count.** An idle agent calls `wait` for ever by design, so a limit on
         * turns would stop every healthy session; a limit on *unproductive* turns trips in
         * minutes on an agent that is spinning and never on one that is waiting.
         */
        const val IDLE_CALLS = 20

        fun specs(): List<ToolSpec> = listOf(
            spec(
                "tree",
                "List the files in a folder of this project. Folders end with a slash.",
                "{\"path\":\"where to look, relative to the project\",\"depth\":2}",
            ),
            spec(
                "read",
                "Read a text file. Always read before you change one.",
                """{"path":"the file, relative to the project","from":1,"lines":200}""",
            ),
            spec(
                "edit",
                "Replace text in a file. Either find-and-replace, or write the whole file.",
                """{"path":"the file","find":"text to look for","replace":"what to put there"}""",
            ),
            spec(
                "create",
                "Make a new file, with its folders if they do not exist.",
                """{"path":"the file","text":"its contents"}""",
            ),
            spec("delete", "Delete one file or an empty folder.", """{"path":"what to delete"}"""),
            spec(
                "move",
                "Move a file into a folder, or rename it inside its own folder.",
                """{"path":"the file","into":"the folder","name":"the new name"}""",
            ),
            spec(
                "clone",
                "Clone a GitHub repository into this project's GitHub folder. Refused when " +
                    "there are changes that would be lost.",
                """{"url":"https://github.com/owner/repo"}""",
            ),
            spec(
                "history",
                "List the versions of a file, newest first, with the id each is restored from.",
                """{"path":"the file"}""",
            ),
            spec(
                "restore",
                "Put an old version of a file back. It becomes a new version, nothing is lost.",
                """{"path":"the file","revision":"the id history gave you"}""",
            ),
            spec(
                "reason",
                "Write down what you are thinking, so the person can read it later. It goes in " +
                    "your record, not in their files.",
                """{"text":"your reasoning"}""",
            ),
            spec(
                "ask",
                "End the turn with one question for the person. Called alone.",
                """{"question":"what you need to know"}""",
            ),
            spec(
                "conclude",
                "End the turn: the work is done. Say what you did. Called alone.",
                """{"text":"what you did"}""",
            ),
            spec(
                "wait",
                "End the turn and stay idle until the person speaks. Called alone.",
                """{}""",
            ),
        )

        private fun spec(name: String, description: String, parameters: String) = ToolSpec(
            name = name,
            description = description,
            parameters = JSONObject(parameters),
        )
    }

    // ---- running ------------------------------------------------------------

    /**
     * Runs one call and returns its body, `0` or `1` first.
     *
     * `ask`, `conclude` and `wait` are not run here: the loop ends the turn on them, because
     * the turn is what they end. Everything else is answered here, including the refusal.
     */
    fun run(call: Call): String = try {
        val args = JSONObject(call.args.ifBlank { "{}" })
        when (call.tool) {
            "tree" -> tree(args)
            "read" -> read(args)
            "edit" -> edit(args)
            "create" -> create(args)
            "delete" -> delete(args)
            "move" -> move(args)
            "clone" -> clone(args)
            "history" -> history(args)
            "restore" -> restore(args)
            "reason" -> reason(args)
            else -> refusal("There is no tool called ${call.tool}")
        }
    } catch (e: Throwable) {
        refusal(Secrets.explain(e, "That tool could not be run"))
    }

    private fun tree(args: JSONObject): String {
        val root = resolve(args.optString("path", "GitHub"))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        if (!root.exists()) return refusal("There is nothing at " + rel(root))
        if (!root.isDirectory) return refusal("${rel(root)} is a file, not a folder")
        val depth = args.optInt("depth", 3).coerceIn(1, 8)
        val out = mutableListOf<String>()
        walk(root, depth, out)
        return success(if (out.isEmpty()) "${rel(root)} is empty" else out.joinToString("\n"))
    }

    private fun walk(dir: File, left: Int, out: MutableList<String>) {
        val entries = ws.list(dir)
        for (entry in entries) {
            out.add(rel(entry.file) + if (entry.isFolder) "/" else "")
            if (entry.isFolder && left > 1) walk(entry.file, left - 1, out)
        }
    }

    private fun read(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        if (!file.exists()) return refusal("There is no file at ${rel(file)}")
        if (file.isDirectory) return refusal("${rel(file)} is a folder; use tree")
        if (ws.kindOf(file) != EntryKind.TEXT) {
            return refusal("${rel(file)} is not a text file, so there is nothing to show you")
        }
        val text = ws.read(file)
        val from = args.optInt("from", 1).coerceAtLeast(1)
        val lines = text.split("\n")
        val wanted = args.optInt("lines", 0)
        val slice = if (wanted > 0) lines.drop(from - 1).take(wanted) else lines.drop(from - 1)
        if (slice.isEmpty()) {
            return success("nothing from line $from: the file has ${lines.size} lines")
        }
        val body = slice.joinToString("\n")
        val note = if (from > 1 || slice.size < lines.size) {
            "\n(showing lines $from to ${from + slice.size - 1} of ${lines.size})"
        } else {
            ""
        }
        return success(body + note)
    }

    private fun edit(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be changed") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.isFile) return refusal("There is no file at ${rel(file)}")
        val before = ws.read(file)
        val find = args.optString("find", "")
        val text = if (find.isEmpty()) {
            args.optString("text", "")
        } else {
            if (!before.contains(find)) return refusal("That text is not in ${rel(file)}")
            if (!args.has("replace")) return refusal("Say what to put there")
            before.replace(find, args.optString("replace"))
        }
        if (text == before) return success("${rel(file)} already said that, so nothing changed")
        // **Through the funnel.** A locked file, a hidden name or a structural folder is refused
        // by `write` itself, and the refusal is the whole answer.
        if (!ws.write(file, text)) return refusal("${rel(file)} cannot be changed")
        return commit("Edited ${file.name}", "${rel(file)} changed")
    }

    private fun create(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be made") }
        if (file.exists()) return refusal("There is already something at ${rel(file)}")
        val parent = file.parentFile ?: return refusal("That path has no folder in it")
        mayWrite(file)?.let { return refusal(it) }
        if (!parent.isDirectory) {
            // Folders are made by the funnel too, so a locked ancestor is refused rather than
            // quietly written around.
            val made = makeFolders(parent)
            if (made.isFailure) {
                return refusal(made.exceptionOrNull()?.message ?: "the folder could not be made")
            }
        }
        val made = ws.createFile(parent, file.name, args.optString("text", ""))
        return made.fold(
            onSuccess = { commit("Created ${file.name}", "made ${rel(file)}") },
            onFailure = { refusal(it.message ?: "it could not be made") },
        )
    }

    private fun makeFolders(dir: File): Result<Unit> {
        if (dir.isDirectory) return Result.success(Unit)
        val parent = dir.parentFile ?: return Result.failure(IllegalArgumentException("no parent"))
        if (parent != projectDir && !parent.isDirectory) {
            val up = makeFolders(parent)
            if (up.isFailure) return up
        }
        val made = ws.createFolder(parent, dir.name)
        return made.map { }
    }

    private fun delete(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be deleted") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.exists()) return refusal("There is nothing at ${rel(file)}")
        val wasFolder = file.isDirectory
        val removed = ws.delete(file)
        return removed.fold(
            onSuccess = {
                commit(
                    if (wasFolder) "Deleted ${file.name}" else "Deleted ${file.name}",
                    "deleted ${rel(file)}",
                )
            },
            onFailure = { refusal(it.message ?: "it could not be deleted") },
        )
    }

    private fun move(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be moved") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.exists()) return refusal("There is nothing at ${rel(file)}")
        val into = resolve(args.optString("into", file.parentFile?.let { rel(it) } ?: "GitHub"))
            .getOrElse { return refusal(it.message ?: "that folder cannot be used") }
        if (!into.isDirectory) return refusal("${rel(into)} is not a folder")
        mayWrite(File(into, file.name))?.let { return refusal(it) }
        val name = args.optString("name", "").trim()
        val moved = if (name.isEmpty()) {
            ws.move(file, into)
        } else {
            // Renaming into a different folder is two steps, in the order that cannot lose
            // the file: rename where it stands, then move it.
            val same = into.canonicalFile == file.parentFile?.canonicalFile
            if (same) {
                ws.rename(file, name)
            } else {
                ws.rename(file, name).mapCatching { renamed -> ws.move(renamed, into).getOrThrow() }
            }
        }
        return moved.fold(
            onSuccess = { commit("Moved ${file.name}", "moved ${rel(file)}") },
            onFailure = { refusal(it.message ?: "it could not be moved") },
        )
    }

    private fun clone(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isEmpty()) return refusal("Say which repository to clone")
        val changes = git.changes(project)
        if (!changes.isClean) {
            return refusal(
                "This project has ${changes.total} changes of its own. Cloning over them " +
                    "would lose that work, so nothing happened."
            )
        }
        val result = git.cloneInto(project, url)
        return if (result.success) success("cloned into GitHub/") else refusal(result.message)
    }

    private fun history(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        val path = rel(file)
        val versions = git.versions(project, path)
        if (versions.isEmpty()) return success("$path has no versions yet")
        return success(
            versions.take(20).joinToString("\n") { (commit, _) ->
                "${commit.shortId}  ${commit.message}  ${stamp(commit.timestamp)}"
            }
        )
    }

    private fun restore(args: JSONObject): String {
        val file = resolve(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be restored") }
        val revision = args.optString("revision").trim()
        mayWrite(file)?.let { return refusal(it) }
        if (revision.length < 7) return refusal("Give the version id that history showed you")
        if (ws.isLocked(file)) return refusal("${rel(file)} is locked, so it is read-only")
        val restored = git.restore(project, rel(file), revision)
        return restored.fold(
            onSuccess = { commit("Restored ${file.name}", "put ${rel(file)} back") },
            onFailure = { refusal(it.message ?: "it could not be restored") },
        )
    }

    private fun reason(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return refusal("Say what you are thinking")
        log.note("reason", text)
        return success("written into your record")
    }

    // ---- the promises -------------------------------------------------------

    /**
     * Commits what a tool just changed, and says which of the two things happened.
     *
     * A file that changed but could not be committed is a `1`: the work is on the phone and not
     * in its history, which is the one state this app exists to make impossible to miss.
     */
    private fun commit(what: String, done: String): String {
        val outcome = git.commitOutcome(project, what)
        return if (outcome.ok) {
            success(done)
        } else {
            refusal("$done — but the history did not record it: ${outcome.error}")
        }
    }

    private fun success(body: String): String = trim("0\n$body")

    private fun refusal(said: String): String = trim("1\n$said")

    /**
     * Never silently shortens. A model told "here is the file" and handed half of it will
     * confidently edit the wrong half.
     */
    private fun trim(body: String): String {
        if (body.length <= maxChars) return body
        return "1\n" + body.take(maxChars) +
            "\n\n(showing first $maxChars of ${body.length} characters; ask again with a range)"
    }

    // ---- where the agent may stand ------------------------------------------

    /**
     * A path the agent gave, as a file in this project — or a refusal.
     *
     * Refused: an absolute path, anything with `..` in it, and anything that resolves outside
     * the project once the phone's own links are followed. All three are the same mistake from
     * the model's side ("I will just look one folder up"), and the answer to all three is one
     * sentence rather than three mechanisms.
     */
    fun resolve(raw: String): Result<File> {
        val clean = raw.trim().replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return Result.success(projectDir)
        val parts = clean.split('/')
        if (parts.any { it == ".." }) {
            return Result.failure(
                IllegalArgumentException("A path may not go up out of the project")
            )
        }
        if (parts.any { it.isEmpty() }) {
            return Result.failure(IllegalArgumentException("That path has an empty name in it"))
        }
        val target = try {
            File(projectDir, clean).canonicalFile
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("That path could not be read"))
        }
        val base = try {
            projectDir.canonicalFile
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("This project's folder could not be read")
            )
        }
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            return Result.failure(IllegalArgumentException("That path is outside this project"))
        }
        return Result.success(target)
    }

    /**
     * Why [file] may not be written, or null when it may.
     *
     * One sentence for every writing tool, in one place, for the same reason the lock lives in
     * the funnel: a rule each tool remembers is a rule one tool forgets. `project_prompt.txt`
     * is the case that matters — the agent may read the instructions it is given and may not
     * rewrite them, which is the difference between following a brief and editing it.
     */
    private fun mayWrite(file: File): String? = if (insideWorktreeOrAgent(file)) {
        null
    } else {
        "You may only write inside GitHub/ or agent/, and ${rel(file)} is neither"
    }

    /** True for the two places the agent may write: `GitHub/` and `agent/`. */
    private fun insideWorktreeOrAgent(file: File): Boolean {
        val path = rel(file)
        return path == "GitHub" || path.startsWith("GitHub/") ||
            path == "agent" || path.startsWith("agent/")
    }

    /** A version's time, in the one shape a row reads in. */
    private fun stamp(at: Long): String = LogFormat.stamp(at)

    /** A file's name as the agent says it: relative to the project, with forward slashes. */
    fun rel(file: File): String {
        val base = projectDir.absolutePath
        val full = file.absolutePath
        if (full == base) return ""
        return if (full.startsWith(base + File.separator)) {
            full.substring(base.length + 1).replace(File.separatorChar, '/')
        } else {
            file.name
        }
    }
}
