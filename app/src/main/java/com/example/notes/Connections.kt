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
 * ### Inheritance is one field at a time, and an empty field is the inheritance
 *
 * The operator's rule (2026-11-09): *"keep a rule that empty value means that it is inherited from the
 * parent, while if lower settings scope defines it, then it overwrites - this way user will be able to
 * change only what he needs and keep the rest of the fields inherited."*
 *
 * So **every field is resolved on its own**: the address, the key, the user, each kind's shape, the
 * model, the effort, and the three compaction numbers. A branch that writes only a model gets the
 * project's address and key; a project that writes only a shape keeps the account's model. There is no
 * switch, because a switch is a second thing to keep in step with the text.
 *
 * **What this replaces, and why it is a removal rather than a preference.** The old rule made the pair
 * atomic and *capped* the choice at the scope that supplied the address, on the reasoning that a model
 * id is a name the server assigns. That cap needed a second concept — a "half-made row", which was a row
 * whose address had no model and which was therefore silently not in force — and the app twice used
 * such a row's address after refusing its model. Two rules for one question became one: **what is
 * written is used, and what is not is inherited.** A model that the new server does not serve is a
 * request that server refuses in its own words, which is the same feedback every other number now
 * gets.
 *
 * ### The one guard left, and why it is the only one
 *
 * **A key follows its host.** A key is written for the server that issued it, and a scope that names a
 * different host while inheriting an older key would send that key to a machine nobody issued it for.
 * Every other mistake here is recoverable — a server refuses a wrong model, a typo'd number produces a
 * refusal — but a key sent to the wrong host cannot be un-sent. So this is not a guard on the person's
 * numbers; it is the vault's own boundary, which is why it is the only one that survived.
 *
### Why the general scope is last, and only for embeddings
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
    /**
     * **Blank means "inherit"**, for this field like every other one.
     *
     * It reads oddly for an address, which is a credential's other half — but a scope that writes only
     * a shape, or only a key, is a real thing to write, and an address written at every row would be a
     * copy of the parent's that stops following it.
     */
    val address: String = "",
    /** Blank means inherit — except at the widest scope, where there is nothing to inherit. */
    val secret: String = "",
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
    /**
     * **True when the server wants no key at all** — a model server on this phone or on the local
     * network — and null when this row says nothing about it.
     *
     * **Nullable, because false and unset are different facts.** A blank key used to mean "keyless"
     * outright, which cannot work once a blank field means "inherit": a project that writes only a
     * model must keep the account's key, not declare the server keyless. So "wants no key" is a
     * statement somebody makes, and an unticked box is a statement nobody made.
     */
    val keyless: Boolean? = null,
) {
    /** The request shape for [kind]: this row's own when it wrote one, the app's preset otherwise. */
    fun shapeFor(kind: ShapeKind): Shape = shapes[kind] ?: Shape.preset(kind)

    /**
     * True when this row says nothing at all — which is what "inherit" looks like once it is stored.
     *
     * The store deletes such a row rather than writing it, so a window that changed nothing leaves the
     * scope as empty as it found it. Without this, opening a window (which shows the values in force)
     * and pressing Save would copy the parent's whole credential down and freeze it.
     */
    fun wroteNothing(): Boolean =
        address.isBlank() && secret.isEmpty() && user.isBlank() && shapes.isEmpty() &&
            !nameable && keyless == null
}

/**
 * One scope's choice: **the model, how hard it should think, and how big its context may get.**
 *
 * Every field is written-or-inherited on its own, like [Connection]'s. A scope that writes only the
 * trim number keeps the parent's model; one that writes only a model keeps the parent's three numbers.
 *
 * **[target], [forget] and [trim] are the operator's numbers**, and they live here rather than in the
 * general settings because they belong to a model: a 32k model and a million-token model want different
 * sizes, and the same person may use both in one account. Null means "not written", so the value in
 * force comes from the nearest scope that wrote one — down to the general settings, which are the root.
 *
 * **Nothing here is clamped and nothing is derived.** See `ContextBudget`: the app used to hold these
 * to a ceiling read from the model's window, and a number a person typed is a number they meant.
 */
data class Choice(
    val model: String = "",
    val effort: String = "",
    /** Where both hands aim: the size the context should end up at. */
    val target: Long? = null,
    /** Where the agent is asked to clear its context up. */
    val forget: Long? = null,
    /** Where the app trims the context itself. */
    val trim: Long? = null,
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
) {
    /** True when this row says nothing, which is the inheritance — see [Connection.wroteNothing]. */
    fun wroteNothing(): Boolean =
        model.isBlank() && effort.isBlank() && target == null && forget == null && trim == null &&
            provider.isBlank()
}

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
 * **Where a scope is, as a project and a branch** — the inverse of the screen's `scopeOf`.
 *
 * A window knows only the scope it is editing, and the resolution needs a place to walk from. The
 * project scope is `main`, because "this project" in this app means its main worktree: the other
 * branches are the rows *below* it, which is what makes a branch's own row the narrower one.
 *
 * The account is `null to null`, which is how [wiredFor] is told "there is no worktree in this
 * question" — its chain then starts at the account and invents no branch.
 */
fun placeOf(scope: SecretScope): Pair<String?, String?> = when (scope) {
    is SecretScope.Branch -> scope.project to scope.branch
    is SecretScope.Project -> scope.name to AgentSession.MAIN
    SecretScope.Account, SecretScope.General -> null to null
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
 * One object rather than five lambdas because the values are resolved **together and once**: the key is
 * chosen by the address in force, and the numbers are resolved with the model they belong to. Handing
 * them out separately is how the screen and the service come to hold a turn to two different sizes, or
 * how a key stored for one host gets offered to another.
 *
 * The key is a **function**, because a poll that finds nothing due must not decrypt one to discover it
 * has nothing to do — and a phone in a pocket is locked most of the time.
 */
data class Wired(
    /** The scope the address came from: what the screen names, and where the key is read. */
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
    /**
     * **The three numbers this turn is held to**, resolved with the model: a branch's own, the
     * project's, the account's, or the general settings' — field by field.
     *
     * Here rather than read from `SettingsStore` at the point of use, because the service's poll and
     * the screen's turn must not be able to disagree about how full the context may get.
     */
    val limits: ContextBudget.Limits = ContextBudget.Limits.of(trigger = 0, hard = 0),
    /**
     * **The one refusal this resolver makes, in a sentence** — set when a key was written for one host
     * and the address in force is another.
     *
     * The door puts it in place of "no model key yet", which would be the wrong instruction: there *is*
     * a key, it is simply not for this server.
     */
    val keyProblem: String? = null,
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
            limits: ContextBudget.Limits = ContextBudget.Limits.of(trigger = 0, hard = 0),
            keyProblem: String? = null,
        ): Wired = Wired(at, active, key, keyState, shape, keyless, limits, keyProblem)
    }
}

/**
 * **Resolves what a turn on [project] at [branch] is asked with**, one field at a time, narrowest first.
 *
 * The rule is the operator's: an empty field is inherited, a written one overwrites it. So this walks
 * branch → project → account and takes, for each field independently, the first scope that wrote it;
 * anything nobody wrote falls through to [account] (the account's own records: `model_key_` and
 * `model_conf.json`) and, for the three numbers, to [general] — the general settings, which are the root.
 *
 * **[project] may be null for a turn that belongs to no worktree.** The chain then starts at the
 * account, which is the honest answer rather than an invented branch scope.
 *
 * **The chat chain stops at the account.** A general entry is the app-wide embedding key; a chat request
 * is made *as* somebody, so nothing wider may choose its model or answer its key.
 *
 * ### What is deliberately not here
 *
 * Any check that a written address is accompanied by a written model, or that a model belongs to the
 * server it is sent to. Both were rules once, and both are the server's to refuse: it knows which names
 * it serves and says so in its own words. The only composition this still refuses is a key and a host,
 * because — see this file's header — that is the one mistake that cannot be undone by a refusal.
 */
fun wiredFor(
    project: String?,
    branch: String?,
    account: ActiveModel,
    general: ContextBudget.Limits = ContextBudget.Limits.of(trigger = 0, hard = 0),
    connectionAt: (SecretScope) -> Connection? = { null },
    choiceAt: (SecretScope) -> Choice? = { null },
    accountKey: () -> String? = { null },
    accountKeyState: () -> KeyState = { KeyState.NONE },
): Wired {
    // **Read each scope once**, so a field-wise walk cannot see two versions of the same row.
    val scopes = connectionScopes(project, branch).filter { it != SecretScope.General }
    val rows = scopes.map { Triple(it, connectionAt(it), choiceAt(it)) }

    fun <T : Any> first(what: (Connection?, Choice?) -> T?): T? =
        rows.firstNotNullOfOrNull { what(it.second, it.third) }

    fun addressFrom(from: Int): String =
        rows.drop(from).firstNotNullOfOrNull {
            it.second?.address?.trim()?.takeIf(String::isNotEmpty)
        } ?: account.baseUrl

    // The address's own scope is remembered rather than the answer's name: the screen says which scope
    // supplied the server, and a value that fell through to the account must not be reported as local.
    val addressAt = rows.indexOfFirst { it.second?.address?.trim()?.isNotEmpty() == true }
    val address = addressFrom(0)

    /**
     * **The key, and its host.** The narrowest row that wrote one wins — and it is used only while the
     * address *that row would send to* (resolved from that scope upward, not the turn's own chain, so a
     * narrower address cannot make an older key look wrong) names the same host.
     *
     * **The account's own key is held to the same rule.** It is not a row in the vault — it is
     * `model_key_`, and the address it was made for is `model_conf.json`'s — so the chain's last step is
     * that pair rather than nothing. Without this, a project that pointed at another server and wrote
     * no key would have sent the account's key to that server, which is the leak the whole rule is for.
     */
    val keyVertex = rows.indexOfFirst {
        it.second?.let { row -> !row.secret.isNullOrEmpty() || row.keyless == true } == true
    }
    val keyRow = if (keyVertex < 0) null else rows[keyVertex].second
    val keyless = keyRow?.keyless == true
    val asksHost = hostOfAddress(address)
    // **The host the key in force belongs to**: the key row's own address, or the account's pair when
    // no row wrote a key at all.
    val keyHost = when {
        keyRow == null -> hostOfAddress(account.baseUrl)
        else -> hostOfAddress(addressFrom(keyVertex))
    }
    val keyHostProblem: String? = when {
        keyless -> null
        keyHost == asksHost -> null
        else ->
            // **Two hosts are named**, because the fix depends on which one is wrong: the address may be
            // a typo, or the key may be from another server, or this scope may want its own.
            "A key is stored for $keyHost, and this address is $asksHost. A key follows the host it " +
                "was made for, so write this server's key here — or tick \"no key\" if it wants none."
    }

    val model = first { _, choice -> choice?.model?.takeIf(String::isNotEmpty) } ?: account.model
    val effort = first { _, choice -> choice?.effort?.takeIf(String::isNotEmpty) } ?: account.effort

    return Wired(
        at = if (addressAt < 0) SecretScope.Account else scopes[addressAt],
        active = ActiveModel(
            model = model,
            // **The choice's own word, or the address's.** A choice written in a row's window names no
            // provider — the window asks only what the protocol defines — so the host is the honest
            // word for where the request goes, and the account's own choice keeps the word it had.
            provider = first { _, choice -> choice?.provider?.takeIf(String::isNotBlank) }
                ?: if (addressAt < 0) account.provider else providerOf(address),
            effort = effort,
            baseUrl = address,
        ),
        key = when {
            keyless -> ({ null })
            // **A key for another host is not offered at all**, rather than sent and refused: the
            // refusal would be the server's to make, but the leak would already have happened.
            keyHostProblem != null -> ({ null })
            keyRow != null -> ({ keyRow.secret.takeIf(String::isNotEmpty) })
            else -> accountKey
        },
        keyState = if (keyRow == null) {
            accountKeyState
        } else {
            // **A secret the store already decrypted is readable by definition**, and a blank one is
            // nothing to read rather than a phone that would not unlock.
            ({ if (keyRow.secret.isEmpty()) KeyState.NONE else KeyState.READABLE })
        },
        keyless = keyless,
        // **The server's own shape, when it recorded one.** A connection that did not is the
        // compatible default, and the preset is field-for-field what the app sent before a shape
        // could be stored — so nothing about an ordinary connection changes here.
        shape = first { row, _ -> row?.shapes?.get(ShapeKind.CHAT) } ?: Shape.CHAT,
        // **Each number on its own**, which is what lets a branch change the trim and keep the rest.
        limits = ContextBudget.Limits.of(
            trigger = first { _, choice -> choice?.forget } ?: general.triggerAt,
            hard = first { _, choice -> choice?.trim } ?: general.hardAt,
            target = first { _, choice -> choice?.target } ?: general.targetAt,
        ),
        keyProblem = keyHostProblem,
    )
}
