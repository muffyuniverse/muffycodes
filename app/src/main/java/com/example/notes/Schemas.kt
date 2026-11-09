package com.example.notes

import java.io.File

/**
 * **The folder of request schemas** — `<account>/.muffy/schemas/`, one [Schema] per file.
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
 * ### Why `.muffy/`
 *
 * `.muffy/` is the app's own state — outside every project, outside every repository, and already
 * excluded from what an agent may touch. A schema folder under a project would either be committed
 * (a request shape is the person's, not the repository's) or need an ignore rule about a folder that is
 * not in the tree; under `.muffy/` it is hidden from the project list and still reachable, which is the
 * "hidden folder" the operator asked for.
 *
 * ### Why the shipped ones exist
 *
 * A fresh account must be able to run an agent before it has written anything, and an empty folder
 * would be a feature that says "make a request schema" to somebody who has never seen one. So three are
 * written the first time the folder is read **and only when it holds no schema at all** — a person who
 * deleted them all means it, and one of the three coming back would be the app overruling them.
 */
object Schemas {

    /** The folder, as a path from the account root — the same path the browser shows. */
    const val DIR = ".muffy/schemas"

    /** The suffix every schema has. A file without it is not offered, so a note can live here too. */
    const val SUFFIX = ".json"

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
     * Writes the shipped schemas when the folder holds none, and answers what is there.
     *
     * Called before a list is shown rather than at first launch, so an account that upgrades into this
     * design gets them too — and a folder a person emptied is left empty, because the check is "no
     * schema at all" rather than "a schema missing".
     */
    fun ensure(accountDir: File): List<String> {
        val folder = dir(accountDir)
        val there = names(accountDir)
        // **A folder that cannot be made is not an error here.** The list is allowed to be empty, and
        // the picker says so; failing this call would make a read-only account unable to open Settings.
        if (there.isNotEmpty() || !folder.mkdirs()) return there
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
