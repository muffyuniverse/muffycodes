package com.example.notes

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * One model the agent may use, and the endpoints it may be pinned to.
 *
 * A provider is a **tag**, not a name: `deepinfra/fp8` rather than `deepinfra`. OpenRouter's
 * routing documents that a base slug matches *every* endpoint of that provider ("dropping the
 * tier never matches a tier endpoint"), so a request pinned to a bare name is still routed among
 * that provider's several endpoints — which is not a pin, it is autorouting one step narrower.
 * Since the app promises no fallback and no autorouting, the thing that is stored has to be the
 * thing that is sent.
 */
data class ModelChoice(
    val id: String,
    val providers: List<String>,
)

/**
 * The three values the agent is built from: which model, which endpoint, how hard it thinks.
 *
 * **[contextLength] and [maxOutput] are the model's own window, stored with the choice.** They are
 * not a fourth setting: they are what the catalogue said about this model when it was picked, kept
 * beside it because the two places that build an agent — the screen's turn and the service's poll —
 * have no catalogue to ask. Without them a pin nobody measured fell back to a 96,000-token floor,
 * which is how a million-token model came to be trimmed at 96k.
 *
 * `null` means "not known yet": a config written by an older build, or a model chosen by hand.
 * Both readers then answer with the measured table or the floor, exactly as before.
 */
data class ActiveModel(
    val model: String,
    val provider: String,
    val effort: String,
    val contextLength: Int? = null,
    val maxOutput: Int? = null,
    /**
     * **Where the requests go** — the connection's own address, not the vendor's.
     *
     * This is the field that makes the app host-agnostic for models, and it is the reason [provider]
     * stops being a pin: OpenRouter's `provider.only` chose one upstream behind one fixed URL, and a
     * base URL *is* the upstream. Whatever speaks `POST /chat/completions` can be a connection.
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
    baseUrl.trim().trimEnd('/').ifEmpty { OPENROUTER_BASE } + "/chat/completions"

/**
 * A model chosen for one job, **where the job decides what the third value means.**
 *
 * The three catalogues are the same shape and their choices are not: a chat model has an effort
 * ladder, an embedding model has **dimensions**, and a rerank model has neither. The operator's
 * instruction is what settles the third one — *"dims can replace effort screen for embedding
 * model"* — so `extra` is named for what it holds rather than pretending the three are the same.
 *
 * **The dimension is a request parameter, not a property of the model.** `qwen/qwen3-embedding-8b`
 * answers 1024, 2048 or 4096 on demand (measured), so it belongs beside the id exactly as
 * `ActiveModel.contextLength` does — for the same reason: *"the two places that build an agent
 * have no catalogue to ask."* An index stores the dimension it was built at, and a mismatch is
 * "re-index needed", never a comparison.
 */
data class PickedModel(
    val model: String,
    val provider: String,
    /** The embedding dimension. Null for a chat or rerank model, which have no such choice. */
    val dimensions: Int? = null,
    /** The endpoints allowed for this model, in the order the person put them. */
    val providers: List<String> = emptyList(),
) {
    /**
     * What decides the **vectors** this choice produces: the model and the size, and nothing else.
     *
     * **The endpoint is deliberately absent.** It says where a request is sent, not where the numbers
     * come from, so moving a model to another provider keeps its index valid — and comparing whole
     * [PickedModel] values instead would have asked a person to rebuild every project for a change of
     * route. Two models at the same size are still two spaces: the size being equal does not make
     * their vectors comparable.
     */
    fun space(): Pair<String, Int?> = model to dimensions
}

/**
 * `model_conf.json` at the account root: the catalogue and the choice.
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
    val models: List<ModelChoice>,
    /** The model that turns text into vectors, and the dimension to ask it for. */
    val embedding: PickedModel? = null,
    /** The model that reorders search results. */
    val rerank: PickedModel? = null,
    /**
     * **The connection this account's models are reached through** — its base URL.
     *
     * **Kept beside `active` rather than inside it, and that is the point.** The chat model is one
     * choice among several, and the *account's* default is what a branch inherits
     * (`PLAN-credentials` §3.5); the address is a property of what answers, so it sits with the file
     * rather than with one row of it. Moving the chat model to another id must not move the address,
     * and moving to another provider must not silently keep the old one's URL.
     *
     * Reading is forgiving: a file written before this existed answers with the shipped default.
     */
    val baseUrl: String = OPENROUTER_BASE,
) {
    /** The endpoints allowed for [model], in the order the person put them. */
    fun providersOf(model: String): List<String> =
        models.firstOrNull { it.id == model }?.providers.orEmpty()

    /** What a row shows at a glance. */
    fun summary(): String = "${active.model} · ${active.provider} · ${active.effort}"
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

    /**
     * Its endpoints, as tags.
     *
     * `deepinfra/fp8/priority` is deliberately absent: it is the same provider's paid tier at
     * $0.1470/M input against $0.0980 for the standard one, and a default should be the boring
     * one. `/flex` is here beside `/fp8` rather than instead of it, because it is a different
     * service tier at a different price ($0.0784 against $0.0980) and choosing between them is
     * the person's, not the app's.
     */
    val DEFAULT_PROVIDERS = listOf(
        "deepseek",
        "deepinfra/fp8",
        "deepinfra/fp8/flex",
        "decart/fp4",
        "cloudflare",
    )

    fun default(): ModelConfig = ModelConfig(
        active = ActiveModel(DEFAULT_MODEL, "deepseek", "high"),
        models = listOf(ModelChoice(DEFAULT_MODEL, DEFAULT_PROVIDERS)),
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
            put("provider", config.active.provider)
            put("effort", config.active.effort)
            // Only when known, so a file this app writes stays the file it wrote before: a null
            // written as 0 would tell the ceiling that the model holds nothing.
            config.active.contextLength?.let { put("contextLength", it) }
            config.active.maxOutput?.let { put("maxOutput", it) }
        })
        put("models", JSONArray().apply {
            config.models.forEach { choice ->
                put(JSONObject().apply {
                    put("id", choice.id)
                    put("providers", JSONArray(choice.providers))
                })
            }
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
        put("model", one.model)
        put("provider", one.provider)
        put("providers", JSONArray(one.providers))
        one.dimensions?.let { put("dimensions", it) }
    }

    /**
     * Parses and checks the file. Failures name the place, because the person typing them is
     * the only one who can fix them: `line 7` for a syntax error, `active.provider` for a value
     * that does not hold together.
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

        val modelsArray = root.optJSONArray("models")
            ?: return Result.failure(IllegalArgumentException("models must be a list"))

        val models = ArrayList<ModelChoice>(modelsArray.length())
        for (i in 0 until modelsArray.length()) {
            val entry = modelsArray.optJSONObject(i)
                ?: return Result.failure(IllegalArgumentException("models[$i] must be an object"))
            val id = entry.optString("id").trim()
            if (id.isEmpty()) {
                return Result.failure(IllegalArgumentException("models[$i].id is empty"))
            }
            val providersRaw = entry.optJSONArray("providers")
                ?: return Result.failure(
                    IllegalArgumentException("models[$i].providers must be a list")
                )
            val providers = ArrayList<String>(providersRaw.length())
            for (j in 0 until providersRaw.length()) {
                val tag = providersRaw.optString(j).trim()
                if (tag.isEmpty()) {
                    return Result.failure(
                        IllegalArgumentException("models[$i].providers[$j] is empty")
                    )
                }
                if (tag.endsWith("/")) {
                    return Result.failure(
                        IllegalArgumentException(
                            "models[$i].providers[$j] ends with a slash — a provider is a tag " +
                                "like \"deepinfra/fp8\", not a prefix"
                        )
                    )
                }
                if (tag !in providers) providers.add(tag)
            }
            if (providers.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("models[$i].providers is empty — a model needs one")
                )
            }
            if (models.none { it.id == id }) models.add(ModelChoice(id, providers))
        }
        if (models.isEmpty()) {
            return Result.failure(IllegalArgumentException("models is empty — add one"))
        }

        val model = activeObject.optString("model").trim()
        val provider = activeObject.optString("provider").trim()
        val effort = activeObject.optString("effort").trim()

        if (model.isEmpty()) {
            return Result.failure(IllegalArgumentException("active.model is empty"))
        }
        val chosen = models.firstOrNull { it.id == model }
            ?: return Result.failure(
                IllegalArgumentException("active.model \"$model\" is not in models")
            )
        if (provider.isEmpty()) {
            return Result.failure(IllegalArgumentException("active.provider is empty"))
        }
        if (provider !in chosen.providers) {
            return Result.failure(
                IllegalArgumentException(
                    "active.provider \"$provider\" is not one of that model's providers"
                )
            )
        }
        if (effort.isEmpty()) {
            return Result.failure(IllegalArgumentException("active.effort is empty"))
        }

        // Absent is null, not zero: `optInt` answers 0 for a missing key, and a window of zero
        // tokens is a claim the catalogue never made.
        val window = if (activeObject.has("contextLength")) activeObject.optInt("contextLength", -1).takeIf { it > 0 } else null
        val reply = if (activeObject.has("maxOutput")) activeObject.optInt("maxOutput", -1).takeIf { it > 0 } else null
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
        return Result.success(
            ModelConfig(
                ActiveModel(model, provider, effort, window, reply),
                models,
                embedding = embedding,
                rerank = rerank,
                baseUrl = base.ifEmpty { OPENROUTER_BASE },
            ),
        )
    }

    /**
     * One optional chosen-model block, or null when the file has none.
     *
     * **A block that is present must be complete.** `{"model":"x"}` with no provider is not a
     * half-chosen model, it is a file someone edited wrongly, and the refusal names the place —
     * `embedding.provider` — because that is what the person has to fix.
     */
    private fun pickedIn(root: JSONObject, key: String): Result<PickedModel?> {
        val one = root.optJSONObject(key) ?: return Result.success(null)
        val model = one.optString("model").trim()
        if (model.isEmpty()) {
            return Result.failure(IllegalArgumentException("$key.model is empty"))
        }
        val provider = one.optString("provider").trim()
        if (provider.isEmpty()) {
            return Result.failure(IllegalArgumentException("$key.provider is empty"))
        }
        val providersRaw = one.optJSONArray("providers")
        val providers = ArrayList<String>(providersRaw?.length() ?: 0)
        if (providersRaw != null) {
            for (j in 0 until providersRaw.length()) {
                val tag = providersRaw.optString(j).trim()
                if (tag.isNotEmpty()) providers.add(tag)
            }
        }
        // **The dimension, when the file names one.** Not checked against a list here: a model may
        // support dimensions this build has never heard of, and refusing them would make the file
        // less capable than the API. A value that is not a positive number is a typo, though.
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
        return Result.success(PickedModel(model, provider, dimensions, providers))
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
     * The effort to use when the chosen model does not declare the chosen one.
     *
     * "It moves down to a supported one, and the operator can raise it": the highest supported
     * effort **at or below** what was asked, and when the model's ladder starts higher than that
     * (a model offering only `high` and `max`), the lowest it has — because there is nothing
     * below to move down to, and refusing the model would be the app overruling a choice it was
     * given. Returns null when the model declares nothing, in which case nothing is sent.
     */
    fun effortFor(supported: List<String>, asked: String): String? {
        if (supported.isEmpty()) return null
        if (asked in supported) return asked
        val rank = effortRank(asked)
        val below = supported.filter { effortRank(it) in 0..rank }.maxByOrNull { effortRank(it) }
        return below ?: supported.minByOrNull { effortRank(it) }
    }

    /**
     * How hard a named effort is, so "down" means something.
     *
     * The names are a ladder rather than a set: a model that offers `low` and `max` has nothing
     * in between, and a request for `high` has to land on one of them. Unknown names rank below
     * every known one, so a model with an effort this app has never seen is still placed
     * somewhere instead of throwing.
     */
    fun effortRank(name: String): Int = when (name.trim().lowercase()) {
        "none", "off", "minimal" -> 0
        "low" -> 1
        "medium", "mid" -> 2
        "high" -> 3
        "max", "maximum" -> 4
        else -> -1
    }
}
