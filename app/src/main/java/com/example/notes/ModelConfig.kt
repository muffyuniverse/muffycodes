package com.example.notes

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * **What a request says about itself**: the model it asks for, how hard it should think, and where it
 * goes.
 *
 * Three fields, and there used to be a fourth: `provider`, the OpenRouter endpoint tag the request was
 * pinned to. It is gone with the catalogue that published the tags — the address *is* the upstream now,
 * so a sentence that wants the word asks [providerOf] for the host.
 *
 * **The model's window and reply reserve were here too**, and they are gone with the ceiling they fed
 * (see `ContextBudget`): the app does not derive a limit from a model, so a number about a model's size
 * would be a fact nothing reads.
 */
data class ActiveModel(
    val model: String,
    val effort: String,
    /**
     * **Where the requests go** — the connection's own address, not the vendor's.
     *
     * This is the field that makes the app host-agnostic for models, and it is why the provider tag is
     * gone: OpenRouter's `provider.only` chose one upstream behind one fixed URL, and a base URL *is*
     * the upstream. Whatever speaks `POST /chat/completions` can be a connection — and a person who
     * wants a particular downstream writes `"provider": {"only": [...]}` in the request shape, which is
     * a body field and therefore needs no code at all.
     *
     * **A default rather than a required field**, so every config written before this existed keeps
     * working and means exactly what it meant — and so a person editing `model_conf.json` by hand does
     * not have to know the address to keep using the app.
     */
    val baseUrl: String = OPENROUTER_BASE,
)

/** OpenRouter's base URL, which is the default connection and the one the app ships. */
const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"

/**
 * **The address a request is sent to**, checked rather than trusted.
 *
 * A trailing slash in the file would otherwise produce `https://x/api/v1//chat/completions`, which most
 * servers tolerate and some do not — and a person who typed one would be told the connection is broken.
 * An empty value falls back to the shipped default rather than producing a relative URL.
 */
fun chatEndpoint(baseUrl: String): String =
    // **The join is the shape's** ([Shape.address]), and the fallback is here. A connection with no
    // address is the vendor's default; an address is joined one way, in one place, so a shape's url
    // that gains or loses a slash cannot produce `//chat/completions` at one call site and not
    // another.
    Shape.CHAT.address(baseUrl.trim().ifEmpty { OPENROUTER_BASE })

/**
 * A model chosen for one job, **where the job decides what the third value means.**
 *
 * The three catalogues are the same shape and their choices are not: a chat model has an effort
 * ladder, an embedding model has **dimensions**, and a rerank model has neither. The operator's
 * instruction is what settles the third one — *"dims can replace effort screen for embedding
 * model"* — so `extra` is named for what it holds rather than pretending the three are the same.
 *
 * **The dimension is a request parameter, not a property of the model.** `qwen/qwen3-embedding-8b`
 * answers 1024, 2048 or 4096 on demand (measured), so it belongs beside the id: the two places that
 * build an embedder have no catalogue to ask, and an index stores the dimension it was built at, so a
 * mismatch is "re-index needed" rather than a comparison.
 */
data class PickedModel(
    /**
     * **The template this points at** — a file name in `shared/schemas/`, like `embedding.json`.
     *
     * A name rather than an id, because the thing that decides where the call goes and what it says is
     * the file. That is the operator's 2026-11-09 decision: *"rerank and embedding pickers can also just
     * reference a template file from this folder"*. The name is stored rather than the whole request so
     * that editing the template changes the behaviour of every worktree that names it — which is the
     * point of having one folder.
     */
    val schema: String,
    /**
     * **The size the index was built at**, when the job has one.
     *
     * It stays a number here rather than a literal in the file because it is a property of *this phone's
     * index*: the template fills `{{dimensions}}` at call time, and the index that is compared against
     * needs the value that was actually used. Null for a rerank model, which has no such parameter.
     */
    val dimensions: Int? = null,
) {
    /**
     * What decides the **vectors** this choice produces: the template and the size, and nothing else.
     *
     * **The address inside the template is deliberately absent.** It says where a request is sent, not
     * where the numbers come from, so pointing the same file at another server keeps the index valid —
     * and comparing whole [PickedModel] values instead would have asked a person to rebuild every
     * project for a change of route. Two templates producing different vectors are still different
     * spaces even at the same size: the size being equal does not make their vectors comparable, so the
     * *name* is part of the identity.
     */
    fun space(): Pair<String, Int?> = schema to dimensions
}

/**
 * **Which of the two models that are not the chat model** — the one that embeds, and the one that
 * reranks.
 *
 * Two values rather than three: `CatalogueKind` had a `CHAT` member because the picker's path was a
 * parameter, and there is no picker and no path any more. The label is here so a sentence that says
 * "chose X as the embedding model" does not have to spell the words at the call site.
 */
/**
 * **The embedding sizes this build offers**, in the order the dimension row cycles them.
 *
 * `qwen/qwen3-embedding-8b` answers 1024, 2048 or 4096 on demand — measured, not assumed — which is why
 * the size is a property of the *choice* rather than of the model. A template whose server answers others
 * still works: the number is filled into `{{dimensions}}` and the server decides whether it can.
 */
val SCHEMA_DIMENSIONS = listOf(1024, 2048, 4096)

enum class PickedKind(val label: String) {
    EMBEDDING("embedding model"),
    RERANK("rerank model"),
}

/**
 * `model_conf.json` at the account root: the choice, and the two models that build and order vectors.
 *
 * **The file is the only store.** The settings rows and the picker are editors of it, so there
 * is no second copy of the same three values to disagree with the first.
 *
 * **[embedding] and [rerank] are nullable, and that is the honest shape.** A config written before
 * this app could search has no embedding model, and the right answer is "not chosen yet" — not a
 * default slug silently picked on the person's behalf, which would be a model choice they never
 * made and cannot see. `null` reads as "no embedding model", and the search feature says so.
 */
data class ModelConfig(
    val active: ActiveModel,
    /** The model that turns text into vectors, and the dimension to ask it for. */
    val embedding: PickedModel? = null,
    /** The model that reorders search results. */
    val rerank: PickedModel? = null,
    /**
     * **The address the embedding and rerank calls go to** — not the chat model's.
     *
     * The chat model's address is a *scope's* (`ActiveModel.baseUrl`, resolved by `wiredFor`), because
     * a branch may talk to a different server than its project. The two models that build vectors are
     * the other way round on purpose: there is one index per worktree and one vector space per
     * project (`PickedModel.space`), so the way they are reached is a property of the account and
     * cannot be overridden deeper without silently mixing spaces.
     *
     * Reading is forgiving: a file written before this existed answers with the shipped default.
     */
    val baseUrl: String = OPENROUTER_BASE,
) {
    /**
     * What a row shows at a glance — **the model, the host it is asked at, and the effort.**
     *
     * The host rather than a provider tag: there are no tags left to print, and a sentence that named
     * one would be naming a thing the app no longer stores.
     */
    fun summary(): String = "${active.model} · ${providerOf(active.baseUrl)} · ${active.effort}"
}

/**
 * Reading and writing `model_conf.json`.
 *
 * Two rules decide the shape of this file, and both come from it being editable by a person:
 *
 * - **It is written by forms, so it is JSON and never hand-parsed.** A permissive format would
 *   need a parser with its own bugs; `org.json` is already in the app and is correct.
 * - **A file that will not parse is refused, never replaced.** A config parser that silently
 *   falls back to the defaults is a config parser that eats the person's work, and they find
 *   out from an agent using a model they did not choose.
 */
object ModelConfigFile {

    const val NAME = "model_conf.json"
    const val VERSION = 1

    /** The model the app ships set up, measured against OpenRouter on 2026-10-25. */
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash-1017"

    fun default(): ModelConfig = ModelConfig(
        active = ActiveModel(DEFAULT_MODEL, "high"),
        baseUrl = OPENROUTER_BASE,
    )

    fun file(accountDir: File): File = File(accountDir, NAME)

    /**
     * Reads the config, writing the shipped one first when there is no file yet.
     *
     * A missing file is not an error — it is a new account — so it is created. A file that is
     * there and cannot be read is an error, and the caller keeps the config it already had.
     */
    fun load(accountDir: File): Result<ModelConfig> {
        val file = file(accountDir)
        if (!file.isFile) {
            val fresh = default()
            return try {
                write(accountDir, fresh)
                Result.success(fresh)
            } catch (e: Exception) {
                Result.success(fresh)
            }
        }
        return read(file)
    }

    fun read(file: File): Result<ModelConfig> = try {
        val text = file.readText()
        parse(text).fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IllegalArgumentException("${file.name}: ${it.message}")) },
        )
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("${file.name} could not be read"))
    }

    fun write(accountDir: File, config: ModelConfig) {
        val file = file(accountDir)
        file.parentFile?.mkdirs()
        // Pretty rather than one line: a person may open this from the editor, and a single
        // 400-character line is the one shape nobody can read.
        file.writeText(text(config))
    }

    /**
     * The file's text for [config], **without writing it.**
     *
     * The round-trip test needs this, and so does any caller that wants to show a person what the app
     * would write before it writes it. `write` is this plus a file, so the two cannot disagree about
     * the shape — which is the only way a "what we write is what we can read" test means anything.
     */
    fun text(config: ModelConfig): String = toJson(config).toString(2)

    private fun toJson(config: ModelConfig): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("active", JSONObject().apply {
            put("model", config.active.model)
            put("effort", config.active.effort)
            // **Nothing about the model's size is written any more.** A file that still has those
            // keys is read without them, which is why the reader ignores them rather than failing:
            // the app stopped clamping, so a number nobody uses is not worth a migration.
        })
        // **Only when chosen.** A null written as `{}` would read back as a model with no id, and
        // the difference between "no embedding model" and "an empty one" is the difference between
        // a feature that is off and a config that is broken.
        config.embedding?.let { put("embedding", picked(it)) }
        config.rerank?.let { put("rerank", picked(it)) }
        // **Written only when it is not the shipped one.** A file the app writes stays the file it
        // wrote before, so an OpenRouter account's config does not sprout an address it never chose —
        // and the absence of the field keeps meaning "the default", not "empty".
        if (config.baseUrl != OPENROUTER_BASE) put("baseUrl", config.baseUrl)
    }

    /** One chosen model as JSON, with the dimension only when the job has one. */
    private fun picked(one: PickedModel): JSONObject = JSONObject().apply {
        put("schema", one.schema)
        one.dimensions?.let { put("dimensions", it) }
    }

    /**
     * Parses and checks the file. Failures name the place, because the person typing them is
     * the only one who can fix them: `line 7` for a syntax error, `active.model` for a value that
     * does not hold together.
     */
    fun parse(text: String): Result<ModelConfig> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Result.failure(IllegalArgumentException(whereIn(text, e)))
        }

        val version = root.optInt("version", 0)
        if (version != VERSION) {
            return Result.failure(
                IllegalArgumentException("version $version is not one this app knows ($VERSION)")
            )
        }

        val activeObject = root.optJSONObject("active")
            ?: return Result.failure(IllegalArgumentException("active must be an object"))

        val model = activeObject.optString("model").trim()
        val effort = activeObject.optString("effort").trim()

        if (model.isEmpty()) {
            return Result.failure(IllegalArgumentException("active.model is empty"))
        }
        // **An empty effort is allowed now, and it means "send none".** The shape's second rule
        // already removes a key whose placeholder is blank, so a model that refuses
        // `reasoning_effort` is served by leaving this empty — which is a statement, not a gap.
        //
        // **`provider` is read and dropped when present**, like the window and the reply reserve: a
        // file the previous build wrote still names one, the address is the upstream now, and a
        // migration for a word nobody routes by would be work spent on nothing.

        // **The two optional blocks, read only when present.** A block that is there and wrong is a
        // refusal like any other, because a config that silently dropped a model the person chose
        // would leave search using nothing while the file plainly names one.
        var failed: Throwable? = null
        val embedding = pickedIn(root, "embedding").getOrElse {
            failed = it
            null
        }
        if (failed != null) return Result.failure(failed!!)
        val rerank = pickedIn(root, "rerank").getOrElse {
            failed = it
            null
        }
        if (failed != null) return Result.failure(failed!!)
        // **The address, if the file carries one.** Absent means the shipped default, which is what
        // every config written before this field existed means — and what a person who typed nothing
        // would expect. A value that is present must look like an address, because "openai" in this
        // field would produce a relative URL and an error nobody could act on.
        val base = root.optString("baseUrl").trim()
        if (base.isNotEmpty() && !base.startsWith("http://") && !base.startsWith("https://")) {
            return Result.failure(
                IllegalArgumentException(
                    "baseUrl must start with http:// or https:// — it is an address, " +
                        "like https://openrouter.ai/api/v1",
                ),
            )
        }
        // **The model is read before the address**, because the address is what the model is asked
        // at and a config with no model cannot be asked anything at all.
        return Result.success(
            ModelConfig(
                ActiveModel(model, effort, base.ifEmpty { OPENROUTER_BASE }),
                embedding = embedding,
                rerank = rerank,
                baseUrl = base.ifEmpty { OPENROUTER_BASE },
            ),
        )
    }

    /**
     * **One pointer at a template**, or null when the file has none.
     *
     * The key is `schema` — a file name in `shared/schemas/` — and **its absence is what turns the
     * feature off.** An empty string is a file somebody edited wrongly rather than a model they have not
     * chosen yet, so it is refused with the place named, the same rule `model_conf.json` has always had.
     *
     * **Nothing is checked against the folder here.** A template can be renamed or deleted after the
     * pointer is written, and the call site that reads the file is where that is reported — it is the
     * place that knows which job the template was for. Refusing here would make a missing file into a
     * config that will not load, which is a worse answer than a feature saying "that template is gone".
     */
    private fun pickedIn(root: JSONObject, key: String): Result<PickedModel?> {
        val one = root.optJSONObject(key) ?: return Result.success(null)
        val schema = one.optString("schema").trim()
        if (schema.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("$key.schema is empty — name a template, or remove the block"),
            )
        }
        // **The dimension, when the file names one.** Not checked against a list: a model may support
        // dimensions this build has never heard of, and refusing them would make the file less capable
        // than the server. A value that is not a positive number is a typo, though.
        val dimensions = if (one.has("dimensions")) {
            val value = one.optInt("dimensions", -1)
            if (value <= 0) {
                return Result.failure(
                    IllegalArgumentException("$key.dimensions must be a positive whole number"),
                )
            }
            value
        } else {
            null
        }
        return Result.success(PickedModel(schema, dimensions))
    }

    /**
     * Where a syntax error is.
     *
     * `org.json` reports a character offset, not a line, and a person looking at a 40-line file
     * cannot use an offset. So it is converted — and when the message carries no offset at all,
     * the message is passed through rather than invented.
     */
    private fun whereIn(text: String, error: JSONException): String {
        val message = error.message.orEmpty().ifBlank { "it is not valid JSON" }
        // Two shapes, and both are `org.json`'s: an older "at character 58" and the newer
        // "at 58 [character 59 line 4]". The character offset is the one that appears in both,
        // so it is what the line is counted from — and a message that already names a line is
        // passed through with its own words rather than re-derived.
        // **Its own line wins when it has one.** `org.json` sometimes reports
        // "at 97 [character 0 line 5]" — a character offset of zero with the line it really
        // means in brackets — so counting from the offset put the mistake on line 1 of a file
        // whose fifth line is the broken one. The bracket form is the parser's own answer.
        Regex("""line (\d+)""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return "line $it: $message" }
        val offset = Regex("""character (\d+)""").find(message)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\bat (\d+)""").find(message)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return message
        val line = text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1
        return "line $line: $message"
    }

    /**
     * **The effort ladder is gone with the catalogue that published it.**
     *
     * `effortFor(supported, asked)` used to move a request down to an effort the *endpoint* declared,
     * and `effortRank` was the order it moved in. Both needed the catalogue's `supported_efforts`,
     * which is OpenRouter's addition — OpenAI defines exactly one `reasoning_effort` and no way to
     * ask a server which values it takes. So the effort is now what the person typed: a value the
     * server does not know is a request it refuses, in its own words, and the fix is one word in the
     * window rather than a floor the app chose silently.
     */
}