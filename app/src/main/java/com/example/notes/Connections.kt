package com.example.notes

/**
 * **Which meaning of "scope" is in play, and how a connection is found.**
 *
 * The operator's correction settled the shape of this: *the chat model is selected at
 * account/project/branch level, in the window that adds the thing and in that row's ⋮ afterwards; the
 * account is the widest place it can live, because chat is per identity; Settings keeps only what is
 * the app's own.* This file is the rule that makes that work without a second copy of any secret.
 *
 * ### Two records, and they inherit differently
 *
 * A connection is a **pair** — an address and the key for it — and a **choice** — the model to ask for
 * and how hard it should think. They are deliberately not one thing:
 *
 * - **The pair is atomic.** A key is bound to one host, so a project with its own address and an
 *   *inherited* key would send that key to a host it was never stored for. That is the hole the vault
 *   exists to close, one scope up.
 * - **The choice inherits field by field**, so changing only the model does not mean re-typing the
 *   key — which would put a second copy of a secret on the phone.
 *
 * ### The cap, and the one word that is not a protocol word
 *
 * A model id is *a name the server assigns*, and the names disagree: `deepseek/deepseek-v4.1-flash`
 * at one address, `deepseek-chat` at another, whatever `--served-model-name` said on somebody's vLLM.
 * So the choice is inheritable **only as far as the address is**:
 * [choiceScopes] stops at the scope that supplied the pair. A project that sets its own address must
 * set its own model, because no wider scope knows a name that address serves; a project that inherits
 * the account's address may override only the model, which is the case that matters (the same server,
 * a cheaper model on one project).
 *
 * ### Why the general scope is last, and only for embeddings
 *
 * An embedding model is a fact about the app rather than about a person: there is one vector space per
 * app, and an index built by one identity has to be readable by the next. So `llm` may be stored
 * generally — and only for the two calls that are app-wide (embedding and rerank). Chat is never
 * general, because a chat request is made *as* somebody and the log says who spent the tokens.
 */
enum class ConnKind {
    /** The token a repository is pushed, pulled and `ls-remote`d with. */
    GIT,

    /** The key a model server is asked with, and the address it is asked at. */
    LLM,
}

/**
 * One scope's connection: **an address and the key for it**, which travel together or not at all.
 *
 * For [ConnKind.GIT], [address] is the host the token is for (`github.com`) and [user] is the name a
 * commit is made as — git's own two facts, since basic auth is `user:token` at an address. For
 * [ConnKind.LLM], [address] is the base URL (`https://openrouter.ai/api/v1`) and [user] is empty,
 * because an HTTP bearer token has no username.
 *
 * **The shape travels with the pair** ([Shape]), because it is a fact about the same server: a
 * different address means a different shape, and half of one is a request that cannot be sent.
 *
 * **[shapes] is a map, not one shape, because one server answers three kinds of request** — a chat
 * completion, an embedding, a rerank — and each has its own path and its own body. One shape per
 * connection would have had to be the chat's, and then a self-hosted server would have been told how
 * to answer a chat and nothing else. A kind that is missing falls back to the app's preset for it,
 * which is exactly what a connection to a compatible server needs.
 */
data class Connection(
    val address: String,
    val secret: String,
    val user: String = "",
    val shapes: Map<ShapeKind, Shape> = emptyMap(),
    /**
     * **"The agent may spend this on its own requests to this host"** — one checkmark per kind,
     * independent, **off unless somebody ticks it**.
     *
     * Off, the entry is exactly as invisible to `auth:` as the account's own token is. On, `auth: git`
     * (or `auth: llm`) resolves to it **only for the host it is bound to**, which is the rule that
     * keeps a named secret from being a token-exfiltration primitive: the agent names the value, and
     * the vault decides where it may go ([namedConnection]).
     *
     * **Per kind rather than per entry**, because the two have different blast radii: a git token can
     * push, a model key can spend money, and a person may want the agent to ask the model server what
     * models exist without letting it push as them.
     */
    val nameable: Boolean = false,
) {
    /** The request shape for [kind]: the server's own when it was recorded, the preset otherwise. */
    fun shapeFor(kind: ShapeKind): Shape = shapes[kind] ?: Shape.preset(kind)
}

/**
 * One scope's choice: **the model and how hard it should think**, and the two numbers that come with
 * a model rather than with an address.
 *
 * [contextLength] and [maxOutput] are here for the reason `ActiveModel` already carries them: the two
 * places that build an agent have no catalogue to ask, so the numbers the picker read are written down
 * beside the choice they belong to.
 */
data class Choice(
    val model: String,
    val effort: String = "",
    val contextLength: Int? = null,
    val maxOutput: Int? = null,
    /**
     * The word a sentence uses for where this model comes from, or blank for "name the address".
     *
     * **Not a routing instruction and not asked for in any window.** It used to be one — `provider.only`
     * chose an upstream behind one fixed URL, and the context ceilings were keyed by the tag — and the
     * address is the upstream now, so the tag has no work left except being readable in a sentence. A
     * choice written by the account carries the word the account already had, so nothing a person has
     * read changes; a choice written in a row's window carries none and [providerOf] names the address.
     */
    val provider: String = "",
)

/**
 * **The word a sentence uses for the place a model came from.**
 *
 * The host, because the address *is* the upstream: `openrouter.ai`, `localhost`, `192.168.1.9`. There is
 * deliberately no table from a host to a friendlier name, because a table is the vendor-shaped thing this
 * design removed — a server a person runs on their own machine has no row in it and must not need one.
 */
fun providerOf(address: String): String = hostOfAddress(address).removePrefix("www.")

/**
 * The host [address] names, whether it was written as a host or as a base URL.
 *
 * A git connection stores the host the token is for (`github.com`) and an llm connection stores a base
 * URL (`https://openrouter.ai/api/v1`), because those are the two things the two protocols ask for. This
 * is the one place that difference is papered over, so a comparison never has to know which kind it has.
 */
fun hostOfAddress(address: String): String {
    val text = address.trim()
    return if (text.contains("://")) Secrets.hostOf(text) else text.trimEnd('/').lowercase()
}

/**
 * True when a credential stored for [stored] may be offered to [asked].
 *
 * **Whole labels, never a suffix**: `evil-github.com` must not satisfy `github.com`, and a bare
 * `endsWith` is exactly the check that passes review and fails an attacker. A subdomain does satisfy the
 * host it belongs to, which is what makes `api.github.com` work for a token stored for `github.com`.
 *
 * **One copy, in one place** — the store, the test fake and the connection lookup all ask this.
 */
fun hostCovers(stored: String, asked: String): Boolean {
    val allowed = stored.trim().lowercase()
    val wanted = asked.trim().lowercase()
    if (allowed.isEmpty() || wanted.isEmpty()) return allowed == wanted
    return wanted == allowed || wanted.endsWith(".$allowed")
}

/**
 * **The connection `auth:` may name**, or null when none may.
 *
 * The two names are the two kinds (`git`, `llm`) — not arbitrary: a name is what an article or a tool
 * argument carries, and a per-entry name would be a second namespace to keep in step with the vault's
 * own. **The host must be the one the entry is bound to**, which is the whole reason this is a
 * function rather than a field: naming a value must not choose the destination.
 */
fun namedConnection(
    name: String,
    host: String,
    kind: ConnKind,
    project: String?,
    branch: String?,
    /**
     * The row at a scope **for this kind**, or null.
     *
     * **The kind is a parameter of the question rather than a field of the row**, because a row's kind
     * is in its key and nowhere else — so a lookup that took only a scope would answer the *llm* row
     * for `auth: git`, and `namedConnection` would have returned a model key for a push. The caller is
     * the one that knows how to ask; this function is the one that decides whether the answer counts.
     */
    at: (SecretScope, ConnKind) -> Connection?,
): Connection? {
    val scope = connectionScope(project, branch) { one ->
        at(one, kind)?.let { it.nameable && hostCovers(hostOfAddress(it.address), host) } == true
    } ?: return null
    return at(scope, kind)
}

/**
 * **Which kind a name is**, or null when it is not one this vault answers to.
 *
 * The two names are the two kinds' own: an article says `auth: git` or `auth: llm`, and there is no
 * third, because a per-entry name would be a namespace to keep in step with the vault's.
 */
fun kindFor(name: String): ConnKind? = when (name.trim().lowercase()) {
    "git" -> ConnKind.GIT
    "llm" -> ConnKind.LLM
    else -> null
}

/**
 * The scopes a question about [project] and [branch] may be answered at — **narrowest first**.
 *
 * A branch only exists inside a project, so a branch with no project is not a place a value can live
 * and is skipped rather than invented.
 */
fun connectionScopes(project: String?, branch: String?): List<SecretScope> = buildList {
    if (project != null && branch != null) add(SecretScope.Branch(project, branch))
    if (project != null) add(SecretScope.Project(project))
    add(SecretScope.Account)
    add(SecretScope.General)
}

/**
 * The narrowest scope that has a connection, or null when none does.
 *
 * [has] answers "is there one here" rather than returning it, because the caller then reads the pair
 * *and* the choice from the same scope, and a resolver that returned the value would have to be asked
 * twice for the second half.
 */
fun connectionScope(
    project: String?,
    branch: String?,
    has: (SecretScope) -> Boolean,
): SecretScope? = connectionScopes(project, branch).firstOrNull(has)

/**
 * **What a turn on a worktree is asked with**: the pair's address, the model, the effort — and the
 * scope both came from, which is what says where the key is read.
 *
 * One object rather than three lambdas because the four values travel together by rule (see this file's
 * header): a key is bound to an address and a model name is only meaningful at the address that
 * assigned it. Handing them out separately is how a turn comes to ask one server's model with another
 * server's key, which is the hole [choiceScopes] exists to close.
 *
 * The key is a **function**, because a poll that finds nothing due must not decrypt one to discover it
 * has nothing to do — and a phone in a pocket is locked most of the time.
 */
data class Wired(
    /** The scope the pair came from: where the key is read, and what the screen can say. */
    val at: SecretScope,
    val active: ActiveModel,
    /** The key, or null when there is none to send. */
    val key: () -> String?,
    /** **Why** there is none, when there is none — a locked phone is not a key that was never set. */
    val keyState: () -> KeyState,
    /**
     * The request shape the connection sends, or the app's preset when it recorded none.
     *
     * A fact about the server, so it travels with the address and the key — and it is one of the
     * three kinds' shapes, chosen by the caller: a chat turn sends the chat's.
     */
    val shape: Shape = Shape.CHAT,
    /**
     * **True when the server wants no key at all**, which is a model server on this phone or on a
     * local network — the one case where "no key" is the whole credential rather than half of one.
     *
     * It is not the same fact as `key() == null`: an account whose key simply is not stored must be
     * told to type it in, and a model server on the same phone must not be told anything. The two are
     * one field apart because they are one character apart in a request — with this, the
     * `Authorization` header is absent rather than empty.
     */
    val keyless: Boolean = false,
) {
    companion object {
        /**
         * A wiring with no key behind it — what a test builds, and what the app builds for a scope whose
         * connection holds no secret.
         */
        fun of(
            active: ActiveModel,
            at: SecretScope = SecretScope.Account,
            key: () -> String? = { null },
            keyState: () -> KeyState = { KeyState.NONE },
            keyless: Boolean = false,
            shape: Shape = Shape.CHAT,
        ): Wired = Wired(at, active, key, keyState, shape, keyless)
    }
}

/**
 * **Resolves what a turn on [project] at [branch] is asked with**, narrowest first, with the choice
 * capped at the scope that supplied the pair.
 *
 * [account] is the account's own answer — the model it has chosen and the address it has chosen, which
 * is exactly `model_conf.json`'s `active`. It is deliberately not in the scoped vault: the account is
 * where those two facts already live, and a second copy is what this design avoids everywhere. So
 * [connectionAt] is asked **including** the account and answers null there, and a stored pair at branch
 * or project scope answers instead — which is the whole of "the model is chosen per project and per
 * branch".
 *
 * **[project] may be null for a turn that belongs to no worktree.** The chain then starts at the
 * account, which is the honest answer rather than an invented branch scope.
 *
 * **The chat chain stops at the account.** A general entry is the app-wide embedding key; a chat request
 * is made *as* somebody, so nothing wider may choose its model or answer its key.
 */
fun wiredFor(
    project: String?,
    branch: String?,
    account: ActiveModel,
    connectionAt: (SecretScope) -> Connection? = { null },
    choiceAt: (SecretScope) -> Choice? = { null },
    accountKey: () -> String? = { null },
    accountKeyState: () -> KeyState = { KeyState.NONE },
): Wired {
    val chain = connectionScopes(project, branch).filter { it != SecretScope.General }
    // **The account is asked like any other scope**, and its records are the floor rather than a
    // scope of their own: a row there is a person saying "not the account's own pair", and a pair at
    // branch or project scope says "not either of those". That is one rule with three stopping
    // points, where a special case for the account would have been a second rule to keep in step.
    // **A scoped pair is used only when its row is complete**, and the account is the answer otherwise —
    // so the walk always ends rather than falling off the end into an empty scope. "Complete" is an
    // address and a model: the address is the credential for this kind (there is no other record of
    // it), and a model name is only meaningful at the address that assigned it, so a project that
    // names a server and no model cannot inherit the account's model — that name belongs to another
    // server. Such a row is a half-made one, and the honest reading is that it is not in force yet.
    //
    // **A blank key is complete, though.** A model server on this phone wants no key at all, and that
    // is a whole credential rather than half of one — see [Wired.keyless].
    val found = chain.firstOrNull { scope ->
        val pair = connectionAt(scope) ?: return@firstOrNull false
        pair.address.isNotBlank() &&
            choiceScopes(project, branch, scope).firstNotNullOfOrNull(choiceAt) != null
    }
    // **Nothing found means the account's own records are the pair**, which is where they live today:
    // `model_key_` and the address in `model_conf.json`. **The distinction is the found scope and not
    // the answer's name**: a half-made row at the account is *not* in force (it has no model to go
    // with its address), so reading `connectionAt(at)` after a failed walk would have used it anyway —
    // an address from a row whose model was refused, which is the one pairing the cap forbids.
    val at = found ?: SecretScope.Account
    val stored = found?.let(connectionAt)
    val address = stored?.address?.trim()?.takeIf { it.isNotEmpty() } ?: account.baseUrl
    val choice = choiceScopes(project, branch, at).firstNotNullOfOrNull(choiceAt)

    return Wired(
        at = at,
        active = ActiveModel(
            model = choice?.model ?: account.model,
            // **The choice's own word, or the address's.** A choice written in a row's window names no
            // provider — the window asks only what the protocol defines — so the host is the honest
            // word for where the request goes, and the account's own choice keeps the word it had.
            provider = choice?.provider?.takeIf { it.isNotBlank() }
                ?: if (stored == null) account.provider else providerOf(address),
            effort = choice?.effort ?: account.effort,
            contextLength = choice?.contextLength ?: account.contextLength,
            maxOutput = choice?.maxOutput ?: account.maxOutput,
            baseUrl = address,
        ),
        key = if (stored == null) accountKey else ({ stored.secret.takeIf { it.isNotBlank() } }),
        keyState = if (stored == null) {
            accountKeyState
        } else {
            // **A secret the store already decrypted is readable by definition**, and a blank one is
            // nothing to read rather than a phone that would not unlock.
            ({ if (stored.secret.isBlank()) KeyState.NONE else KeyState.READABLE })
        },
        keyless = stored != null && stored.secret.isBlank(),
        // **The server's own shape, when it recorded one.** A connection that did not is the
        // compatible default, and the preset is field-for-field what the app sent before a shape
        // could be stored — so nothing about an ordinary connection changes here.
        shape = stored?.shapeFor(ShapeKind.CHAT) ?: Shape.CHAT,
    )
}

/**
 * Where the choice may be read from, given the scope that supplied the connection — **and no wider.**
 *
 * This is the cap: a project that has its own address may not inherit the account's model, because
 * that name was a name for *another* server. The list still runs narrowest first, so a branch may
 * override the model of the project's connection while sharing it.
 */
fun choiceScopes(project: String?, branch: String?, connectionAt: SecretScope): List<SecretScope> {
    val all = connectionScopes(project, branch)
    val at = all.indexOf(connectionAt)
    // **A scope that is not in this question's chain cannot cap it** — the caller passed an account
    // scope for a branch that has none, say — so the whole chain is the honest answer rather than an
    // empty list, which would silently mean "nothing may be chosen".
    if (at < 0) return all
    return all.take(at + 1)
}
