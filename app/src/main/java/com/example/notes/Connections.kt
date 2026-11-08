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
 */
data class Connection(
    val address: String,
    val secret: String,
    val user: String = "",
    val shape: Shape? = null,
)

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
)

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
