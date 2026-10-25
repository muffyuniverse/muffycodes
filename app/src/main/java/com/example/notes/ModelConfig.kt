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

/** The three values the agent is built from: which model, which endpoint, how hard it thinks. */
data class ActiveModel(
    val model: String,
    val provider: String,
    val effort: String,
)

/**
 * `model_conf.json` at the account root: the catalogue and the choice.
 *
 * **The file is the only store.** The settings rows and the picker are editors of it, so there
 * is no second copy of the same three values to disagree with the first.
 */
data class ModelConfig(
    val active: ActiveModel,
    val models: List<ModelChoice>,
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
        file.writeText(toJson(config).toString(2))
    }

    private fun toJson(config: ModelConfig): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("active", JSONObject().apply {
            put("model", config.active.model)
            put("provider", config.active.provider)
            put("effort", config.active.effort)
        })
        put("models", JSONArray().apply {
            config.models.forEach { choice ->
                put(JSONObject().apply {
                    put("id", choice.id)
                    put("providers", JSONArray(choice.providers))
                })
            }
        })
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

        return Result.success(ModelConfig(ActiveModel(model, provider, effort), models))
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
        val offset = Regex("""at character (\d+)""").find(message)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return message
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
