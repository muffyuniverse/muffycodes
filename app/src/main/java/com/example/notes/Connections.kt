package com.example.notes

/**
 * **The git credential a scope holds, and how it is found.**
 *
 * A connection is a **pair** — an address and the key for it — because a key is bound to one host: a
 * project with its own address and an *inherited* key would send that key to a host it was never
 * stored for. That is the hole the vault exists to close, one scope up, and it is why the pair is
 * atomic even though every other number in this app inherits field by field.
 *
 * ### There used to be two kinds, and the model half is gone (2026-11-10)
 *
 * `ConnKind.LLM` stored an address, a key, a model, an effort and a request shape **per scope**, and a
 * chat resolved all five by walking branch → project → account. It was deleted because the chat's
 * request form already holds every one of them: the form's `address` is where the bytes go, its `key`
 * is the *name* of a vault row, its `body` carries the model and the effort, and its `shape` is the
 * document's own. Two answers to "what is this chat asked with" was the drift §3.11.3 removed —
 * [Wired.ofForm] is now the only resolver, and a chat with no form refuses rather than inheriting a
 * model nobody chose for it.
 *
 * **What survived is the half with a safety property**: a git key follows its host, and a scope that
 * names a different host while inheriting an older token would send that token to a machine nobody
 * issued it for. A model server refuses a wrong model; a key sent to the wrong host cannot be un-sent.
 */
enum class ConnKind {
    /** The token a repository is pushed, pulled and `ls-remote`d with. */
    GIT,
}

/**
 * One scope's git connection: **an address and the token for it**, which travel together or not at
 * all.
 *
 * [address] is the host the token is for (`github.com`) and [user] is the name a commit is made as —
 * git's own two facts, since basic auth is `user:token` at an address.
 *
 * **The request shape is gone with the model half (2026-11-10).** A shape — a path, a body, a
 * dialect — is a *request form's* job (`Schema`), and a connection is not a request.
 */
data class Connection(
    /**
     * **Blank means "inherit"**, for this field like every other one.
     *
     * It reads oddly for an address, which is a credential's other half — but a scope that writes only
     * a token is a real thing to write, and an address written at every row would be a copy of the
     * parent's that stops following it.
     */
    val address: String = "",
    /** Blank means inherit — except at the widest scope, where there is nothing to inherit. */
    val secret: String = "",
    val user: String = "",
    /**
     * **"The agent may spend this on its own requests to this host"** — off unless somebody ticks it.
     *
     * Off, the entry is exactly as invisible to `auth:` as the account's own token is. On, `auth: git`
     * resolves to it **only for the host it is bound to**, which is the rule that keeps a named secret
     * from being a token-exfiltration primitive: the agent names the value, and the vault decides where
     * it may go ([namedConnection]).
     */
    val nameable: Boolean = false,
) {
    /**
     * True when this row says nothing at all — which is what "inherit" looks like once it is stored.
     *
     * The store deletes such a row rather than writing it, so a window that changed nothing leaves the
     * scope as empty as it found it. Without this, opening a window (which shows the values in force)
     * and pressing Save would copy the parent's whole credential down and freeze it.
     */
    fun wroteNothing(): Boolean =
        address.isBlank() && secret.isEmpty() && user.isBlank() && !nameable
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
): Connection? =
    resolvedConnection(project, branch, host) { at(it, kind) }?.takeIf { it.nameable }

/**
 * **The credential a worktree holds for [host]** — its own, or the one it follows.
 *
 * **Scopes are separate records and a branch contributes one field.** A branch is a worktree of the
 * same repository, so its remote, its host and the name a token is presented as are the project's; the
 * only thing it can meaningfully say differently is *which token*. A branch's row is therefore a token
 * and a checkmark, and everything else is read from the scope above it.
 *
 * **Nothing at this scope means follow, not nothing.** A branch with no token of its own spends the
 * project's, and a project with no row spends the account's — so one credential set once applies
 * everywhere, and an override is a deliberate act rather than a copy that freezes.
 *
 * **The host is checked wherever the address actually is.** A branch's token is only usable for the
 * host its own row was entered for; a row with no address of its own is a token for whatever host the
 * row above it names, which is what makes "just the token" possible without losing the binding.
 */
fun resolvedConnection(
    project: String?,
    branch: String?,
    host: String,
    at: (SecretScope) -> Connection?,
): Connection? {
    val asked = hostOfAddress(host)
    if (asked.isEmpty()) return null
    val own = scopeOfPlace(project, branch)
    // **A wider row is a whole credential**, so it is usable only when it has a token *and* names a
    // host that covers the one asked about. A row with no address is not a wider credential at all —
    // it is a branch token, which stores no host because the project above it supplies one.
    fun usable(row: Connection?): Connection? = row?.takeIf {
        it.secret.isNotBlank() && it.address.isNotBlank() &&
            hostCovers(hostOfAddress(it.address), asked)
    }
    val above = parentChain(project, branch).firstNotNullOfOrNull { usable(at(it)) }

    if (own !is SecretScope.Branch) {
        // The wider scopes are whole credentials, so their own row is the answer when it is usable.
        return usable(at(own)) ?: above
    }

    // **A branch may answer with a token and nothing else**, which is the whole point of the scope.
    // Everything except the token comes from the row above — **including the host**, which is the part
    // that must not be invented. A branch row stores no address, so the server it is for is whatever
    // the project names; accepting the token for any host asked would send it somewhere nobody
    // entered it for, which is the one mistake this app cannot undo.
    val mine = at(own)
    val token = mine
        ?.takeIf { it.secret.isNotBlank() }
        ?.takeIf {
            if (it.address.isNotBlank()) {
                hostCovers(hostOfAddress(it.address), asked)
            } else {
                // No address of its own: the host in force is the project's, so the token is for that
                // and for nothing else. `above` is null when no wider row names this host either.
                above != null
            }
        }
        ?.secret
    return when {
        // A token of its own: the branch's checkmark decides, and the rest is the project's.
        token != null -> Connection(
            address = above?.address?.ifBlank { asked } ?: asked,
            secret = token,
            user = above?.user.orEmpty(),
            nameable = mine?.nameable == true,
        )
        // No token of its own: the branch follows, grant and all.
        above != null -> Connection(
            address = above.address.ifBlank { asked },
            secret = above.secret,
            user = above.user,
            nameable = above.nameable,
        )
        // Nothing anywhere, which is a project with no credential at all.
        else -> null
    }
}

/**
 * The scopes above [project] and [branch] — **what a scope follows when it says nothing**.
 *
 * A branch follows its project; a project follows the account; the account follows nothing. Derived
 * from [connectionScopes] rather than written a second time, because a chain written twice is a chain
 * that will disagree.
 */
private fun parentChain(project: String?, branch: String?): List<SecretScope> =
    connectionScopes(project, branch).dropWhile { it != scopeOfPlace(project, branch) }.drop(1)

/**
 * **Which kind a name is**, or null when it is not one this vault answers to.
 *
 * **One name now.** `auth: llm` went with `ConnKind.LLM` (2026-11-10): a model key is a named vault
 * secret like any other (`auth: @openrouter`), not a connection the app has a kind for. What is left
 * is the one credential git itself needs, and a name that is not it is not this vault's.
 */
fun kindFor(name: String): ConnKind? = when (name.trim().lowercase()) {
    "git" -> ConnKind.GIT
    else -> null
}

/**
 * **Where a scope is, as a project and a branch** — the inverse of [scopeOfPlace].
 *
 * A window knows only the scope it is editing, and the resolution needs a place to walk from. The
 * project scope is `main`, because "this project" in this app means its main worktree: the other
 * branches are the rows *below* it, which is what makes a branch's own row the narrower one.
 *
 * The account is `null to null`, which is how a caller is told "there is no worktree in this
 * question" — so it invents no branch.
 */
/**
 * **Which scope a row's window writes to** — the inverse of [placeOf].
 *
 * `main` is the project: it is the branch a project is made of, and its own folder *is* the project's
 * tree. Any other branch sets that branch's own row.
 *
 * **Here, beside [placeOf], because the two are one rule read in two directions.** It lived in the
 * activity, where only a screen could reach it, so the store that had to ask the same question would
 * have kept a second copy — which is how a scope rule comes to be written twice and disagree.
 */
fun scopeOfPlace(project: String?, branch: String?): SecretScope = when {
    // **No project is the account**, which is the widest place a credential may live. Nothing wider
    // answers a request: a request is made *as* somebody, and the log says who spent the tokens.
    project == null -> SecretScope.Account
    branch == null || branch == AgentSession.MAIN -> SecretScope.Project(project)
    else -> SecretScope.Branch(project, branch)
}

fun placeOf(scope: SecretScope): Pair<String?, String?> = when (scope) {
    is SecretScope.Branch -> scope.project to scope.branch
    is SecretScope.Project -> scope.name to AgentSession.MAIN
    SecretScope.Account, SecretScope.General -> null to null
}

/**
 * The scopes a **credential** may be answered at, narrowest first.
 *
 * A branch only exists inside a project, so a branch with no project is not a place a value can live
 * and is skipped rather than invented.
 *
 * **`General` is deliberately absent, and it is not an omission.** A general scope means "every
 * project, every account" — right for a shared service key like npm's, and wrong for a credential that
 * can push: it would be a token with no owner, usable by a project nobody chose it for. A git
 * credential belongs to an identity, so the widest place it can live is the account.
 */
fun connectionScopes(project: String?, branch: String?): List<SecretScope> = buildList {
    if (project != null && branch != null) add(SecretScope.Branch(project, branch))
    if (project != null) add(SecretScope.Project(project))
    add(SecretScope.Account)
}

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
    /**
     * **The form this turn runs**, when the chat named one — the whole request as a document.
     *
     * Null on the older path (a scope's connection rows) and on the refusal path below. It is here
     * rather than only in the derived fields because a refusal has to be able to say *which* file was
     * named and where to pick another.
     */
    val form: Schema? = null,
    /**
     * **Why this chat cannot send anything**, when it cannot — the sentence the person reads.
     *
     * The one thing a turn cannot work around: no form, or a form that will not read. A refusal rather
     * than a fallback, because a fallback would spend the key on a model nobody chose for this chat.
     */
    val missing: String? = null,
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

        /**
         * **What a chat is asked with, from the form it names** (§3.8.3) — the pointer's path, read as
         * a document, and nothing else.
         *
         * This is the whole of the resolution: the form's address is where the bytes go, its `key` is
         * the *name* of a vault row, its `url` is the path on that address, its body is what is sent,
         * and its three numbers are the context policy. There is no inheritance and no second place to
         * look, which is what makes "the chat's form is the chat's request" a fact rather than a rule.
         *
         * **The key is resolved by its name and the form's own host.** A row is stored bound to the
         * host it was made for, so a form naming `@openrouter` for `example.com` gets nothing rather
         * than sending that key to a server it was never issued by — the vault's rule, asked through
         * the name the document carries.
         *
         * **A chat with no form refuses in words**, naming the picker: a worktree that silently ran
         * another chat's request would spend the key on a model nobody chose for it.
         */
        fun ofForm(
            ws: Workspace,
            credentials: Credentials,
            project: String,
            branch: String,
        ): Wired {
            val path = Schemas.pointer(ws.accountDir, project, branch)
            if (path == null) {
                return Wired(
                    at = SecretScope.Branch(project, branch),
                    active = ActiveModel("", ""),
                    key = { null },
                    keyState = { KeyState.NONE },
                    missing = "This chat has no request form yet, so there is nothing to send. " +
                        "Pick one with the chat's template button — forms live in " +
                        "${Schemas.REQUESTS}/${ShapeKind.CHAT.folder}/, and the format is explained " +
                        "in ${Schemas.DIR}/${ShapeKind.CHAT.folder}/${Schemas.REFERENCE}.",
                )
            }
            val form = Schemas.read(ws, path, ShapeKind.CHAT).getOrElse { broken ->
                return Wired(
                    at = SecretScope.Branch(project, branch),
                    active = ActiveModel("", ""),
                    key = { null },
                    keyState = { KeyState.NONE },
                    missing = "${broken.message} Pick another form with the chat's template button.",
                )
            }
            val name = form.key.removePrefix("@").trim()
            val host = hostOfAddress(form.address)
            // **No name is a server that wants no key** — a model on this phone — and it is said by the
            // document rather than by a checkbox: the form either names a row or it does not.
            val keyless = name.isEmpty()
            val look = { credentials.secret(project, branch, name, host) }
            val keyProblem = if (keyless || look() != null) {
                null
            } else {
                "This form names the secret @$name for $host, and this phone has no readable secret " +
                    "by that name for that host. Add it in Settings → Secrets."
            }
            return Wired(
                at = SecretScope.Branch(project, branch),
                active = ActiveModel(
                    model = form.body.optString("model"),
                    // **The effort is a field in the body, not a setting**, so the label a sentence
                    // prints is read from the same place the server reads it.
                    effort = form.body.optString("reasoning_effort"),
                    baseUrl = form.address,
                ),
                key = { if (keyless) null else look() },
                keyState = {
                    when {
                        keyless -> KeyState.NONE
                        look() != null -> KeyState.READABLE
                        else -> KeyState.NONE
                    }
                },
                shape = form.shape(ShapeKind.CHAT),
                keyless = keyless,
                limits = ContextBudget.Limits.of(
                    trigger = form.forget,
                    hard = form.trim,
                    target = form.target,
                ),
                keyProblem = keyProblem,
                form = form,
            )
        }
    }
}
