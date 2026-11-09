package com.example.notes

import org.json.JSONObject
import java.io.File

/**
 * **The request library: two folders, and the difference between them is who may write.**
 *
 * | folder | holds | written by | locked |
 * |---|---|---|---|
 * | `shared/schemas/<kind>/` | **references** — the request structure, each field explained | the app only | **yes** |
 * | `shared/requests/<kind>/` | **units** — what the app actually sends | the person, and the agent | no |
 *
 * The operator's words for the second, 2026-11-09: *"shared folder holds the actual templates with
 * request structure commented system locked templates in jsonc (to allow for comments), and user or
 * agent can copy those and make a filled in units to be used by the app to power chat agents."*
 *
 * **Why two folders rather than one with a lock list.** The reference must be untouchable and the unit
 * must be the person's; one folder would need a lock entry per shipped file, and the entry list would
 * have to grow every time the app shipped another reference. Two folders make the rule a location, so
 * the lock is the location and there is no per-file bookkeeping.
 *
 * **The subfolder is the kind**, so a picker's list is already the right kind: an embedding picker
 * cannot offer a chat unit at all, because it does not read that folder.
 *
 * ### Why there is no editor for any of this
 *
 * A request is a document and a person can copy one — so the browser and the editor the app already
 * has **are** the template manager. The same screen reads a unit, the same Save writes it, and the same
 * "New file" makes one. That is the operator's design and it is why this object is small.
 *
 * **What a unit does not get is a history**: the version browser is `GitService.versionsOf(repo, path)`
 * and `shared/` is outside every repository. An edit is undone by editing it back, or by copying the
 * file first. Putting the folder inside a repository to inherit a history would put request shapes into
 * commits, which is what the hidden folder exists to avoid.
 *
 * ### A unit is a copy, not a second format
 *
 * With JSONC ([Jsonc]) the comments survive the copy, so the shipped unit *is* the reference with its
 * working values filled in — one artifact at two addresses, and an agent that edits a unit keeps the
 * sentence explaining what it changed.
 */
object Schemas {

    /** The references, as a path from the account root — the same path the browser shows. */
    val DIR = Workspace.SYSTEM_PROJECT + "/schemas"

    /** The units. **The folder a person works in**, and the only one the pickers read. */
    val REQUESTS = Workspace.SYSTEM_PROJECT + "/requests"

    /** Where the shipped reference lives, under [DIR]. */
    const val REFERENCE = "openrouter.jsonc"

    /**
     * **The two suffixes a schema may have**, and a file with neither is not offered, so a note can
     * live beside one.
     *
     * `.jsonc` is the one the folder is for — a template that explains a field beside itself — and
     * `.json` stays because strict JSON is a subset: a person who writes it has written a valid
     * template, and refusing it would be the app preferring a spelling to a document.
     */
    val SUFFIXES = listOf(".jsonc", ".json")

    /** True when [name] is a file this library offers. */
    fun isSchemaName(name: String): Boolean = SUFFIXES.any { name.endsWith(it) }

    /** The references for [kind] — **the app's**, and locked as a subtree. */
    fun refsDir(ws: Workspace, kind: ShapeKind): File = ws.fileAt((DIR + "/" + kind.folder).split('/'))

    /** The units for [kind] — **the person's and the agent's**. */
    fun unitsDir(ws: Workspace, kind: ShapeKind): File =
        ws.fileAt((REQUESTS + "/" + kind.folder).split('/'))

    /** The account-relative path a unit [name] of [kind] has — what a pointer stores. */
    fun unitPath(kind: ShapeKind, name: String): String = "$REQUESTS/${kind.folder}/$name"

    /**
     * The units of [kind], by name, **sorted**, so the pickers read in one order.
     *
     * Read on every call rather than cached: a person renames or deletes one in the editor, and a list
     * built once would keep offering a file that is no longer there.
     */
    fun names(ws: Workspace, kind: ShapeKind): List<String> =
        unitsDir(ws, kind).listFiles()
            .orEmpty()
            .filter { it.isFile && isSchemaName(it.name) && !it.name.startsWith(".") }
            .map { it.name }
            .sorted()

    /**
     * Reads the form at [path], or says why it cannot be used.
     *
     * **A path, not a name** (§3.8.3), because that is what a pointer stores: the operator's word is
     * *"a path link"*, a unit may be kept wherever the person wants (including inside a project), and
     * the refusal can quote the whole path — which is what a person needs when a pointer outlives the
     * file it named.
     *
     * **A path that climbs out is refused rather than clamped**: `..` names something the library does
     * not have, and clamping it would read a different file than the one written down.
     */
    fun read(ws: Workspace, path: String, kind: ShapeKind? = null): Result<Schema> {
        val clean = path.trim().trim('/')
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("No request form is named."))
        val parts = clean.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            return Result.failure(
                IllegalArgumentException(
                    "A request form's path cannot climb out of the account: \"$path\".",
                ),
            )
        }
        val file = ws.fileAt(parts)
        if (!file.isFile) {
            return Result.failure(
                IllegalArgumentException("There is no request form at \"$clean\"."),
            )
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("\"$clean\" could not be read"))
        }
        return Schema.parse(text)
            .mapCatching { schema ->
                // **The kind is a fact about the place that chose the file**, so it is checked here
                // rather than trusted from the document.
                kind?.let { schema.problem(it) }?.let { throw IllegalArgumentException("$clean: $it") }
                schema
            }
            .recoverCatching { throw IllegalArgumentException("$clean: ${it.message}") }
    }

    /**
     * Writes [schema] as the unit [name] of [kind] — the app's one writer, and it takes a **name**
     * rather than a path.
     *
     * **A name, because a path is a thing to read and a name is a thing to write.** The pointer is a
     * path, so a form may be kept anywhere; but the app only ever writes the unit it is asked to
     * write, in the folder that kind's picker reads. A path here would be a second way to say where a
     * file lands, and the leaf check would not catch `sub/chat.json` — which is a name that is fine
     * and a folder that is not.
     *
     * A unit the app rewrites loses its comments, because [Schema.text] writes plain JSON: acceptable,
     * and worth saying because it is the one place a comment can vanish.
     */
    fun write(ws: Workspace, kind: ShapeKind, name: String, schema: Schema): Result<Unit> {
        nameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        return putFile(ws, unitPath(kind, name.trim()), schema.text())
    }

    /** Removes one. A missing file is not an error: the state asked for is the state reached. */
    fun remove(ws: Workspace, path: String): Boolean = ws.fileAt(path.trim().trim('/').split('/')).delete()

    /**
     * Why [name] cannot be a schema's file name, or null when it can.
     *
     * **A name is a file name, and the checks are about the folder rather than about taste.** A slash
     * would write into another folder, a leading dot would make the file invisible to [names], and a
     * name that ends in neither of [SUFFIXES] would never be offered again — each of those is a silent
     * disappearance, so each is refused in words.
     */
    fun nameProblem(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "A request form needs a name."
        if (clean.contains('/') || clean.contains('\\')) {
            return "A request form's name cannot contain a slash — it is a file in one folder."
        }
        if (clean.startsWith(".")) return "A request form's name cannot start with a dot."
        if (!isSchemaName(clean)) {
            return "A request form's name must end in ${SUFFIXES.joinToString(" or ")}."
        }
        if (clean.length > 64) return "That name is too long; keep it under 64 characters."
        return null
    }

    /**
     * **Which form a chat runs** — a path, keyed `project/branch`.
     *
     * Stored beside `agents.json` in `.muffy/`, and **not inside the session**: a form is chosen when
     * the chat is opened, which is before the first turn, so a session record would be the wrong home
     * for it — and forgetting a session would silently forget its form.
     *
     * One file, read and written whole, keyed exactly as [AgentSession.keyOf] keys a session: `main`
     * keeps the bare project name, a branch is `project/branch`.
     */
    fun pointerFile(accountDir: File): File =
        File(File(accountDir, Workspace.STATE_DIR), POINTERS)

    /** The form [project] on [branch] runs, as a path, or null when it has not chosen one. */
    fun pointer(accountDir: File, project: String, branch: String): String? {
        val file = pointerFile(accountDir)
        if (!file.isFile) return null
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // **A broken file is "nobody has chosen"**, not a crash on every tick: the sessions beside
            // it are the app's record of what is running, and losing a form pointer must not take that
            // with it. The refusal the agent gets names the picker, so the person can set it again.
            return null
        }
        return root.optString(AgentSession.keyOf(project, branch)).trim().takeIf { it.isNotEmpty() }
    }

    /** Points [project] on [branch] at [path], or forgets the pointer when [path] is blank. */
    fun setPointer(accountDir: File, project: String, branch: String, path: String) {
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
        val clean = path.trim()
        if (clean.isEmpty()) root.remove(key) else root.put(key, clean)
        file.parentFile?.mkdirs()
        // **Written whole and atomically**, because the runner reads it on every tick from another
        // worker: a half-written file is every branch's pointer gone at once.
        Beside.write(file, root.toString(2))
    }

    /**
     * Forgets **every branch pointer of [project]** — for a project that is moving accounts.
     *
     * **A pointer is a path inside this account's folders**, so it must not travel: the unit it names
     * lives in `shared/requests/` here, and the destination has its own. Dropping the rows rather than
     * carrying them means a moved branch refuses by naming the picker, which is true and fixable — a
     * row naming a file that is not there would refuse by naming a file.
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
     * **The `requests` folder's existence is the flag**: when it is not there, both folders are made
     * and filled with what ships; when it is there, they are left exactly as they are.
     *
     * That distinction is not a detail — it is the whole of "a person who deleted them meant it".
     * "Fill the folders when they hold no schema" reads the same and is wrong: a folder somebody
     * emptied by hand holds none either, and refilling it would be the app overruling them with the
     * very files they just removed. What a person who wants the defaults back does is delete
     * `shared/requests/`.
     *
     * **One flag for both folders**, so emptying either is a thing a person can mean, and so there is
     * no second flag that can disagree with the first.
     */
    fun ensure(ws: Workspace) {
        if (ws.fileAt(REQUESTS.split('/')).isDirectory) return
        for ((path, text) in shipped()) {
            putFile(ws, path, text)
        }
    }

    /** One file, made with its folder. */
    private fun putFile(ws: Workspace, path: String, text: String): Result<Unit> = try {
        val file = ws.fileAt(path.split('/'))
        file.parentFile?.mkdirs()
        file.writeText(text)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("\"$path\" could not be written"))
    }

    /** Every file the app ships: its path from the account root, and its text. */
    fun shipped(): List<Pair<String, String>> = listOf(
        "$DIR/${ShapeKind.CHAT.folder}/$REFERENCE" to REFERENCE_TEXT,
        unitPath(ShapeKind.CHAT, UNIT) to UNIT_TEXT,
    )

    /** The name of the shipped unit — the one a fresh account can send before it writes anything. */
    const val UNIT = "deepseek-1017.jsonc"

    /**
     * **The reference: the format, explained field by field.** Locked, so it can be copied and never
     * edited — it is the one file the app writes for itself, and a person who changed it would be
     * changing the example every copy comes from.
     */
    private val REFERENCE_TEXT = """
        // A request form for a chat, explained. This is the example every copy comes from: copy it
        // into shared/requests/chat/ and make the copy say what you want. The original is locked,
        // because a person editing it would be editing the example rather than a request.
        //
        // The app sends exactly this body, to exactly this address. Nothing here is decoration.
        {
          // Where the bytes go. The host is the connection, and the key named below must belong to
          // it: a key stored for one host is not offered to another.
          "address": "https://openrouter.ai/api/v1",
          // The path on that address — a path, never a full address. A url that could carry a scheme
          // could send the key to a server nobody chose.
          "url": "chat/completions",
          // The vault row that holds this server's key, by NAME and not by value: "@openrouter".
          // Create the row in Settings → Secrets, or mint one with the OpenRouter button there.
          // Absent means this server wants no key at all, which is what a model on this phone is.
          "key": "@openrouter",
          // Where the context should end up, and the size at which the agent is asked to clear up.
          // Both are numbers of tokens; absent or zero means that hand is off.
          "target": 60000,
          "forget": 100000,
          // What is sent. Only what the app alone knows at call time is a placeholder: the
          // conversation, its tools, whether the reply is streamed, and the answer's size.
          // The model and the effort are written down, because they are names the server assigned
          // and they are the same every turn — so a second model is a second file.
          "body": {
            "model": "deepseek/deepseek-v4.1-flash-1017",
            "messages": "{{messages}}",
            "tools": "{{tools}}",
            "tool_choice": "{{tool_choice}}",
            "stream": "{{stream}}",
            "stream_options": { "include_usage": true },
            "reasoning_effort": "high",
            "max_tokens": "{{max_tokens}}"
          }
        }
    """.trimIndent()

    /**
     * **The unit: a working request, out of the box.** A copy of the reference with the explanation
     * reduced to the one thing a person edits first.
     *
     * The shipped one is not special: it is a file in the person's folder, it can be edited, and
     * deleting it is the way to ask for it back. The rule it follows is the operator's — *"a request
     * form for openrouter's deepseek 1017 endpoint when user chooses to use this door … will work out
     * of the box"* — which is true because the key it names (`@openrouter`) is the row minting writes,
     * and a form that names no key sends none.
     */
    private val UNIT_TEXT = """
        // The chat request this app ships. It works as soon as a key called @openrouter exists in
        // Settings → Secrets, and it is yours to change: copy it for another model, edit it for
        // another server. The format is explained in shared/schemas/chat/$REFERENCE.
        {
          "address": "https://openrouter.ai/api/v1",
          "url": "chat/completions",
          "key": "@openrouter",
          "target": 60000,
          "forget": 100000,
          "body": {
            "model": "deepseek/deepseek-v4.1-flash-1017",
            "messages": "{{messages}}",
            "tools": "{{tools}}",
            "tool_choice": "{{tool_choice}}",
            "stream": "{{stream}}",
            "stream_options": { "include_usage": true },
            "reasoning_effort": "high",
            "max_tokens": "{{max_tokens}}"
          }
        }
    """.trimIndent()

    /** Where the worktree pointers live, under `.muffy/` — the app's own state, beside `agents.json`. */
    private const val POINTERS = "schemas.json"
}
