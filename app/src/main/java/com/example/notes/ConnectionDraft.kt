package com.example.notes

import org.json.JSONObject

/**
 * **What a connection window holds, and the sentences it refuses with.**
 *
 * A window's fields are text, and the rules about which text may be stored are not: they are here, as
 * one function per question, so a test can ask them without drawing anything. The same reason
 * [GitCredentials] and [TokenScopes] are separate files — and the same lesson that made the shape a
 * document rather than a lambda: a rule that can only be exercised by pressing a button is a rule
 * nobody checks.
 *
 * ### The two rules that make this window small
 *
 * 1. **A blank field writes nothing.** The absence of a local value *is* the inheritance
 *    (`PLAN-credentials` §2.2), so there is no "inherit" switch to keep in step with the text, and
 *    clearing a field returns the scope to what it had.
 * 2. **A field is only as independent as the pair allows.** An address and a model are the pair's
 *    own — a model id is a name the server assigns, so a scope that names a server must name the
 *    model that server serves. The key may be blank: a model server on this phone wants none.
 *
 * ### What the git window does *not* store
 *
 * The remote's address. It is git's own fact (`remote.origin.url`, and *Set the address* is the verb
 * that changes it), and a second copy is a thing that drifts — the same rule that made `secretHost`
 * and the model config live in one place each. What the vault keeps is the host the token is for, the
 * token, and the name a commit is made as.
 */
data class ConnectionDraft(
    val kind: ConnKind,
    /** The host for [ConnKind.GIT] (`gitlab.com`), the base URL for [ConnKind.LLM]. */
    val address: String = "",
    /** The token or key. **Blank is allowed for a model server and refused for git.** */
    val secret: String = "",
    /** The name a commit is made as — git's only. */
    val user: String = "",
    /** The model this scope asks for, or blank to inherit it. */
    val model: String = "",
    /** How hard it should think, or blank to inherit. */
    val effort: String = "",
    /** The shape editor's kind: one server answers three requests, and each has its own body. */
    val shapeKind: ShapeKind = ShapeKind.CHAT,
    /** The shape's path on the address, or blank for the app's preset. */
    val shapeUrl: String = "",
    /** The shape's body, as JSON text, or blank for the app's preset. */
    val shapeBody: String = "",
    /**
     * **May the agent spend this on its own requests to this host?**
     *
     * One checkmark per kind, independent, **off unless ticked** — and it is the only way a credential
     * the app itself uses becomes nameable by `auth:` ([Connection.nameable]). A window that defaulted
     * it on would be handing an agent a token because a row exists, which is the opposite of a grant.
     */
    val nameable: Boolean = false,
) {

    /**
     * Why this cannot be saved, or null when it can — **naming the field and what to do**.
     *
     * Every branch here is a sentence a person reads on the window, which is why the checks are in one
     * function rather than spread through the composable: the order they are asked in is the order
     * they are fixed in.
     */
    fun problemSaid(): String? = when (kind) {
        ConnKind.GIT -> gitProblem()
        ConnKind.LLM -> llmProblem()
    }

    private fun gitProblem(): String? {
        val address = address.trim()
        if (address.isEmpty()) {
            return "Say which host this token is for — for example gitlab.com. A project on another " +
                "host needs its own credential, and this is where it is kept."
        }
        // **A host, not an address.** The remote's address is git's own fact; pasting one here would
        // store a second copy of it, and the copy would be the one that went stale.
        if ('/' in address.substringAfter("://", "")) {
            return "That is an address, not a host: \"$address\". This window keeps the host a token " +
                "is for; the address belongs to the project's own remote, which is where it is set."
        }
        if (hostOfAddress(address).isEmpty()) {
            return "That does not name a host. Write one, for example gitlab.com."
        }
        if (secret.isBlank()) {
            return "A git credential is a token. Paste it — a host that wants none cannot accept a push."
        }
        return null
    }

    private fun llmProblem(): String? {
        val address = address.trim()
        // **Nothing typed at all is not an error**: the window may be open to set only a model, which
        // inherits the address and the key beside it.
        if (address.isEmpty() && model.isBlank() && shapeUrl.isBlank() && shapeBody.isBlank()) return null
        if (address.isNotEmpty() && !address.contains("://")) {
            return "An address is a URL, with its scheme: https://openrouter.ai/api/v1. Without one " +
                "there is nothing to join the request's path to."
        }
        // **The cap, said as an instruction rather than as a rule**: a scope that names a server must
        // name the model that server serves, because a model id is a name the server assigns.
        if (address.isNotEmpty() && model.isBlank()) {
            return "Say which model this server serves. A model id is a name the server assigns, so " +
                "the inherited one would be asking this server for something it does not have."
        }
        if (effort.isNotBlank() && ModelConfigFile.effortRank(effort) < 0) {
            return "\"$effort\" is not an effort the app knows. Use one of: none, minimal, low, " +
                "medium, high, max — or leave it blank to inherit."
        }
        shapeProblem()?.let { return it }
        return null
    }

    /**
     * Why the shape editor's text cannot be used, or null when it can.
     *
     * **Built and then asked**, rather than checked twice: [Shape.problem] is the one place a path is
     * refused for having a scheme, and a second copy of that rule here is a second place for it to be
     * wrong. The body is parsed first so a template that is not JSON is said to be not JSON, instead of
     * being reported as a missing placeholder.
     */
    fun shapeProblem(): String? {
        if (shapeUrl.isBlank() && shapeBody.isBlank()) return null
        if (shapeUrl.isBlank()) return "A request body needs a path to be sent to."
        val body = try {
            JSONObject(shapeBody)
        } catch (t: Throwable) {
            return "The request body is not JSON: ${t.message ?: "it could not be read"}"
        }
        return Shape(shapeUrl, body, shapeKind).problem()
    }

    /**
     * The pair to store, or null when this window is not setting one.
     *
     * **Null when the address is blank**, which is the whole of "the pair is atomic": a scope with a
     * model of its own and no address keeps the address it inherits, and its key with it.
     */
    fun connection(): Connection? {
        if (address.isBlank()) return null
        val shape = shapeProblem()?.let { null } ?: shapeOrNull()
        return Connection(
            address = if (kind == ConnKind.GIT) hostOfAddress(address) else address.trim(),
            secret = secret,
            user = if (kind == ConnKind.GIT) user.trim() else "",
            shapes = shape?.let { mapOf(shapeKind to it) } ?: emptyMap(),
            nameable = nameable,
        )
    }

    /** The choice to store, or null when nothing was typed — the absence that *is* the inheritance. */
    fun choice(): Choice? {
        if (model.isBlank()) return null
        return Choice(model = model.trim(), effort = effort.trim())
    }

    private fun shapeOrNull(): Shape? {
        if (shapeUrl.isBlank()) return null
        val body = try {
            JSONObject(shapeBody)
        } catch (t: Throwable) {
            return null
        }
        return Shape(shapeUrl.trim(), body, shapeKind)
    }

    companion object {
        /**
         * A window opened on a scope, showing **the values in force** rather than the scope's own.
         *
         * The pair's fields are filled from what the scope would use — a person changing only the
         * model should see the address and the model that are in force, not two empty boxes they have
         * to reconstruct. The *choice* is shown the same way, and that is what makes "leaving a field
         * alone writes nothing" visible: what is shown is what would happen, and what is typed is what
         * changes.
         *
         * **The secret is never filled in.** It is not read back into a field by policy — a window that
         * showed it would be a screenshot away from a leak — so an empty key box means "keep the one
         * that is here", and the sentence beside it says whether one is.
         */
        fun opening(
            kind: ConnKind,
            at: String,
            user: String = "",
            model: String = "",
            effort: String = "",
            shape: Shape? = null,
            shapeKind: ShapeKind = ShapeKind.CHAT,
            nameable: Boolean = false,
        ): ConnectionDraft = ConnectionDraft(
            kind = kind,
            address = at,
            secret = "",
            user = user,
            model = model,
            effort = effort,
            shapeKind = shapeKind,
            shapeUrl = shape?.url.orEmpty(),
            shapeBody = shape?.body?.toString().orEmpty(),
            nameable = nameable,
        )
    }
}
