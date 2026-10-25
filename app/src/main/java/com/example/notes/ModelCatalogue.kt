package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What OpenRouter says about one model, as the picker shows it.
 *
 * **`null` means "the API does not say", and that is displayed as `—`, never as 0.** The
 * distinction is not pedantry: on this app's own default model `max_prompt_tokens` is null on
 * every endpoint, `throughput_last_30m` was null on all four pinned endpoints when this was
 * written, and only 286 of 467 models in the catalogue carry an Intelligence Index at all. A row
 * of zeroes would say "this provider has stopped", which is a different and alarming statement.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    /** Release date, from the catalogue's `created` (seconds since the epoch). */
    val releasedAt: Long?,
    val contextLength: Int?,
    val maxOutput: Int?,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val intelligence: Double?,
    val efforts: List<String>,
    val defaultEffort: String?,
)

/**
 * One endpoint of one model — a provider tag and the numbers that belong to it.
 *
 * A model has many, and they are not interchangeable: on the same model DeepSeek reports a
 * 393,216-token reply and DeepInfra 131,072, which changes how much context can safely be sent.
 */
data class EndpointInfo(
    val tag: String,
    val providerName: String,
    val contextLength: Int?,
    val maxPrompt: Int?,
    val maxOutput: Int?,
    /** US dollars per million tokens. */
    val priceIn: Double?,
    val priceOut: Double?,
    val priceCache: Double?,
    /** Tokens per second, the median of the last half hour, when OpenRouter has seen traffic. */
    val tokensPerSecond: Double?,
    val uptime: Double?,
    /** Whether this endpoint can call tools at all, which the agent needs for everything. */
    val tools: Boolean,
)

/** Where the picker's numbers come from. An interface so a test can answer without a socket. */
interface Catalogue {
    fun model(id: String): Result<ModelInfo>
    fun endpoints(id: String): Result<List<EndpointInfo>>
}

/**
 * OpenRouter's own catalogue, over the two endpoints that carry what the picker shows.
 *
 * The app makes these calls when a picker is opened and when a model is added — never on a
 * timer, and never while a session is running, so a catalogue that is down costs a form and not
 * an agent.
 */
class OpenRouterCatalogue(private val key: () -> String?) : Catalogue {

    override fun model(id: String): Result<ModelInfo> = try {
        val text = get("/api/v1/models")
        parseModels(text, id).fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IllegalStateException("OpenRouter does not have \"$id\"")) },
        )
    } catch (e: Exception) {
        Result.failure(IllegalStateException(Secrets.explain(e, "The model list could not be read")))
    }

    override fun endpoints(id: String): Result<List<EndpointInfo>> = try {
        val text = get("/api/v1/models/$id/endpoints")
        parseEndpoints(text)
    } catch (e: Exception) {
        Result.failure(
            IllegalStateException(Secrets.explain(e, "That model's providers could not be read"))
        )
    }

    private fun get(path: String): String {
        val token = key()
            ?: throw IllegalStateException("No OpenRouter key on this account yet")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://openrouter.ai$path").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("OpenRouter answered $code")
            }
            body
        } finally {
            connection?.disconnect()
        }
    }

    companion object {

        /**
         * One model out of the catalogue, or a failure when the id is not there.
         *
         * A missing id is the normal failure for the add form — a person types an id and the app
         * says whether OpenRouter has it — so it is reported as "not there" rather than as a
         * parsing problem.
         */
        fun parseModels(text: String, id: String): Result<ModelInfo> {
            val data = JSONObject(text).optJSONArray("data") ?: return Result.failure(
                IllegalArgumentException("The catalogue has no models in it")
            )
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                if (entry.optString("id") != id) continue
                return Result.success(modelOf(entry))
            }
            return Result.failure(IllegalArgumentException("no model \"$id\""))
        }

        private fun modelOf(entry: JSONObject): ModelInfo {
            val architecture = entry.optJSONObject("architecture") ?: JSONObject()
            val reasoning = entry.optJSONObject("reasoning") ?: JSONObject()
            val top = entry.optJSONObject("top_provider") ?: JSONObject()
            val efforts = ArrayList<String>()
            reasoning.optJSONArray("supported_efforts")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let { efforts.add(it) }
                }
            }
            return ModelInfo(
                id = entry.optString("id"),
                name = entry.optString("name").ifBlank { entry.optString("id") },
                releasedAt = entry.optLong("created", 0L).takeIf { it > 0L },
                contextLength = entry.optInt("context_length", 0).takeIf { it > 0 },
                maxOutput = top.optInt("max_completion_tokens", 0).takeIf { it > 0 },
                inputModalities = strings(architecture.optJSONArray("input_modalities")),
                outputModalities = strings(architecture.optJSONArray("output_modalities")),
                intelligence = entry.optJSONObject("benchmarks")
                    ?.optJSONObject("artificial_analysis")
                    ?.optDouble("intelligence_index", Double.NaN)
                    ?.takeIf { !it.isNaN() },
                efforts = efforts,
                defaultEffort = reasoning.optString("default_effort").takeIf { it.isNotBlank() },
            )
        }

        /**
         * Every endpoint of one model, priced per million tokens.
         *
         * The prices arrive as **strings per token** (`"0.0000000784"`), and the picker shows
         * per million, because a row of `0.0000000784` is a number nobody can compare at a
         * glance. The conversion happens here, once, rather than in each row.
         */
        fun parseEndpoints(text: String): Result<List<EndpointInfo>> {
            val data = JSONObject(text).optJSONObject("data")
                ?: return Result.failure(IllegalArgumentException("The answer has no model in it"))
            val array = data.optJSONArray("endpoints")
                ?: return Result.failure(IllegalArgumentException("That model has no providers"))
            val out = ArrayList<EndpointInfo>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val tag = entry.optString("tag").ifBlank { entry.optString("provider_name") }
                if (tag.isBlank()) continue
                val pricing = entry.optJSONObject("pricing") ?: JSONObject()
                val parameters = strings(entry.optJSONArray("supported_parameters"))
                out.add(
                    EndpointInfo(
                        tag = tag,
                        providerName = entry.optString("provider_name").ifBlank { tag },
                        contextLength = entry.optInt("context_length", 0).takeIf { it > 0 },
                        maxPrompt = entry.optInt("max_prompt_tokens", 0).takeIf { it > 0 },
                        maxOutput = entry.optInt("max_completion_tokens", 0).takeIf { it > 0 },
                        priceIn = perMillion(pricing.optString("prompt")),
                        priceOut = perMillion(pricing.optString("completion")),
                        priceCache = perMillion(pricing.optString("input_cache_read")),
                        tokensPerSecond = median(entry.opt("throughput_last_30m")),
                        uptime = median(entry.opt("uptime_last_30m")),
                        tools = "tools" in parameters,
                    )
                )
            }
            if (out.isEmpty()) {
                return Result.failure(IllegalArgumentException("That model has no providers"))
            }
            return Result.success(out)
        }

        private fun strings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            return out
        }

        /** A price string per token, as dollars per million. */
        fun perMillion(raw: String): Double? {
            val value = raw.toDoubleOrNull() ?: return null
            if (value < 0.0) return null
            return value * 1_000_000.0
        }

        /**
         * The middle of a statistic, whichever shape it arrives in.
         *
         * OpenRouter answers with `{"p50":94,"p75":64,…}` on a busy endpoint and with a flat
         * number on some, and with `null` when it has seen no traffic. All three are handled
         * here so no caller has to know which one it got — and `null` stays `null`, because a
         * quiet provider is not a zero-throughput one.
         */
        fun median(raw: Any?): Double? = when (raw) {
            null, JSONObject.NULL -> null
            is Number -> raw.toDouble().takeIf { it > 0.0 }
            is JSONObject -> raw.optDouble("p50", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
            else -> null
        }
    }
}
