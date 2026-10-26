package com.example.notes

import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

/**
 * What the agent can do, and what it is refused.
 *
 * **Every write goes through [Workspace]**, the same funnel the screens use, so a lock, a
 * hidden name and the structural folders are enforced here by construction rather than by this
 * file remembering them. That is the whole reason the lock moved into the funnel before the
 * agent existed: "the agent honours locks" needs no agent code at all.
 *
 * A result's **first line is `0` or `1`, always**, and a result is either whole or a refusal that
 * says it was too big and how to narrow it — never a fragment with a note at the bottom. Both are
 * promises the model is told about, and both are things a silent tool would break: an agent whose
 * tool goes quiet is an agent that either hangs or invents.
 */
class AgentTools(
    private val ws: Workspace,
    private val project: String,
    private val git: ProjectGit,
    private val log: AgentLog,
    private val maxChars: Int = MAX_RESULT,
    /** Where a `background` call runs. Inline by default, so a test acquires no thread. */
    private val bg: Background = Background.inline(),
    /**
     * The operator's limit for a **background** command, in milliseconds; zero is "no limit".
     *
     * Read per call rather than captured here, because the setting can change while an agent is
     * idle and the number that applies is the one in force when somebody asked for the work.
     */
    private val backgroundMs: () -> Long = { 0L },
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
        /** The default result limit, for a caller that has no setting of its own. */
        const val MAX_RESULT = SettingsStore.DEFAULT_MAX_RESULT

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
        val WRITING = setOf("edit", "create", "delete", "move", "restore")

        /**
         * How many calls in a row may change nothing and answer nothing before the loop stops.
         *
         * **Not a turn count.** An idle agent waits for ever by design, so a limit on turns would
         * stop every healthy session; a limit on *unproductive* turns trips in minutes on an agent
         * that is spinning and never on one that is waiting.
         */
        const val IDLE_CALLS = 20

        /**
         * How much of a version's first line a `history` row shows.
         *
         * A row is a signpost, not a reading: it has to fit beside an id, a message and a time and
         * still leave one of twenty rows recognisable at a glance.
         */
        const val EXCERPT = 60

        /**
         * How much of a line a `grep` hit shows.
         *
         * A hit's line is evidence, not a row: it has to hold enough of the line to recognise the
         * place and be short enough that twenty hits fit in one answer. [EXCERPT] is for a version's
         * first line, which is a label; this is for the line that matched, which is the finding.
         */
        const val HIT = 200

        /**
         * The folder a call's result is written into, under `agent/`.
         *
         * **Inside `agent/` and not at the project root**, because a file at the root is a change the
         * next save commits and the next Sync pushes. `agent/` is the app's own directory and is in
         * git's exclude list, so a result file is never mistaken for the person's work.
         *
         * `RESULTS_DIR` and not `LONG_DIR`: there is no `LONG` set any more — every command takes a
         * deadline — and what lands here is any result too big to enter the conversation, not only a
         * slow one's.
         */
        const val RESULTS_DIR = AgentLog.DIR

        /**
         * What each command takes — **and the whole of what it takes.**
         *
         * A key that is not here is refused before the command runs, because the alternative is
         * silent misbehaviour: `read {"lines":40}` used to mean "everything", since `lines` was the
         * old name for a count and the parser had stopped looking at it. A typo that changes the
         * answer without saying so is the worst kind of answer.
         *
         * `ask` and `conclude` are here even though [run] never dispatches them: the loop ends the
         * turn on them, and it asks this same table first, so a `conclude` with a misspelled key is
         * refused rather than turned into an ending with the wrong words.
         */
        val KEYS: Map<String, Set<String>> = mapOf(
            "tree" to setOf("path", "depth", "name", "grep", "from", "to"),
            "read" to setOf("path", "from", "to", "grep", "revision"),
            "edit" to setOf("path", "find", "replace", "from", "to", "at", "text"),
            "forget" to setOf("ids"),
            "create" to setOf("path", "text"),
            "delete" to setOf("path"),
            "move" to setOf("path", "into", "name", "copy"),
            "history" to setOf("path", "grep", "from", "to"),
            "restore" to setOf("path", "revision"),
            "background" to setOf("tool", "args"),
            "reason" to setOf("text"),
            "ask" to setOf("question"),
            "conclude" to setOf("text"),
        )

        /** The keys [args] carries that [tool] does not take, in the order the model wrote them. */
        fun unknownIn(tool: String, args: JSONObject): List<String> {
            val took = KEYS[tool] ?: return emptyList()
            return args.keys().asSequence().filterNot { it in took }.toList()
        }

        /**
         * The answer a wrong key gets: which key, which keys this command does take, and its shape.
         *
         * The example is read from [specs] rather than written again here, so a command's refusal
         * cannot describe a command that no longer exists.
         */
        fun refuseKeys(tool: String, unknown: List<String>): String = "1\n" + keysSaid(tool, unknown)

        /** [refuseKeys] without the status line, for a caller that is a `Result` rather than a result. */
        fun keysSaid(tool: String, unknown: List<String>): String {
            val took = KEYS[tool] ?: return "There is no tool called $tool"
            val keys = if (took.isEmpty()) "no arguments" else "only " + took.sorted().joinToString(", ") { "`$it`" }
            val shape = specs().firstOrNull { it.name == tool }?.parameters?.toString() ?: "{}"
            val renamed = if ("lines" in unknown) {
                " `lines` is not a key any more: `from` and `to` are line numbers, both included."
            } else {
                ""
            }
            return "`$tool` does not take " + unknown.joinToString(", ") { "`$it`" } +
                ". It takes $keys.$renamed\nIts shape: $shape"
        }

        fun specs(): List<ToolSpec> = listOf(
            spec(
                "tree",
                "List the files in a folder of this project. Folders end with a slash. " +
                    "`name` keeps only paths whose own name matches, and may answer with a folder; " +
                    "`grep` keeps only files whose text matches, and shows the matching lines. " +
                    "A pattern is a regular expression, case-insensitive, matched inside one line. " +
                    "`from`/`to` pick a range of the result's lines, which here are paths.",
                "{\"path\":\"where to look, relative to the project\",\"depth\":2," +
                    "\"name\":\"a pattern for file names\",\"grep\":\"a pattern for their text\"," +
                    "\"from\":1,\"to\":200}",
            ),
            spec(
                "read",
                "Read a text file, or one of its versions. Always read before you change one. " +
                "`from` and `to` are line numbers, both included; `from` starts at 1 and `to` " +
                "left out runs to the end. " +
                    "With `grep` only the lines that match are shown, each with its line number, " +
                    "and the pattern is applied inside `from`/`to` rather than to the whole file. " +
                    "A pattern is a regular expression, case-insensitive, matched inside one line.",
                "{\"path\":\"the file, relative to the project\",\"from\":1,\"to\":200," +
                    "\"grep\":\"a pattern to look for\",\"revision\":\"an id from history\"}",
            ),
            spec(
                "edit",
                "Change part of a file. `find`/`replace` replaces text wherever it appears; " +
                    "`from`/`to` replaces that block of lines; `at` inserts after that line (0 puts " +
                    "it at the top); `text` writes the whole file. `replace` may be a `read` call, " +
                    "which copies that call's answer out of another file without it passing " +
                    "through this conversation.",
                "{\"path\":\"the file\",\"find\":\"text to look for\",\"replace\":\"what to " +
                    "put there\"}",
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
            spec("delete", "Delete a file, or a folder and everything in it.", """{"path":"what to delete"}"""),
            spec(
                "move",
                "Move a file into a folder, or rename it inside its own folder. With `copy` the " +
                    "original stays where it is. A name that is already taken is refused.",
                "{\"path\":\"the file\",\"into\":\"the folder\",\"name\":\"the new name\"," +
                    "\"copy\":true}",
            ),
            spec(
                "history",
                "List the versions of a file, newest first: the id each is restored from, and the " +
                    "first line it contained. `from`/`to` pick a range of versions; `grep` keeps " +
                    "only the versions with a matching line, and shows that line instead. A pattern " +
                    "is a regular expression, case-insensitive, matched inside one line.",
                "{\"path\":\"the file\",\"grep\":\"a pattern\",\"from\":1,\"to\":20}",
            ),
            spec(
                "restore",
                "Put an old version of a file back. It becomes a new version, nothing is lost.",
                """{"path":"the file","revision":"the id history gave you"}""",
            ),
            spec(
                "background",
                "Run any command and have its result written to a file instead of into this " +
                    "conversation. The answer comes back at once with the path; read it to see " +
                    "whether the command has finished. Use it for slow work on a big file. A " +
                    "command that changes files will change them while this turn carries on.",
                "{\"tool\":\"read\",\"args\":{\"path\":\"a big file\"}}",
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
     * `ask` and `conclude` are not run here: the loop ends the turn on them, because the turn is
     * what they end. Everything else is answered here, including the refusal.
     *
     * **One `when`, and every command gets the deadline.** There used to be a short list of "slow"
     * commands with a deadline and a second dispatch beside this one for them; a `read` of a huge
     * file and a `tree` of a huge project are as slow as the disk says they are, so no command has
     * the option of outlasting its turn. What each one does with the deadline is its own business:
     * the ones that can ask inside a loop do ([Deadline.check]), and the ones that write once have
     * either finished or not started by the time it passes.
     */
    fun run(call: Call, deadline: Deadline = Deadline.none()): String = try {
        // **The arguments, or a sentence about them.** `JSONObject(...)` throws on anything that is
        // not one — a model that sends `[]`, or a bare string where the object should be — and
        // "that tool could not be run" would leave it guessing at which part of the call was wrong.
        val args = try {
            JSONObject(call.args.ifBlank { "{}" })
        } catch (e: Throwable) {
            return refusal(
                "The arguments must be a JSON object, like {\"path\":\"a file\"}. " +
                    "This call's were: ${call.args.take(160)}",
            )
        }
        // **Before the command, not inside it.** A key a command does not take means the model and
        // the description disagree, and answering as if they did not is how `lines` silently became
        // "everything". Nothing has happened yet at this point, which is the only moment a refusal
        // can leave everything exactly as it was.
        val unknown = unknownIn(call.tool, args)
        if (unknown.isNotEmpty()) return refuseKeys(call.tool, unknown)
        when (call.tool) {
            "tree" -> tree(args, deadline)
            "read" -> read(args, deadline)
            "edit" -> edit(args, deadline)
            "forget" -> forget(args)
            "create" -> create(args, deadline)
            "delete" -> delete(args, deadline)
            "move" -> move(args, deadline)
            "history" -> history(args, deadline)
            "restore" -> restore(args, deadline)
            "background" -> background(call, args)
            "reason" -> reason(args)
            else -> refusal("There is no tool called ${call.tool}")
        }
    } catch (e: StoppedException) {
        e.message ?: Stopped.body(call.tool, deadline)
    } catch (e: Throwable) {
        refusal(Secrets.explain(e, "That tool could not be run"))
    }

    /**
     * Any command, run so that its result lands in a file instead of in the conversation.
     *
     * **The answer comes back before the work starts** — [Background.start] writes the file and then
     * hands the work over — so this never holds a turn open, however long the command takes. Its
     * limit is the operator's *background* number, applied to the work and not to the answer.
     *
     * **The tool name is the argument**: a result belongs to the call that asked for it, and the path
     * is named after that call's own row. The work goes through [run], the same one the foreground
     * uses, so the file's body is the body the agent would have read in the conversation and no
     * command can exist on one path and not the other.
     *
     * **The inner call inherits the outer one's id**, which is what makes the path unique. It used to
     * take an `id` from the arguments, defaulting to `"0"` — so two backgrounded reads both wrote
     * `read-0.txt` and the second answer replaced the first, silently, while the agent read it as the
     * answer to its own question.
     *
     * Refused: the three that end a turn, because there is no result to file, and `background`
     * itself, because a background of a background is a way to lose a result.
     */
    private fun background(call: Call, args: JSONObject): String {
        val tool = args.optString("tool").trim()
        if (tool.isEmpty()) return refusal("Say which command to run in the background")
        if (tool in TERMINATING) {
            return refusal("$tool ends the turn, so there is no result to put in a file")
        }
        if (tool == "background") {
            return refusal("A background command cannot be put in the background")
        }
        // **The command it names is checked now, not when it runs.** Otherwise the wrong key would
        // be found by the work, and the answer would be a file containing a refusal the model does
        // not look at until it thinks the command finished.
        if (tool !in KEYS) return refusal("There is no tool called $tool")
        val innerArgs = innerArgsOf(args)
            .getOrElse { return refusal(it.message ?: "`args` must be a JSON object") }
        val unknown = unknownIn(tool, innerArgs)
        if (unknown.isNotEmpty()) return refuseKeys(tool, unknown)
        val inner = Call(id = call.id, tool = tool, args = innerArgs.toString())
        val dir = projectDir
        return bg.start(dir, inner) { run(inner, Deadline(backgroundMs())) }
    }

    /**
     * The `args` of a `background` call: an object, or a string that holds one.
     *
     * **Both shapes, because models write both.** The string form used to be read as "no arguments
     * at all", so `{"tool":"read","args":"{\\"path\\":\\"milk.txt\\"}"}` ran a `read` of the
     * project's own folder and answered with a sentence about a folder — a command that quietly
     * dropped what it was told, which is the same class of mistake as a wrong key.
     */
    private fun innerArgsOf(args: JSONObject): Result<JSONObject> {
        val raw = args.opt("args")
        return when {
            raw == null || raw === JSONObject.NULL -> Result.success(JSONObject())
            raw is JSONObject -> Result.success(raw)
            raw is String -> try {
                Result.success(JSONObject(raw))
            } catch (e: Throwable) {
                Result.failure(
                    IllegalArgumentException(
                        "`args` must be a JSON object, like {\"path\":\"a file\"}",
                    ),
                )
            }
            else -> Result.failure(IllegalArgumentException("`args` must be a JSON object"))
        }
    }

    private fun tree(args: JSONObject, deadline: Deadline): String {
        val root = resolve(args.optString("path", ""))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        if (!root.exists()) return refusal("There is nothing at " + rel(root))
        if (!root.isDirectory) return refusal("${rel(root)} is a file, not a folder")
        val depth = count(args, "depth", 3)
            .getOrElse { return refusal(it.message ?: "that depth cannot be used") }
            .coerceIn(1, 8)
        val name = pattern(args.optString("name", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        val content = pattern(args.optString("grep", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        if (name == null && content == null) {
            val out = mutableListOf<String>()
            walk(root, depth, out, deadline)
            if (out.isEmpty()) return success("${rel(root)} is empty")
            // **The "lines" here are paths.** A listing of four thousand names has to be narrowable
            // once no cap of its own exists, and `from`/`to` is the same range every other command
            // takes — the description says so, because a range over a path list whose unit a model
            // has to guess is a trap.
            val chosen = slice(out, args).getOrElse { return refusal(it.message ?: "that range cannot be used") }
            if (chosen.isEmpty()) return success("nothing in that range: there are ${out.size} paths")
            return success(chosen.joinToString("\n"))
        }
        // **A search, not a listing.** With `name` the answer is the paths whose own name matches,
        // and a folder can be one of them. With `grep` the answer is files and lines: a folder in the
        // result would be there because something inside it matched, and printing the folder adds a
        // line nobody asked about. What a person (and a model) wants is the path, and for a content
        // match, the line.
        val found = mutableListOf<String>()
        search(root, depth, name, content, found, deadline)
        if (found.isEmpty()) return success("nothing matches in ${rel(root)}")
        // **No cap of its own.** The only limit a result has is the operator's, and a result past it
        // is an error asking for a narrower pattern — not a list of the first N with a note, which a
        // model reads as "here they all are".
        val chosen = slice(found, args).getOrElse { return refusal(it.message ?: "that range cannot be used") }
        if (chosen.isEmpty()) return success("nothing in that range: ${found.size} matches")
        return success(chosen.joinToString("\n"))
    }

    /**
     * The paths under [dir] that match [name] and [content], walking at most [left] levels.
     *
     * [name] is asked of each entry's **own name** — one short string, so a folder can match it —
     * and [content] is asked of each **line** of every text file, so one search answers "where is
     * this said" rather than "which file says it". The line test is the same one `read` uses, on
     * the same text, which is what makes the two commands agree.
     */
    private fun search(
        dir: File,
        left: Int,
        name: Regex?,
        content: Regex?,
        out: MutableList<String>,
        deadline: Deadline,
    ) {
        for (entry in ws.list(dir)) {
            deadline.check("tree")
            // **A link is followed only while it stays in the project.** The rule `resolve` enforces
            // for the path a model wrote is the same rule here, for every entry a walk meets: a link
            // inside the project can point anywhere, and a search that followed one would read files
            // the instructions promise it cannot see.
            if (!withinProject(entry.file)) continue
            if (entry.isFolder) {
                // **A folder is named, not read.** With `name` alone a folder can be the answer —
                // `{"name":"agent"}` used to answer nothing, which reads as "there is no such
                // folder" when there is. With `grep` the answer is files only: a folder would be
                // there because something in it matched, and it adds a line nobody asked about.
                if (content == null && name != null && hit(entry.name, name, deadline, "tree") != null) {
                    out.add(rel(entry.file) + "/")
                }
                if (left > 1) search(entry.file, left - 1, name, content, out, deadline)
                continue
            }
            if (name != null && hit(entry.name, name, deadline, "tree") == null) continue
            if (content == null) {
                out.add(rel(entry.file))
                continue
            }
            if (ws.kindOf(entry.file) != EntryKind.TEXT) continue
            // **One read, in chunks, and the pattern asked per line** — the same shape `read` uses,
            // so the two commands cannot disagree about whether a file matches. There used to be a
            // whole-file test first, and it is what made `^milk` miss a file whose second line was
            // `milk`: an anchor matches at the start of what it is given, and it was given the file.
            val text = readText(entry.file, deadline, "tree")
            for ((index, line) in text.split("\n").withIndex()) {
                val at = hit(line, content, deadline, "tree") ?: continue
                out.add("${rel(entry.file)}:${index + 1}: ${excerpt(line, at, HIT)}")
            }
        }
    }

    /** True while [file] is inside the project once the phone's own links are followed. */
    private fun withinProject(file: File): Boolean = try {
        val base = projectDir.canonicalFile
        val target = file.canonicalFile
        target == base || target.path.startsWith(base.path + File.separator)
    } catch (e: Exception) {
        false
    }

    /**
     * A whole number from the model, or a refusal that names it.
     *
     * **Read from the value, never through `optInt`.** The phone's `org.json` answers the default
     * where the JVM's casts and throws, so `{"from":"2"}` meant "from 1" on a phone and "that tool
     * could not be run" in a test — one call, two behaviours, and no way to see either from the
     * other side. Reading the value makes both say the same thing, and it accepts a number written
     * as a string, which models do.
     */
    private fun count(args: JSONObject, key: String, fallback: Int): Result<Int> {
        if (!args.has(key) || args.isNull(key)) return Result.success(fallback)
        val raw = args.opt(key)
        val number = whole(raw)
            ?: return Result.failure(
                IllegalArgumentException(
                    "`$key` is a whole number, and ${quoted(raw)} is not one",
                ),
            )
        return Result.success(number)
    }

    /** [value] as a whole number, or null when it is not one — a number, or a string holding one. */
    private fun whole(value: Any?): Int? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (number != Math.floor(number)) return null
        if (number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    /** A value from the arguments, short enough to quote back inside a sentence. */
    private fun quoted(value: Any?): String = "\"" + value.toString().take(30) + "\""

    /**
     * A pattern from the model, or null when it gave none — **and the one meaning every command
     * gives it.**
     *
     * **Per line, always.** `tree`, `read` and `history` ask the same question of the same text, so
     * a pattern that finds a line in one finds that line in the others. It used to have two
     * meanings: `read` matched line by line, while `tree` and `history` first matched the whole
     * file and only then the lines. Anchors were the difference — `^milk` matches only at the very
     * start of the text it is given, so a file whose *second* line was `milk` was skipped by `tree`
     * and found by `read`, and the model had no way to know which answer it would get.
     *
     * **Case-insensitive in every script.** `CASE_INSENSITIVE` on its own folds US-ASCII only, so
     * `Kraków` and `kraków` were two different words; `UNICODE_CASE` is what makes "ignore case"
     * mean what it says for a name with an accent in it.
     *
     * A pattern holding a line break is refused, because no line contains one: it would answer
     * "nothing matches" about a file that plainly holds the text, and the model would go looking
     * for a mistake in its path. `from`/`to` is how a block of lines is read.
     *
     * **A refusal, not a crash.** A model writing `[` is making a mistake about a regular
     * expression, and the answer it needs is that sentence — not an exception turned into "that
     * tool could not be run", which says nothing about what to fix.
     */
    private fun pattern(raw: String): Result<Regex?> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.success(null)
        if (text.any { it == '\n' || it == '\r' }) {
            return Result.failure<Regex?>(
                IllegalArgumentException(
                    "it holds a line break, and a pattern looks inside one line. " +
                        "Use `from`/`to` for a block of lines, or search for one of its lines",
                ),
            )
        }
        return try {
            Result.success(Regex(text, setOf(RegexOption.IGNORE_CASE, RegexOption.UNICODE_CASE)))
        } catch (e: Throwable) {
            Result.failure<Regex?>(IllegalArgumentException("that is not a regular expression"))
        }
    }

    /**
     * The text a matcher walks, with the clock in it.
     *
     * **A pattern is the only input to a command that can take unbounded time in one call.** Walking
     * a folder asks the deadline per entry and a read asks it per chunk, but `Regex.find` against one
     * line is a single call: `(a+)+$` against forty `a`s is more work than the phone will ever
     * finish, and no deadline *outside* the call can stop it. So the text is handed over through
     * this, which asks the deadline every few thousand characters — the engine reaches the input
     * through `charAt`, so the question is asked *inside* the match, and the answer is the same stop
     * sentence every other command gives.
     *
     * It is a view of the text and not a copy, so a match is still asked about the file's own
     * characters.
     */
    private class Timed(
        private val text: CharSequence,
        private val deadline: Deadline,
        private val tool: String,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            // Every 4096 characters: often enough that a runaway pattern is stopped in milliseconds,
            // rare enough that the count costs nothing beside the matching itself.
            if ((++reads and 4095) == 0) deadline.check(tool)
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            Timed(text.subSequence(startIndex, endIndex), deadline, tool)

        override fun toString(): String = text.toString()
    }

    /** Where [pattern] first matches [line] — the one test every command asks, with the clock on. */
    private fun hit(line: String, pattern: Regex, deadline: Deadline, tool: String): IntRange? =
        pattern.find(Timed(line, deadline, tool))?.range

    /** The first line of [text] that [pattern] matches, and where in the line it matched. */
    private fun firstHit(
        text: String,
        pattern: Regex,
        deadline: Deadline,
        tool: String,
    ): Pair<String, IntRange>? {
        for (line in text.split("\n")) {
            val at = hit(line, pattern, deadline, tool) ?: continue
            return line to at
        }
        return null
    }

    /**
     * The part of [line] a hit shows: a window around the match, with a mark where it was cut.
     *
     * **The window is placed around the match, not at the start of the line.** A minified line can
     * be a hundred thousand characters; the answer is a path, a line number and enough text to
     * recognise the place, and a hit reported at character 40 000 beside the first two hundred
     * characters would be an answer that does not contain the thing it answers about.
     */
    private fun excerpt(line: String, at: IntRange, width: Int): String {
        val start = if (line.length <= width) {
            0
        } else {
            (at.first - width / 4).coerceIn(0, line.length - width)
        }
        val end = (start + width).coerceAtMost(line.length)
        val window = line.substring(start, end).filterNot { it < ' ' && it != '\t' }.trim()
        if (window.isEmpty()) return "\u2026"
        return (if (start > 0) "\u2026" else "") + window + (if (end < line.length) "\u2026" else "")
    }

    private fun walk(dir: File, left: Int, out: MutableList<String>, deadline: Deadline) {
        val entries = ws.list(dir)
        for (entry in entries) {
            deadline.check("tree")
            if (!withinProject(entry.file)) continue
            out.add(rel(entry.file) + if (entry.isFolder) "/" else "")
            if (entry.isFolder && left > 1) walk(entry.file, left - 1, out, deadline)
        }
    }

    private fun read(args: JSONObject, deadline: Deadline): String =
        readInto(args, deadline).fold(
            onSuccess = { success(it) },
            onFailure = { refusal(it.message ?: "that file cannot be read") },
        )

    /**
     * The text a `read` selects — **the body with no `0` or `1` on the front.**
     *
     * The prefix belongs to a result that enters the conversation, and one caller is not a result:
     * `edit`'s `replace` may be a `read` call whose text goes straight into a file. That is also why
     * nothing here is measured against the operator's limit — [read] puts its answer through
     * [trim], and a replacement never does.
     */
    private fun readInto(args: JSONObject, deadline: Deadline): Result<String> {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "that path cannot be read")) }
        if (!file.exists()) {
            // **The one name an older instruction set still points at.** The record used to be two
            // files; this build folds the second into the first. An agent holding instructions from
            // before that (a prompt the person edited, so the app would not replace it) will ask for
            // `agent/context.log` — and "there is no file" would leave it guessing.
            if (file.name == AgentLog.CONTEXT) {
                return Result.failure(
                    IllegalArgumentException(
                        "agent/${AgentLog.CONTEXT} was folded into agent/${AgentLog.SESSION}. " +
                            "Read that; forget takes rows out of your context.",
                    ),
                )
            }
            return Result.failure(IllegalArgumentException("There is no file at ${rel(file)}"))
        }
        if (file.isDirectory) {
            return Result.failure(IllegalArgumentException("${rel(file)} is a folder; use tree"))
        }
        if (ws.kindOf(file) != EntryKind.TEXT) {
            return Result.failure(
                IllegalArgumentException("${rel(file)} is not a text file, so there is nothing to show you"),
            )
        }
        // **A version, when one is named.** `history` finds the id and `restore` puts it back; this
        // is the step between them, and without it the middle of a version's life could only be
        // guessed at by restoring it. The bytes come from the same function `restore` uses, so a
        // revision is read exactly as it would be written back.
        val revision = args.optString("revision").trim()
        val text = if (revision.isEmpty()) {
            readText(file, deadline)
        } else {
            val bytes = git.bytesAt(project, rel(file), revision)
                ?: return Result.failure(
                    IllegalArgumentException("There is no version $revision of ${rel(file)}"),
                )
            String(bytes, Charsets.UTF_8)
        }
        val grep = pattern(args.optString("grep", ""))
            .getOrElse { return Result.failure(IllegalArgumentException("That is not a pattern: ${it.message}")) }
        val lines = text.split("\n")
        val chosen = slice(lines, args)
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "that range cannot be used")) }
        val from = count(args, "from", 1)
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "that range cannot be used")) }
            .coerceAtLeast(1)
        if (chosen.isEmpty()) {
            return Result.success("nothing from line $from: the file has ${lines.size} lines")
        }
        // **The same grep as `tree`, on one file, inside the range if there is one.** Each hit comes
        // with its real line number, because the next thing the model does with a hit is change it
        // and an edit needs a place — and the numbers are the file's, not the selection's, so a
        // number read in one call means the same line in the next.
        if (grep != null) {
            val found = chosen.withIndex().mapNotNull { (offset, line) ->
                val at = hit(line, grep, deadline, "read") ?: return@mapNotNull null
                "${from + offset}: ${excerpt(line, at, HIT)}"
            }
            if (found.isEmpty()) {
                return Result.success("nothing in ${rel(file)} matches that")
            }
            return Result.success(found.joinToString("\n"))
        }
        return Result.success(chosen.joinToString("\n"))
    }

    /**
     * The part of [lines] that the arguments select — `from` and `to`, both inclusive, 1-based.
     *
     * **One parser for `read`, `tree` and `history`,** so a range means the same thing in every
     * command. `to` absent or zero is the end of the list. The answer is the selection, not a note
     * about it: what is over the operator's limit is an error, and what is inside it is whole.
     *
     * A range that ends before it starts is a refusal — a model that wrote the two the wrong way
     * round is asking a question nobody can answer, and an empty answer would look like an empty
     * file. Everything else out of bounds is simply empty, which each caller words for itself.
     */
    private fun <T> slice(lines: List<T>, args: JSONObject): Result<List<T>> {
        val from = count(args, "from", 1)
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "that range cannot be used")) }
            .coerceAtLeast(1)
        val to = count(args, "to", 0)
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "that range cannot be used")) }
        if (to > 0 && to < from) {
            return Result.failure(
                IllegalArgumentException(
                    "That range ends at line $to, before it starts at line $from. " +
                        "`from` is the first line and `to` is the last.",
                ),
            )
        }
        val end = if (to <= 0) lines.size else to.coerceAtMost(lines.size)
        if (from > end) return Result.success(emptyList())
        return Result.success(lines.subList(from - 1, end))
    }

    private fun edit(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be changed") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.isFile) return refusal("There is no file at ${rel(file)}")
        // **Exactly one way to say what changes.** Two selectors at once is two edits in one call,
        // and a call that means two things is a call whose result the model cannot check. The
        // refusal names all four shapes, because the model that sent two is guessing at the shape.
        val hasText = args.has("text")
        val find = args.optString("find", "")
        val hasFind = find.isNotEmpty()
        val hasRange = args.has("from") || args.has("to")
        val hasAt = args.has("at")
        val given = listOf(hasText, hasFind, hasRange, hasAt).count { it }
        if (given == 0) {
            return refusal(
                "Say what to change: `find` and `replace` for text, `from`/`to` for a block of " +
                    "lines, `at` to insert a line, or `text` to write the whole file",
            )
        }
        if (given > 1) {
            return refusal(
                "Give one of `text`, `find`, `from`/`to` or `at`, not two at once — " +
                    "a call that means two edits cannot be checked",
            )
        }
        val before = readText(file, deadline, "edit")
        val lines = before.split("\n")
        val text = when {
            hasText -> args.optString("text")
            hasFind -> {
                if (!before.contains(find)) return refusal("That text is not in ${rel(file)}")
                val put = replacementText(args, deadline).getOrElse { return refusal(it.message ?: "that replacement cannot be read") }
                before.replace(find, put)
            }
            hasRange -> {
                val from = count(args, "from", 1)
                    .getOrElse { return refusal(it.message ?: "that range cannot be used") }
                    .coerceAtLeast(1)
                val asked = count(args, "to", 0)
                    .getOrElse { return refusal(it.message ?: "that range cannot be used") }
                if (asked > 0 && asked < from) {
                    return refusal(
                        "That range ends at line $asked, before it starts at line $from. " +
                            "`from` is the first line and `to` is the last.",
                    )
                }
                val to = if (asked <= 0) lines.size else asked.coerceAtMost(lines.size)
                if (from > to) {
                    return refusal("There is no line $from: the file has ${lines.size} lines")
                }
                val put = replacementText(args, deadline).getOrElse { return refusal(it.message ?: "that replacement cannot be read") }
                (lines.take(from - 1) + put.split("\n") + lines.drop(to)).joinToString("\n")
            }
            else -> {
                // **`at` is a place between lines, which a range cannot say.** `from:11,to:10` would
                // be an empty range that happens to mean "here", and a model that meant "here" would
                // write that exactly once before it got it wrong. Zero prepends; the number of lines
                // appends.
                val at = count(args, "at", -1)
                    .getOrElse { return refusal(it.message ?: "that place cannot be used") }
                if (at < 0 || at > lines.size) {
                    return refusal(
                        "`at` is the line to insert after, from 0 (the top) to ${lines.size} " +
                            "(the end); $at is not one of them",
                    )
                }
                val put = replacementText(args, deadline).getOrElse { return refusal(it.message ?: "that replacement cannot be read") }
                (lines.take(at) + put.split("\n") + lines.drop(at)).joinToString("\n")
            }
        }
        if (text == before) return success("${rel(file)} already said that, so nothing changed")
        // **Asked before the one write, because the write is atomic.** A `write` either lands whole
        // or not at all, so the question to ask is only whether there is still time to start it.
        deadline.check("edit")
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
     * What `replace` says: text, or the body of a `read` call.
     *
     * **Only `read` is allowed there.** A replacement that could name any tool would turn one call
     * into a small program with an order nobody wrote down; `read` is the one tool whose whole job is
     * to produce text, and it takes the same `from`/`to`/`grep` arguments it takes anywhere else —
     * applied to the **source** file, which is the unambiguous direction.
     *
     * **The nested read is not measured against the result limit**, because its text goes into a
     * file and never through the conversation. That is the whole point of the shape: a paste of two
     * hundred kilobytes is exactly the case a tool must not refuse for being large.
     */
    private fun replacementText(args: JSONObject, deadline: Deadline): Result<String> {
        val call = args.optJSONObject("replace") ?: return Result.success(args.optString("replace"))
        val tool = call.optString("tool").trim()
        if (tool != "read") {
            return Result.failure(
                IllegalArgumentException(
                    "A replacement is text or a read call, and $tool is neither",
                ),
            )
        }
        val inner = call.optJSONObject("args") ?: JSONObject()
        // **The nested read is checked like a real one.** It is not dispatched through [run] — its
        // text goes into a file, not into the conversation — so without this the one place a model
        // could send a key nobody reads would be the one place a large paste is built from.
        val unknown = unknownIn("read", inner)
        if (unknown.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(keysSaid("read", unknown)))
        }
        return readInto(inner, deadline)
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
        val ids = idsIn(args).getOrElse { return refusal(it.message ?: "those ids cannot be used") }
        val problem = AgentLog.forgetProblem(log.rows(), ids)
        if (problem != null) return refusal(problem)
        val gone = log.forget(ids)
        val task = log.contextRows().lastOrNull { it.kind == AgentLog.TOLD }
        val left = if (task == null) "" else " The task is still ${task.id}."
        return success("forgot ${gone} row${if (gone == 1) "" else "s"}.$left")
    }

    /**
     * The row ids a `forget` call names: a list, one number, or numbers written as one string.
     *
     * **Three shapes, because a model writes all three.** `{"ids":[12,13]}` is the description's,
     * `{"ids":12}` is what a model that forgets one row writes sooner or later, and
     * `{"ids":"12,13"}` is what one writes when it is thinking of a sentence. A refusal for the
     * last two would be about the shape of the JSON rather than about the work.
     *
     * Every number is read from the value: `optInt` answers the default on the phone and casts on
     * the JVM, so a quoted id would be silently ignored on one and a crash on the other.
     */
    private fun idsIn(args: JSONObject): Result<Set<Int>> {
        val raw = args.opt("ids")
        val out = mutableSetOf<Int>()

        fun take(value: Any?) {
            val id = whole(value)
                ?: throw IllegalArgumentException(
                    "`ids` are row numbers, and ${quoted(value)} is not one",
                )
            out.add(id)
        }

        try {
            when {
                raw == null || raw === JSONObject.NULL -> return Result.success(emptySet())
                raw is org.json.JSONArray -> for (i in 0 until raw.length()) take(raw.opt(i))
                raw is String && (raw.contains(',') || raw.contains(' ')) ->
                    for (piece in raw.split(',', ' ')) if (piece.isNotBlank()) take(piece)
                else -> take(raw)
            }
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        out.removeAll { it <= 0 }
        return Result.success(out)
    }

    private fun create(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be made") }
        deadline.check("create")
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

    private fun delete(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be deleted") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.exists()) return refusal("There is nothing at ${rel(file)}")
        // **A folder is the one deletion that can stop half way.** Everything else removes one entry
        // and is done; a tree removal walks, so the deadline is asked between entries, and the answer
        // says what is still there rather than claiming nothing happened.
        val removed = ws.delete(file, deadline)
        return removed.fold(
            onSuccess = { commit("Deleted ${file.name}", "deleted ${rel(file)}") },
            onFailure = { refusal(it.message ?: "it could not be deleted") },
        )
    }

    private fun move(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be moved") }
        mayWrite(file)?.let { return refusal(it) }
        if (!file.exists()) return refusal("There is nothing at ${rel(file)}")
        deadline.check("move")
        val into = resolve(args.optString("into", file.parentFile?.let { rel(it) } ?: ""))
            .getOrElse { return refusal(it.message ?: "that folder cannot be used") }
        if (!into.isDirectory) return refusal("${rel(into)} is not a folder")
        mayWrite(File(into, file.name))?.let { return refusal(it) }
        val name = args.optString("name", "").trim()
        // **`copy` is the one difference between the two acts.** Everything else — the destination
        // checks, the refusal of a taken name, a folder into itself — is the same question, and it
        // is asked in one place per act rather than in a second tool that would drift from this one.
        // A copy may read a locked source, because it does not touch it.
        val copy = args.optBoolean("copy", false)
        if (copy && name.isNotEmpty()) {
            // **Two calls, not one with two meanings.** Copying and renaming in one act can fail
            // after the copy exists, and then the answer has to describe half of each. The model
            // can copy, then move the copy — and each answer it gets is about one thing.
            return refusal(
                "A copy keeps its name. Copy it, then move the copy to the name you want",
            )
        }
        val done = if (copy) {
            ws.copy(file, into, deadline)
        } else if (name.isEmpty()) {
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
        return done.fold(
            onSuccess = { made ->
                if (copy) {
                    commit("Copied ${made.name}", "copied ${rel(file)} into ${rel(into)}/")
                } else {
                    commit("Moved ${file.name}", "moved ${rel(file)}")
                }
            },
            onFailure = { refusal(it.message ?: "it could not be moved") },
        )
    }

    private fun history(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be read") }
        deadline.check("history")
        val path = rel(file)
        val grep = pattern(args.optString("grep", ""))
            .getOrElse { return refusal("That is not a pattern: ${it.message}") }
        val versions = git.versions(project, path, deadline)
        if (versions.isEmpty()) return success("$path has no versions yet")
        // **The range picks versions, then the pattern picks among those.** The same order as
        // everywhere else, and it has to be this way round: a version is one line of this result, so
        // a range over this result is a range over versions — never over the file's own lines.
        val chosen = slice(versions, args).getOrElse { return refusal(it.message ?: "that range cannot be used") }
        if (chosen.isEmpty()) return success("nothing in that range: ${versions.size} versions")
        // **A version is shown when its own text matches.** [versionsOf] loads every version's bytes
        // anyway, so asking is free — and it is the question a model actually has ("when did it say
        // this?"), which a commit message cannot answer.
        val rows = mutableListOf<String>()
        for ((commit, text) in chosen) {
            if (grep == null) {
                rows.add(versionRow(commit, text))
                continue
            }
            // **The row shows the line that matched.** A saved version is identified by its id, its
            // message and its time; the excerpt is what makes twenty saves of one file readable, and
            // the first line of the file is not the answer to "which save said this".
            val found = if (text == null) null else firstHit(text, grep, deadline, "history")
            if (found != null) rows.add(versionRow(commit, text, found))
        }
        if (rows.isEmpty()) return success("no version of $path matches that")
        return success(rows.joinToString("\n"))
    }

    /**
     * One version as a line: its id, its message, when it was made, and what the file said.
     *
     * **The excerpt is what makes a list of saves readable.** A commit message describes the change,
     * and Save's is one word, so twenty versions of one file read as twenty identical rows. The
     * file's own first non-blank line is the only thing that tells them apart, and it is already in
     * hand. An empty version says so, and so does one that is not text — a picture's first line is
     * not a sentence.
     */
    private fun versionRow(
        commit: GitService.CommitInfo,
        text: String?,
        found: Pair<String, IntRange>? = null,
    ): String {
        val said = when {
            text == null -> "not a text file"
            found != null -> excerpt(found.first, found.second, EXCERPT)
            else -> {
                val line = text
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.filterNot { it < ' ' && it != '\t' }
                    ?.trim()
                when {
                    line.isNullOrEmpty() -> "empty"
                    line.length > EXCERPT -> line.take(EXCERPT) + "\u2026"
                    else -> line
                }
            }
        }
        return "${commit.shortId}  ${commit.message}  ${stamp(commit.timestamp)}  $said"
    }

    private fun restore(args: JSONObject, deadline: Deadline): String {
        val file = resolveFile(args.optString("path"))
            .getOrElse { return refusal(it.message ?: "that path cannot be restored") }
        val revision = args.optString("revision").trim()
        mayWrite(file)?.let { return refusal(it) }
        if (revision.length < 7) return refusal("Give the version id that history showed you")
        if (ws.isLocked(file)) return refusal("${rel(file)} is locked, so it is read-only")
        val restored = git.restore(project, rel(file), revision, deadline)
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

    private fun success(body: String): String = "0\n$body"

    private fun refusal(said: String): String = "1\n$said"

    /**
     * What the conversation gets to see of one call's answer.
     *
     * **A result larger than the operator's limit is not cut short and it is not refused: it is
     * filed.** The body already exists by the time the limit is asked about, so writing it whole to
     * `agent/background_results/<tool>-<callId>.txt` and answering with that path costs nothing and
     * loses nothing — and it is the same file, the same sentence and the same protocol as
     * `background`, so the agent has one rule to learn instead of two.
     *
     * Two earlier shapes were wrong. Cutting the body is how a model confidently edits the half it
     * was shown. Refusing with "too big, narrow it" tells a model what it already knows and hands it
     * nothing — and a refusal measured by the same limit could be longer than the limit, so the
     * complaint about the size replaced the cause.
     *
     * **A refusal keeps its `1`** when it is filed: the status is the call's, not the filing's.
     *
     * Two results are deliberately not measured, and neither passes through here: one going to a file
     * (`background`'s work), and one going into another file (`edit`'s replacement).
     */
    fun forConversation(call: Call, body: String): String {
        if (body.length <= maxChars) return body
        val status = if (body.startsWith("1\n")) "1" else "0"
        val text = body.substringAfter('\n')
        val path = bg.keep(projectDir, call, text)
            ?: return "$status\nThat result was ${text.length} characters and the limit is $maxChars, " +
                "and it could not be written to a file either. Narrow it instead: " +
                "{\"from\":1,\"to\":200} or {\"grep\":\"pattern\"}."
        val asked = "{\"path\":\"$path\",\"from\":1,\"to\":200}"
        return "$status\nThat result was ${text.length} characters and the limit is $maxChars, so it " +
            "was written whole to $path. Read it in ranges: $asked, or " +
            "{\"path\":\"$path\",\"grep\":\"a pattern\"}."
    }

    /**
     * A file's whole text, in chunks, so that the deadline can be asked part of the way through.
     *
     * `File.readText()` is one call nobody can interrupt: a phone reading a log of a hundred
     * megabytes inside it would hold the turn for as long as the disk took, which is exactly what
     * every foreground command is now forbidden to do. An [InputStreamReader] over a char buffer is
     * the only shape that decodes multi-byte characters correctly across a chunk boundary.
     *
     * [tool] is the name the stopped sentence carries, because the caller is not always `read`.
     *
     * A file that cannot be read is the empty string, which is what `Workspace.read` has always
     * answered — the checks above have already refused the cases that deserve a sentence.
     */
    private fun readText(file: File, deadline: Deadline, tool: String = "read"): String {
        val out = StringBuilder()
        try {
            InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
                val buffer = CharArray(64 * 1024)
                while (true) {
                    deadline.check(tool)
                    val got = reader.read(buffer)
                    if (got < 0) break
                    out.append(buffer, 0, got)
                }
            }
        } catch (e: StoppedException) {
            throw e
        } catch (e: Throwable) {
            return ""
        }
        return out.toString()
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
    /**
     * A path that has to name something **inside** this project — not the project itself.
     *
     * `""` is a legitimate path for `tree`: the project's own folder is a folder to list. It is a
     * dangerous one for every command that changes something, and it was accepted by all of them:
     * `delete {"path":""}` resolved to the project folder and removed it, `.git` and the record
     * with it, then answered with a refusal about the history it could not write. `.` did the same.
     * A command that needs a file says so before it does anything.
     */
    private fun resolveFile(raw: String): Result<File> {
        val file = resolve(raw).getOrElse { return Result.failure(it) }
        if (rel(file).isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "That is the project's own folder, and this command needs a file inside it",
                ),
            )
        }
        return Result.success(file)
    }

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
    private fun mayWrite(file: File): String? {
        val path = rel(file)
        // **The record is not yours to change.** `forget` is the one thing you may do to the
        // context, and it is a column and not a text edit; the evidence itself is the app's, so an
        // agent cannot make a failure disappear from it. The same rule the person is under.
        if (ws.isRecord(file)) {
            return "$path is the record of what happened. You may read it and not change it"
        }
        // **Your own instructions are the person's.** Reading the brief is how a turn starts;
        // rewriting it is a different act, and one that would let one turn quietly change what
        // the next turn is told to do. The paragraph above says so and the rule did not: the
        // merged layout made this an ordinary file inside the project, and it stopped refusing.
        if (path == AgentPrompt.PROJECT_FILE) {
            return "$path is the person's instructions to you. You may read it and not change it"
        }
        if (insideWorktreeOrAgent(file)) return null
        // **Why, and not "outside this project".** That sentence was given for a path that is
        // inside the project, so a model read it as "my path is wrong" and tried another spelling
        // of the same path. What it needs is the rule it broke.
        return if (path.substringBefore('/') == AgentLog.DIR) {
            "${AgentLog.DIR}/ is the app's own folder. Read it; `forget` takes rows out of your context"
        } else {
            "A name that starts with a dot belongs to the app, and $path has one"
        }
    }


    /**
     * True when [file] is somewhere in the project the agent may write.
     *
     * **Everything in the project is writable except the record's own folder.** The app's
     * git's exclude list keeps `agent/` out of the repository, and the record inside it is [mayWrite]'s
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
