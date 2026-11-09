package com.example.notes

import org.json.JSONObject
import java.io.File

/**
 * **The folder of request schemas** — `<account>/shared/schemas/`, one [Schema] per file.
 *
 * A folder rather than a form, because a request is a document and a person can copy one. That is the
 * operator's design, 2026-11-09: *"a button that then could open a hidden folder with llm request schema
 * templates as files that user can open and edit"* — so there is no editor for requests in the app at
 * all. The browser and the editor this app already has are the editor, and a schema is a file like any
 * other: **the same screen reads it, the same Save writes it, and the same "New file" makes one.**
 *
 * **What it does not get is a history**, and that is worth saying because it looks like it should: the
 * version browser is `GitService.versionsOf(repo, path)`, and `.muffy/` is outside every repository. An
 * edit to a schema is undone by editing it back, or by copying the file before changing it — the same
 * rule `model_conf.json` lives under. Putting the folder inside a repository to inherit a history would
 * put request shapes into commits, which is the thing the hidden folder exists to avoid.
 *
 * ### Why `shared/`, and why it is locked
 *
 * The operator, 2026-11-09: *"we can actually rename system into shared, and make it an ordinary folder
 * with some system locked files … and then we can just keep llm request templates in shared as a system
 * locked subfolder."*
 *
 * `shared/` is an **ordinary folder of the account** — not a project, not classified, no path rule of its
 * own — and it is **system locked**, like `.muffy/` and the agent's records, because the app's
 * functionality depends on it being where it is. `shared/schemas/` is locked the same way, and
 * `example.json` inside it is locked as a *file*, so it can be read and copied and never edited.
 *
 * **A folder lock is not a subtree lock** (`Workspace.isAgentLocked`): it stops the folder being renamed
 * or deleted, and its contents are the person's — so `chat.json`, beside the locked example, is a file
 * both the person and an agent may edit. That is the whole of the protection, and it uses the lock stack
 * the app already had.
 *
 * ### Why the shipped ones exist
 *
 * A fresh account must be able to run an agent before it has written anything, and an empty folder
 * would be a feature that says "make a request schema" to somebody who has never seen one. So three are
 * written the first time the folder is read **and only when it holds no schema at all** — a person who
 * deleted them all means it, and one of the three coming back would be the app overruling them.
 */
object Schemas {

    /**
     * The folder, as a path from the account root — the same path the browser shows.
     *
     * **Derived from the folder's own name rather than typed twice.** It was a literal, and a literal is
     * how a rename leaves one caller pointing at a folder nothing creates: `shared` is
     * [Workspace.SYSTEM_PROJECT], and the subfolder is this arc's new name.
     */
    val DIR = Workspace.SYSTEM_PROJECT + "/schemas"

    /**
     * The exemplar: **locked as a file, so it can be copied and never edited.**
     *
     * The operator: *"one system locked example template from which other templates can then be copied
     * so that there is even less special machinery"*. It is the only template the app writes for itself,
     * which is why it is the only one that must not be changed: everything else is a copy somebody made
     * deliberately, and a copy is theirs.
     */
    const val EXAMPLE = "example.json"

    /** The suffix every schema has. A file without it is not offered, so a note can live here too. */
    const val SUFFIX = ".json"

    /** Where the worktree pointers live, under `.muffy/` — the app's own state, beside `agents.json`. */
    const val POINTERS = "schemas.json"

    fun dir(accountDir: File): File = File(accountDir, DIR)

    /**
     * The schemas in the folder, by name, **sorted**, so the pickers read in one order.
     *
     * Read on every call rather than cached: a person renames or deletes one in the editor, and a list
     * built once would keep offering a file that is no longer there.
     */
    fun names(accountDir: File): List<String> {
        val folder = dir(accountDir)
        return folder.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.startsWith(".") }
            .map { it.name }
            .sorted()
    }

    /** One schema by file name, or the sentence saying why it cannot be read. */
    fun read(accountDir: File, name: String): Result<Schema> {
        val file = File(dir(accountDir), name)
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("There is no schema called \"$name\"."))
        }
        return try {
            Schema.parse(file.readText()).recoverCatching { throw IllegalArgumentException("$name: ${it.message}") }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("$name could not be read"))
        }
    }

    /** Writes [schema] as [name]. The name is checked first, because it becomes a file. */
    fun write(accountDir: File, name: String, schema: Schema): Result<Unit> {
        nameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        return try {
            val folder = dir(accountDir)
            folder.mkdirs()
            File(folder, name).writeText(schema.text())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("$name could not be written"))
        }
    }

    /** Removes one. A missing file is not an error: the state asked for is the state reached. */
    fun remove(accountDir: File, name: String): Boolean = File(dir(accountDir), name).delete()

    /**
     * Why [name] cannot be a schema's file name, or null when it can.
     *
     * **A name is a file name, and the checks are about the folder rather than about taste.** A slash
     * would write into another folder, a leading dot would make the file invisible to [names], and a
     * name that does not end in [SUFFIX] would never be offered again — each of those is a silent
     * disappearance, so each is refused in words.
     */
    fun nameProblem(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "A schema needs a name."
        if (clean.contains('/') || clean.contains('\\')) {
            return "A schema's name cannot contain a slash — it is a file in one folder."
        }
        if (clean.startsWith(".")) return "A schema's name cannot start with a dot."
        if (!clean.endsWith(SUFFIX)) return "A schema's name must end in $SUFFIX."
        if (clean.length > 64) return "That name is too long; keep it under 64 characters."
        return null
    }

    /**
     * **Which template a worktree runs** — `chat.json`, say, keyed `project/branch`.
     *
     * Stored beside `agents.json` in `.muffy/`, and **not inside the session**: a template is chosen when
     * the branch is made, which is before any agent has ever run, so a session record would be the wrong
     * home for it — and forgetting a session would silently forget its request.
     *
     * One file, read and written whole, keyed exactly as [AgentSession.keyOf] keys a session: `main`
     * keeps the bare project name, a branch is `project/branch`.
     */
    fun pointerFile(accountDir: File): File =
        File(File(accountDir, Workspace.STATE_DIR), POINTERS)

    /** The template [project] on [branch] runs, or null when it has not chosen one. */
    fun pointer(accountDir: File, project: String, branch: String): String? {
        val file = pointerFile(accountDir)
        if (!file.isFile) return null
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // **A broken file is "nobody has chosen"**, not a crash on every tick: the sessions beside it
            // are the app's record of what is running, and losing a template pointer must not take that
            // with it. The refusal the agent gets names the picker, so the person can set it again.
            return null
        }
        return root.optString(AgentSession.keyOf(project, branch)).trim().takeIf { it.isNotEmpty() }
    }

    /** Points [project] on [branch] at [name], or forgets the pointer when [name] is blank. */
    fun setPointer(accountDir: File, project: String, branch: String, name: String) {
        val file = pointerFile(accountDir)
        val root = if (file.isFile) {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        val key = AgentSession.keyOf(project, branch)
        val clean = name.trim()
        if (clean.isEmpty()) root.remove(key) else root.put(key, clean)
        file.parentFile?.mkdirs()
        // **Written whole and atomically**, because the runner reads it on every tick from another
        // worker: a half-written file is every branch's pointer gone at once.
        Beside.write(file, root.toString(2))
    }

    /**
     * Forgets **every branch pointer of [project]** — for a project that is moving accounts.
     *
     * **A pointer is a path in this account's folder**, so it must not travel: the unit it names lives
     * in `shared/requests/` here, and the destination has its own. Dropping the rows rather than
     * carrying them means a moved branch refuses by naming the picker, which is true and fixable —
     * a row naming a file that is not there would refuse by naming a file.
     */
    fun clearPointers(accountDir: File, project: String) {
        val file = pointerFile(accountDir)
        if (!file.isFile) return
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return
        }
        var changed = false
        for (key in root.keys().asSequence().toList()) {
            if (AgentSession.projectOf(key) == project) {
                root.remove(key)
                changed = true
            }
        }
        if (changed) Beside.write(file, root.toString(2))
    }

    /**
     * **The folder's existence is the flag**: when it is not there it is made and filled with the
     * shipped three, and when it is there it is left exactly as it is.
     *
     * That distinction is not a detail — it is the whole of "a person who deleted them meant it".
     * "Fill the folder when it holds no schema" reads the same and is wrong: a folder somebody emptied
     * by hand holds no schema either, and refilling it would be the app overruling them with the very
     * files they just removed. What a person who wants the defaults back does is delete the folder.
     *
     * Called before a list is shown rather than at first launch, so an account that upgrades into this
     * design gets them too.
     */
    fun ensure(accountDir: File): List<String> {
        val folder = dir(accountDir)
        if (folder.isDirectory) return names(accountDir)
        // **A folder that cannot be made is not an error here.** The list is allowed to be empty and
        // the picker says so; failing this call would make a read-only account unable to open Settings
        // at all, which is a worse answer than a list with nothing in it.
        folder.mkdirs()
        if (!folder.isDirectory) return emptyList()
        for ((name, schema) in shipped()) {
            write(accountDir, name, schema)
        }
        return names(accountDir)
    }

    /**
     * The three requests the app ships, one per kind — **and each is a complete request**, address and
     * model included, because a template that left them out could not be sent.
     *
     * `chat.json` is the OpenAI shape this app has always sent, with the model and effort as literals
     * where they used to be placeholders. The two others follow the same rule: the model is written
     * down, and only what the app knows at call time is a placeholder.
     */
    fun shipped(): List<Pair<String, Schema>> = listOf(
        "chat.json" to Schema(
            address = OPENROUTER_BASE,
            url = "chat/completions",
            // The numbers are the ones the general settings shipped with: a target the agent aims the
            // context at, and a trigger at which it is asked to clear up. Trim stays off — an app that
            // cuts a conversation on its own loses the thing the person was working on.
            target = 60_000,
            forget = 100_000,
            body = Shape.CHAT.literal(
                "model" to ModelConfigFile.DEFAULT_MODEL,
                "effort" to "high",
                // **`key` is a name, not a value** (§3.6.3). The shipped file names none, because a fresh
                // install has no row to name — and absent means "send no Authorization header at all",
                // which is the honest state of a phone that has not been given a key yet.
            ),
        ),
        "embedding.json" to Schema(
            address = OPENROUTER_BASE,
            url = Shape.EMBEDDING.url,
            body = Shape.EMBEDDING.literal(),
        ),
        "rerank.json" to Schema(
            address = OPENROUTER_BASE,
            url = Shape.RERANK.url,
            body = Shape.RERANK.literal(),
        ),
    )
}
