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
 * `model_conf.json` at the account root: **the chat model, and what effort it is asked for.**
 *
 * That is all that is left of it. It used to hold the embedding and rerank models too, and the
 * address they were reached at; those went with the index (§"what was removed", 2026-11-09), which
 * leaves one choice in a file of its own. §4 of the credentials plan finishes the job: the choice
 * belongs to a scope in the vault, and then this file has nothing to hold at all.
 */
data class ModelConfig(
    val active: ActiveModel,
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

    fun default(): ModelConfig = ModelConfig(active = ActiveModel(DEFAULT_MODEL, "high"))

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
        })
        // **Written only when it is not the shipped one.** A file the app writes stays the file it
        // wrote before, so an OpenRouter account's config does not sprout an address it never chose —
        // and the absence of the field keeps meaning "the default", not "empty".
        if (config.active.baseUrl != OPENROUTER_BASE) put("baseUrl", config.active.baseUrl)
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
        // **A key this build does not know is not an error.** The reader takes the fields it has and
        // says nothing about the rest, so a file edited by hand (a comment, a leftover name) loads
        // instead of refusing — refusing would make the file unopenable by the app that wrote it.

        // **A `embedding` or `rerank` block is ignored rather than refused**, and that is the one
        // place this parser is forgiving: a file written by the build before this one holds them, and
        // refusing to load it would be refusing a person's own config over a field that no longer
        // means anything.

        // **The address, if the file carries one.** Absent means the shipped default, which is what
        // every config written before this field existed means — and what a person who typed nothing
        // would expect. A value that is present must look like an address, because "openai" in this
        // field would produce a relative URL and an error nobody could act on.
        //
        // **It is the chat model's address** (`active.baseUrl`), and since §2.2 of the credentials
        // plan it is only the *fallback*: the connection in the vault names the host when it names one.
        // Reading it here keeps a hand-written file working, which is the whole reason it exists.
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
        return Result.success(ModelConfig(ActiveModel(model, effort, base.ifEmpty { OPENROUTER_BASE })))
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