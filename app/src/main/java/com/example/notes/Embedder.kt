package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * **Turning text into vectors, and reordering results by meaning.**
 *
 * Two capabilities, one interface, because they are the same kind of thing: a model the person chose
 * in settings, reached over the same key, doing something to text the app cannot do itself. `Fetcher`
 * is a sibling and follows the same rule — **an interface so the suite never opens a socket**, because
 * a feature that could only be checked by paying OpenRouter is a feature checked by hand.
 */
interface Embedder {

    /**
     * One vector per [texts], in the same order.
     *
     * **Batched in one request**, because the cost of an embedding call is dominated by the request
     * and not by the tokens: a file of twenty chunks is one round trip, not twenty.
     */
    fun embed(texts: List<String>, model: String, dims: Int): Result<List<FloatArray>>

    /**
     * [documents] reordered by how well they answer [query], best first, with the model's own score.
     *
     * **A reranker sees the query and the document together**, which a vector cannot: an embedding
     * of a paragraph has no idea what question is being asked of it. That is the whole reason the
     * design has both — the index narrows a thousand chunks to thirty, and the reranker puts the
     * right three on top.
     */
    fun rerank(
        query: String,
        documents: List<String>,
        model: String,
    ): Result<List<Ranked>>

    /** One document, its place in the order, and the model's score. */
    data class Ranked(val index: Int, val score: Double)

    companion object {

        /** No embedder, for a tool set built without one and for every test that is not about this. */
        val NONE: Embedder = object : Embedder {
            override fun embed(texts: List<String>, model: String, dims: Int): Result<List<FloatArray>> =
                Result.failure(
                    IllegalStateException(
                        "This app was not given a way to embed text, so there is no semantic index.",
                    ),
                )

            override fun rerank(query: String, documents: List<String>, model: String): Result<List<Ranked>> =
                Result.failure(
                    IllegalStateException(
                        "This app was not given a way to rerank, so results are in score order.",
                    ),
                )
        }

        /**
         * The real one, over OpenRouter's two endpoints.
         *
         * ## The endpoints are not the catalogue
         *
         * `POST /api/v1/embeddings` and `POST /api/v1/rerank` **work** for models that
         * `/api/v1/models` does not list — which is the finding the whole design turned on. So this
         * class talks to the endpoints directly and never asks the catalogue whether a model exists.
         *
         * ## The dimension is sent, not assumed
         *
         * `qwen/qwen3-embedding-8b` answers 1024, 2048 or 4096 **on demand**, so `dimensions` is a
         * request parameter. Sending it means the index stores what the person chose; omitting it
         * would give whatever the model defaults to, which is a size nobody picked.
         */
        fun openRouter(key: () -> String?): Embedder = object : Embedder {

            override fun embed(texts: List<String>, model: String, dims: Int): Result<List<FloatArray>> =
                try {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("input", JSONArray(texts))
                        // **Sent only when the person chose one.** A model that does not take the
                        // parameter rejects the request if it is present, so sending `dimensions` to
                        // every model would break the ones that do not support it.
                        if (dims > 0) put("dimensions", dims)
                    }
                    val answer = post("/api/v1/embeddings", body, key)
                    parseEmbeddings(answer, texts.size, dims)
                } catch (e: Exception) {
                    Result.failure(IllegalStateException(Secrets.explain(e, "The text could not be embedded")))
                }

            override fun rerank(query: String, documents: List<String>, model: String): Result<List<Ranked>> =
                try {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("query", query)
                        put("documents", JSONArray(documents))
                    }
                    val answer = post("/api/v1/rerank", body, key)
                    parseRerank(answer)
                } catch (e: Exception) {
                    Result.failure(IllegalStateException(Secrets.explain(e, "The results could not be reranked")))
                }
        }

        private fun post(path: String, body: JSONObject, key: () -> String?): String {
            val token = key()
                ?: throw IllegalStateException("No OpenRouter key on this account yet")
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL("https://openrouter.ai$path").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                    }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IllegalStateException("OpenRouter answered $code: ${text.take(300)}")
                }
                text
            } finally {
                connection?.disconnect()
            }
        }

        /**
         * `{"data":[{"embedding":[...],"index":0},…]}` — **sorted by `index`, not by arrival.**
         *
         * The API does not promise the answers come back in the order the inputs went out, and a
         * vector attached to the wrong chunk is a search result that reads text nobody wrote. The
         * `index` field is what makes the correspondence explicit, so it is what is used.
         */
        fun parseEmbeddings(text: String, expected: Int, dims: Int): Result<List<FloatArray>> {
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("The embeddings answer would not parse."))
            }
            val data = root.optJSONArray("data")
                ?: return Result.failure(IllegalStateException("The embeddings answer has no data."))
            val byIndex = arrayOfNulls<FloatArray>(data.length())
            for (i in 0 until data.length()) {
                val one = data.optJSONObject(i) ?: continue
                val at = one.optInt("index", i)
                val values = one.optJSONArray("embedding") ?: continue
                val vector = FloatArray(values.length())
                for (j in values.length() - 1 downTo 0) vector[j] = values.optDouble(j, 0.0).toFloat()
                if (at in byIndex.indices) byIndex[at] = vector
            }
            val out = byIndex.filterNotNull()
            if (out.size != expected) {
                return Result.failure(
                    IllegalStateException(
                        "The embedder answered ${out.size} vectors for $expected chunks.",
                    ),
                )
            }
            // **The size is checked here rather than at the index**, so the sentence names the model
            // the person chose instead of a table they never saw.
            val wrong = out.firstOrNull { dims > 0 && it.size != dims }
            if (wrong != null) {
                return Result.failure(
                    IllegalStateException(
                        "The model answered ${wrong.size} dimensions where $dims was asked for. " +
                            "It does not support that size.",
                    ),
                )
            }
            return Result.success(out)
        }

        /**
         * `{"results":[{"index":2,"relevance_score":0.93},…]}` — best first.
         *
         * **The API already orders them**, and re-sorting would be second-guessing a number the model
         * computed; the order is taken as given and the score is carried for a caller that wants it.
         */
        fun parseRerank(text: String): Result<List<Ranked>> {
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("The rerank answer would not parse."))
            }
            val results = root.optJSONArray("results")
                ?: return Result.failure(IllegalStateException("The rerank answer has no results."))
            val out = mutableListOf<Ranked>()
            for (i in 0 until results.length()) {
                val one = results.optJSONObject(i) ?: continue
                val at = one.optInt("index", -1)
                if (at < 0) continue
                out.add(Ranked(at, one.optDouble("relevance_score", 0.0)))
            }
            return Result.success(out)
        }
    }
}
