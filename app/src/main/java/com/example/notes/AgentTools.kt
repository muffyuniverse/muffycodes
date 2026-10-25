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
    val projectDir: File get() = ws.projectDir(project)

    /**
     * The part of it that lives on GitHub — **which is the project's folder itself.**
     *
     * There used to be two of these: the project's folder, and a `GitHub/` worktree inside it where
     * the tracked files lived. The merge means they are one, so a tool that names "the files that
     * sync" and a tool that names "the project" are naming the same folder.
     */
    val worktree: File get() = ws.repoOf(project)

    companion object {
        /** Enough for a file, a listing or a diff; past this a result is truncated and says so. */
        const val MAX_RESULT = 20_000

        /** How many matches a search prints before it says there are more. */
        const val MAX_MATCHES = 200

        /** A file bigger than this is not searched: reading it into memory on a phone is the bug. */
        const val MAX_SCAN = 2L * 1024 * 1024

        /**
         * The tools that end a turn. They are called alone; anything beside them is refused.
         *
         * **The record's set, not a second copy of it.** `AgentLog` owns this vocabulary because
         * it has to read and seal a record with no workspace, repository or model behind it — and
         * two sets with the same three names is a set that drifts the day one of them gains a
         * fourth.
         */
        val TERMINATING = AgentLog.TERMINATING

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
                "List the files in a folder of this project. Folders end with a slash. " +
                    "`name` keeps only paths whose name matches; `grep` keeps only files whose " +
                    "text matches, and shows the matching lines.",
                "{\"path\":\"where to look, relative to the project\",\"depth\":2," +
                    "\"name\":\"a pattern for file names\",\"grep\":\"a pattern for their text\"}",
            ),
            spec(
                "read",
                "Read a text file. Always read before you change one. With `grep`, show only " +
                    "the lines that match, each with its line number.",
                """{"path":"the file, relative to the project","from":1,"lines":200,""" +
                    """"grep":"a pattern to look for"}""",
            ),
            spec(
                "edit",
                "Replace text in a file. Either find-and-replace, or write the whole file.",
                """{"path":"the file","find":"text to look for","replace":"what to put there"}""",
            ),
            spec(
                "forget",
                "Take rows out of your context when it is too big. The rows stay in the record.",
                """{"ids":[12,13]}""",
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
                "Clone a GitHub repository into this project's folder. Refused when there are " +
                    "changes that would be lost.",
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
            "forget" -> forget(args)
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
        val root = resolve(args.optString("path", ""))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        if (!root.exists()) return refusal("There is nothing at " + rel(root))
        if (!root.isDirectory) return refusal("${rel(root)} is a file, not a folder")
        val depth = args.optInt("depth", 3).coerceIn(1, 8)
        val name = pattern(args.optString("name", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        val content = pattern(args.optString("grep", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        if (name == null && content == null) {
            val out = mutableListOf<String>()
            walk(root, depth, out)
            return success(if (out.isEmpty()) "${rel(root)} is empty" else out.joinToString("\n"))
        }
        // **A search, not a listing.** Once a pattern is given the answer is files, not folders:
        // a folder is in the result because something in it matched, and printing the folder adds
        // a line nobody asked about. What a person (and a model) wants is the path, and for a
        // content match, the line.
        val found = mutableListOf<String>()
        search(root, depth, name, content, found)
        if (found.isEmpty()) return success("nothing matches in ${rel(root)}")
        // **A search that stopped says so.** [search] stops at [MAX_MATCHES] rather than scanning a
        // project to its end, so a full list is either complete or bounded — and a model told
        // "here they are" about a bounded list will act as though it saw everything.
        val note = if (found.size >= MAX_MATCHES) {
            "\n(showing the first $MAX_MATCHES matches; narrow the pattern)"
        } else {
            ""
        }
        return success(found.joinToString("\n") + note)
    }

    /**
     * Files under [dir] that match [name] and [content], walking at most [left] levels.
     *
     * [content] is matched against the whole file and the result is the **matching lines**, so one
     * search answers "where is this said" rather than "which file says it". A file too large to be
     * worth reading is skipped and said so, rather than being read into memory on a phone.
     */
    private fun search(
        dir: File,
        left: Int,
        name: Regex?,
        content: Regex?,
        out: MutableList<String>,
    ) {
        for (entry in ws.list(dir)) {
            if (entry.isFolder) {
                if (left > 1) search(entry.file, left - 1, name, content, out)
                continue
            }
            if (name != null && !name.containsMatchIn(entry.name)) continue
            if (content == null) {
                out.add(rel(entry.file))
                continue
            }
            if (ws.kindOf(entry.file) != EntryKind.TEXT) continue
            if (entry.file.length() > MAX_SCAN) {
                out.add("${rel(entry.file)} is too big to search")
                continue
            }
            val text = ws.read(entry.file)
            if (!content.containsMatchIn(text)) continue
            for ((index, line) in text.split("\n").withIndex()) {
                if (!content.containsMatchIn(line)) continue
                out.add("${rel(entry.file)}:${index + 1}: ${line.trim().take(200)}")
                if (out.size >= MAX_MATCHES) return
            }
        }
    }

    /**
     * A pattern from the model, or null when it gave none.
     *
     * **A refusal, not a crash.** A model writing `[` is making a mistake about a regular
     * expression, and the answer it needs is that sentence — not an exception turned into "that
     * tool could not be run", which says nothing about what to fix.
     */
    private fun pattern(raw: String): Result<Regex?> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.success(null)
        return try {
            Result.success(Regex(text, RegexOption.IGNORE_CASE))
        } catch (e: Throwable) {
            Result.failure<Regex?>(IllegalArgumentException("that is not a regular expression"))
        }
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
        if (!file.exists()) {
            // **The one name an older instruction set still points at.** The record used to be two
            // files; this build folds the second into the first. An agent holding instructions from
            // before that (a prompt the person edited, so the app would not replace it) will ask for
            // `agent/context.log` — and "there is no file" would leave it guessing.
            if (file.name == AgentLog.CONTEXT) {
                return refusal(
                    "agent/${AgentLog.CONTEXT} was folded into agent/${AgentLog.SESSION}. " +
                        "Read that; forget takes rows out of your context.",
                )
            }
            return refusal("There is no file at ${rel(file)}")
        }
        if (file.isDirectory) return refusal("${rel(file)} is a folder; use tree")
        if (ws.kindOf(file) != EntryKind.TEXT) {
            return refusal("${rel(file)} is not a text file, so there is nothing to show you")
        }
        val text = ws.read(file)
        // **The same grep as `tree`, on one file.** Line numbers, because the next thing the model
        // does is edit what it found, and an edit needs a place; and no `from`/`lines` window on top
        // of it, because two windows at once is a range that means two things.
        val grep = pattern(args.optString("grep", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        if (grep != null) {
            val hits = text.split("\n").withIndex()
                .filter { (_, line) -> grep.containsMatchIn(line) }
                .take(MAX_MATCHES)
            if (hits.isEmpty()) return success("nothing in ${rel(file)} matches that")
            val body = hits.joinToString("\n") { (index, line) ->
                "${index + 1}: ${line.trim().take(200)}"
            }
            val all = text.split("\n").count { grep.containsMatchIn(it) }
            val note = if (all > hits.size) {
                "\n(showing first ${hits.size} of $all matches; narrow the pattern)"
            } else {
                ""
            }
            return success(body + note)
        }
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
        // **Through the funnel.** A locked file, a hidden name, the record itself or a structural
        // folder is refused by `write`, and the refusal is the whole answer.
        //
        // The context used to be the exception — a file the agent edited under its own rules, with a
        // whole-row rule, a final-newline rule, and a line-by-line comparison to prove the edit was
        // removal. It is not a file any more: the context is a column, and `forget` is the only way
        // to change it, so none of that has to be checked.
        if (!ws.write(file, text)) return refusal("${rel(file)} cannot be changed")
        return commit("Edited ${file.name}", "${rel(file)} changed")
    }

    /**
     * Takes rows out of the context, by id — **the agent's compaction, and the whole of it.**
     *
     * The alternative was editing the context file as text, which needed a rule about whole rows, a
     * rule about final newlines, a line-by-line comparison to prove the edit was removal, and a
     * check that a re-rendered row still said exactly what the file said. None of that is possible
     * here, because no text is carried: [AgentLog.forgetProblem] decides whether the list is legal,
     * and [AgentLog.forget] flips the named rows.
     */
    private fun forget(args: JSONObject): String {
        val ids = mutableSetOf<Int>()
        val raw = args.optJSONArray("ids")
        if (raw != null) {
            for (i in 0 until raw.length()) ids.add(raw.optInt(i, -1))
        } else {
            // A single id is accepted, because a model that forgets one row will write `"ids": 12`
            // sooner or later — and a refusal there would be about the shape of the JSON rather
            // than about the work.
            args.optInt("ids", -1).takeIf { it > 0 }?.let { ids.add(it) }
        }
        ids.removeAll { it <= 0 }
        val problem = AgentLog.forgetProblem(log.rows(), ids)
        if (problem != null) return refusal(problem)
        val gone = log.forget(ids)
        val task = log.contextRows().lastOrNull { it.kind == AgentLog.TOLD }
        val left = if (task == null) "" else " The task is still ${task.id}."
        return success("forgot ${gone} row${if (gone == 1) "" else "s"}.$left")
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
        val into = resolve(args.optString("into", file.parentFile?.let { rel(it) } ?: ""))
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
        return if (result.success) success("cloned into this project's folder") else refusal(result.message)
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
    private fun mayWrite(file: File): String? = when {
        // **The record is not yours to change.** `forget` is the one thing you may do to the
        // context, and it is a column and not a text edit; the evidence itself is the app's, so an
        // agent cannot make a failure disappear from it. The same rule the person is under.
        ws.isRecord(file) ->
            "${rel(file)} is the record of what happened. You may read it and not change it"
        // **Your own instructions are the person's.** Reading the brief is how a turn starts;
        // rewriting it is a different act, and one that would let one turn quietly change what
        // the next turn is told to do. The paragraph above says so and the rule did not: the
        // merged layout made this an ordinary file inside the project, and it stopped refusing.
        rel(file) == AgentPrompt.PROJECT_FILE ->
            "${rel(file)} is the person's instructions to you. You may read it and not change it"
        insideWorktreeOrAgent(file) -> null
        else -> "You may only write inside this project, and ${rel(file)} is outside it"
    }


    /**
     * True when [file] is somewhere in the project the agent may write.
     *
     * **Everything in the project is writable except the record's own folder.** The app's
     * `.gitignore` keeps `agent/` out of the repository, and the record inside it is [mayWrite]'s
     * first refusal. Dot-names were never the agent's and still are not: `.git` is a folder whose
     * contents would destroy the repository, and it is refused here rather than by each tool.
     */
    private fun insideWorktreeOrAgent(file: File): Boolean {
        val path = rel(file)
        if (path.isEmpty()) return true
        val first = path.substringBefore('/')
        return !first.startsWith(".") && first != AgentLog.DIR
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
