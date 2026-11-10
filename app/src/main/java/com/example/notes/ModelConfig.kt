package com.example.notes

/**
 * **What a request says about itself**: the model it asks for, how hard it should think, and where it
 * goes.
 *
 * Three fields, and there used to be a fourth: `provider`, the OpenRouter endpoint tag the request was
 * pinned to. It is gone with the catalogue that published the tags — the address *is* the upstream now,
 * so a sentence that wants the word asks [providerOf] for the host.
 *
 * **This is no longer a file.** `model_conf.json` and its reader/writer lived here, and they went with
 * `ConnKind.LLM` (2026-11-10): a chat's model, effort and address belong to the **request form** it
 * picks (`Schema`), so a config file holding the same three facts was a second answer to "what is this
 * chat asked with". What survives is the value object the form is read into, and the one address the
 * app still names when nothing else does — the OpenRouter endpoint the shipped form uses.
 */
data class ActiveModel(
    val model: String,
    val effort: String,
    /**
     * **Where the requests go** — the form's own address, not the vendor's.
     *
     * This is the field that makes the app host-agnostic for models, and it is why the provider tag is
     * gone: OpenRouter's `provider.only` chose one upstream behind one fixed URL, and a base URL *is*
     * the upstream. Whatever speaks `POST /chat/completions` can be a form's address — and a person who
     * wants a particular downstream writes `"provider": {"only": [...]}` in the form's body, which is a
     * body field and therefore needs no code at all.
     */
    val baseUrl: String = OPENROUTER_BASE,
)

/** OpenRouter's base URL, which the shipped form names and the default door falls back to. */
const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
