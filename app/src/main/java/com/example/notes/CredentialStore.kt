package com.example.notes

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **Which of the vault's scopes a value lives in** (§13c.4, extended by `PLAN-credentials`).
 *
 * This is a type rather than a `String?` because **three scopes cannot be told apart by null**: the old
 * signature used null for "all projects" (the account), and adding general would have needed a second
 * sentinel — a magic project name, or an empty string — which is exactly the kind of
 * indistinguishable-in-code mistake the vault's host rule exists to prevent.
 *
 * It lives beside the store rather than beside the screen because **the store is what answers a row's
 * scope**, and a screen that computed it from three separate lookups could disagree with the value it
 * was about to delete.
 */
sealed interface SecretScope {
    /** Every project on **this account**. The default. */
    data object Account : SecretScope

    /** Every project, **whatever the account** — the shared service key. */
    data object General : SecretScope

    /** One project only. */
    data class Project(val name: String) : SecretScope

    /**
     * **One branch of one project** — the narrowest scope there is.
     *
     * A branch is a working tree of its own (§13d), and the operator's reason for this scope is
     * concrete: a fine-grained token the host limits to one branch is a credential a *branch* holds,
     * and two branches of one project routinely need not be allowed the same powers. The project scope
     * cannot express that: it is one value per name.
     */
    data class Branch(val project: String, val branch: String) : SecretScope
}

/**
 * **The scope a stored key belongs to, read from the key itself.**
 *
 * Every scoped row this app keeps has its scope *in* its key, so the scope does not need a second
 * record - and, more important, four hand-written prefix filters over the same key space is how a
 * deletion came to take a sibling's row. One parse, used by the listing and by both sweeps, means the
 * question "which rows does this project own" has one answer.
 *
 * The formats, all with percent-encoded parts and `/` as the separator, so a part called `a/b` cannot
 * be read as two parts:
 *
 * ```
 * secret_general_<name>                        SecretScope.General
 * secret_<login>_<name>                        SecretScope.Account
 * secret_<login>_project_<p>/<name>            SecretScope.Project(p)
 * secret_<login>_branch_<p>/<b>/<name>         SecretScope.Branch(p, b)
 * conn_general_<kind>                          SecretScope.General
 * conn_<login>_project_<p>/<kind>[_choice]     SecretScope.Project(p)
 * conn_<login>_branch_<p>/<b>/<kind>[_choice]  SecretScope.Branch(p, b)
 * ```
 *
 * Null for anything else — the active login, the logins list, a token, a model key — which is what
 * makes it safe to run over the whole store.
 */
fun keyScope(key: String, login: String?): SecretScope? {
    fun decode(encoded: String): String? = try {
        java.net.URLDecoder.decode(encoded, "UTF-8")
    } catch (e: Exception) {
        null
    }
    /** The first [count] encoded parts of [rest], or null when there are not that many. */
    fun parts(rest: String, count: Int): List<String>? {
        val all = rest.split('/')
        if (all.size < count + 1) return null
        return all.take(count).map { decode(it) ?: return null }
    }

    if (key.startsWith("secret_general_")) return SecretScope.General
    if (key.startsWith("conn_general_")) return SecretScope.General
    if (login == null) return null
    return when {
        key.startsWith("secret_${login}_project_") ->
            parts(key.removePrefix("secret_${login}_project_"), 1)?.let { SecretScope.Project(it[0]) }
        key.startsWith("secret_${login}_branch_") ->
            parts(key.removePrefix("secret_${login}_branch_"), 2)?.let { SecretScope.Branch(it[0], it[1]) }
        key.startsWith("conn_${login}_project_") ->
            parts(key.removePrefix("conn_${login}_project_"), 1)?.let { SecretScope.Project(it[0]) }
        key.startsWith("conn_${login}_branch_") ->
            parts(key.removePrefix("conn_${login}_branch_"), 2)?.let { SecretScope.Branch(it[0], it[1]) }
        // **The account's own rows last**, because a project or branch key also starts with
        // `secret_<login>_`, and the branches above have already taken those. The underscore after
        // the login is what keeps `dev` and `dev2` apart.
        key.startsWith("secret_${login}_") -> SecretScope.Account
        else -> null
    }
}

/**
 * **One row of the vault, addressed by what it is rather than by what it is called.**
 *
 * A name is not unique across scopes — one project can hold `stripe` while the account holds another
 * — so a row that carried only a name could not say which value Remove would delete, and the window
 * deleted the account's whatever the person was looking at. The scope and the host are read from the
 * same key the value came from, so they cannot drift from the thing they describe.
 */
data class SecretRow(
    val name: String,
    /** The host it may be used at, or empty when it is not sent anywhere. */
    val host: String,
    val scope: SecretScope,
)

/**
 * The keys of the stored accounts, as the rest of the app sees them.
 *
 * It is an interface so a test can put an account on a phone without a keystore:
 * the real one is [CredentialStore], which encrypts every key with a key from the
 * Android keystore.
 */
interface Credentials {

    /** The login of the active account, or null when nobody is signed in. */
    var activeLogin: String?

    /** The logins of the accounts stored on this phone. */
    fun logins(): List<String>

    /** The key of [login], or null when this phone has none. */
    fun token(login: String): String?

    /** Keeps [token] for [login]. */
    fun setToken(login: String, token: String)

    /** Forgets the key of [login]. */
    fun forget(login: String)

    /** True when a key is stored for [login]. */
    fun hasToken(login: String): Boolean

    /**
     * The OpenRouter key of [login], or null when this phone has none.
     *
     * **Per account, like the GitHub token**, and for the same reason: it is the identity the
     * app acts as. A second secret in the same store and behind the same door — typed on a
     * screen that refuses screenshots, encrypted with the same keystore key, scrubbed out of
     * every message, and kept out of backups by the same rules.
     */
    fun modelKey(login: String): String?

    /**
     * What the phone can say about the key of [login], without printing it.
     *
     * **Three answers, and the difference between the last two is the whole point.** A key that is
     * *there* but cannot be read right now — because the phone is locked, and the store's own key is
     * only usable while it is unlocked — is not the same thing as a key that is not there. Collapsing
     * them made the app delete a good key on the first locked poll and then say, for ever after, that
     * there had never been one.
     */
    fun modelKeyState(login: String): KeyState =
        if (modelKey(login) != null) KeyState.READABLE else KeyState.NONE

    /** Keeps [key] for [login]. */
    fun setModelKey(login: String, key: String)

    /**
     * A **named** secret of the active account, or null when the phone has none.
     *
     * The operator's design has an MCP article naming its token rather than holding it —
     * `token: linear` — because an article is in git and syncs to GitHub, so *"an article with a token in it
     * is a leaked token in a repository, which is the one failure mode of this whole feature that cannot be
     * undone."* This is where the name resolves.
     *
     * **A third kind of secret in the same store, behind the same door**, for the same reasons as the other
     * two: encrypted with the keystore key, unreadable while the phone is locked, scrubbed out of every
     * message. It is not a fourth mechanism.
     *
     * **The [host] is part of the question, not part of the name.** A name alone cannot say where its value
     * may go, so `secret("github")` would let the agent send the token anywhere by naming it: the tool that
     * resolves the name chooses the destination, and the vault has no say. Passing the destination makes the
     * rule unskippable — there is no way to ask for a secret without saying where it is going.
     *
     * **An empty [host] means "this is not being sent anywhere"**, which is a real kind of secret: the key
     * that decrypts a CI log is read by this app and never travels. The binding is two-way, so the two kinds
     * cannot be confused: a secret stored for a host is never answered for `""`, and one stored for `""` is
     * never answered for a host. Otherwise a decryption key could be handed to a server by naming it, which is
     * the same hole in a different door.
     *
     * **Null for any host the secret was not stored for**, including a look-alike: the match is on whole
     * labels, so `api.github.com` does not satisfy `github.com` and `evil-github.com` satisfies neither.
     *
     * A default of null rather than an abstract method, so a store that has never heard of named secrets —
     * every fake in the suite — compiles and answers honestly.
     */
    fun secret(name: String, host: String): String? = null

    /**
     * [name]'s secret for [project], falling back to the account-wide one.
     *
     * **Project first, then account.** A project that needs a different key for the same service can
     * say so without renaming anything, and a key several projects share is written once.
     */
    /**
     * **Branch, then project, then account, then general — narrowest first.**
     *
     * [branch] is only meaningful inside [project]; a branch is a working tree of a project, so a
     * branch with no project is not a place a value can live and the parameter is ignored.
     */
    fun secret(project: String?, branch: String?, name: String, host: String): String? =
        secret(project, name, host)

    /**
     * **The shorthand for a caller that has no branch in hand**: the project's own value, then the
     * account's, then the shared one.
     *
     * **Not `secret(name, host)`**, which is the account alone. Delegating to that was a silent narrowing
     * of every project question — a store with a project secret answered "nothing" for it, and six
     * tests about scope said so before this line was read carefully.
     */
    fun secret(project: String?, name: String, host: String): String? =
        secret(project, null, name, host)

    /**
     * A secret **every project can read, whatever the account** (§13c.4).
     *
     * A general secret is a fact about the app rather than about the identity signed in: the same
     * `stripe` key for every project, whether it was made under one account or another. It is looked up
     * **last**, after the project's own and the account's, so a narrower scope always wins.
     */
    fun generalSecret(name: String, host: String): String? = null

    /** Keeps a general secret. See [generalSecret]. */
    fun setGeneralSecret(name: String, value: String, host: String) {}

    /** The names of the general secrets, **never their values**. */
    fun generalSecretNames(): List<String> = emptyList()

    /** Forgets a general secret. */
    fun forgetGeneralSecret(name: String) {}

    /** Keeps [value] as [name]'s secret for the active account, usable **only** at [host]. */
    fun setSecret(name: String, value: String, host: String) {}

    /** Keeps [value] for [project] only when [project] is not null; otherwise account-wide. */
    fun setSecret(project: String?, name: String, value: String, host: String) =
        setSecret(name, value, host)

    /**
     * **The only writer that says which row it is writing.** Every other `setSecret` above is a
     * shorthand for one scope, kept because the scope they mean is unambiguous.
     *
     * The screen holds a [SecretScope] — it was chosen from a list — so this is the form it calls, and
     * a scope the store does not know is a compile error rather than a value written to the wrong key.
     */
    fun setSecret(scope: SecretScope, name: String, value: String, host: String) {
        when (scope) {
            SecretScope.General -> setGeneralSecret(name, value, host)
            SecretScope.Account -> setSecret(name, value, host)
            is SecretScope.Project -> setSecret(scope.name, name, value, host)
            is SecretScope.Branch -> setBranchSecret(scope.project, scope.branch, name, value, host)
        }
    }

    /**
     * **The connection a narrower scope puts in front of the account's** — the pair, or null.
     *
     * The account is not answered here, and that is the point rather than an omission: the account's
     * own pair already lives in one record each (`token_<login>` with [Account.host] and [Account.gitUser]
     * for git, `model_key_<login>` with the address in `model_conf.json` for llm). A vault that kept a
     * second copy would be two records of one secret, which is the drift this whole design avoids —
     * so the caller supplies the account's, and this store answers only the scopes *below* it.
     */
    fun connection(scope: SecretScope, kind: ConnKind): Connection? = null

    /** Keeps a connection for [scope]. See [connection]. */
    fun setConnection(scope: SecretScope, kind: ConnKind, connection: Connection) {}

    /** Forgets a scope's connection, if it has one. */
    fun forgetConnection(scope: SecretScope, kind: ConnKind) {}

    /**
     * The git connection that may be offered to [host], or null.
     *
     * A default that answers null, so a store with no vault — every fake in the suite — keeps working
     * and cannot silently send an unscoped key.
     */
    fun gitConnectionFor(project: String?, branch: String?, host: String): Connection? = null

    /**
     * **What `auth: <name>` resolves to in the scoped vault** — the checkmarked connection, or null.
     *
     * One function rather than a branch at each call site, because this is the door the checkmark has
     * to hold: a tool that looked a connection up itself could forget to ask whether the agent was
     * granted it. A default that answers null, so a store that has never heard of checkmarks cannot
     * hand one out.
     */
    fun spendable(name: String, host: String, project: String?, branch: String?): String? {
        val kind = kindFor(name) ?: return null
        if (host.isBlank()) return null
        return namedConnection(name, host, kind, project, branch) { connection(it, kind) }
            ?.secret
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The model and effort [scope] asks for, or null when it asks for nothing and inherits.
     *
     * **Separate from [connection] because it inherits separately** (see `Connections.kt`): the pair is
     * atomic, the choice is not. Written per field so "inherit the key, choose my own model" is one
     * record rather than a copy of a secret.
     */
    fun choice(scope: SecretScope, kind: ConnKind): Choice? = null

    /** Keeps a scope's choice. A blank model means "inherit", so the row is removed. */
    fun setChoice(scope: SecretScope, kind: ConnKind, choice: Choice) {}

    /**
     * Keeps [value] for one branch of [project].
     *
     * A default that writes nothing, so a store which does not keep branch rows cannot silently write
     * the value somewhere wider — which is the failure a nullable project argument already produced
     * once, when "general" and "no project" were the same `null`.
     */
    fun setBranchSecret(project: String, branch: String, name: String, value: String, host: String) {}

    /** Every branch of [project] a secret is stored for, so deleting a branch can take them too. */
    fun forgetBranchSecrets(project: String, branch: String): Int = 0

    /**
     * Forgets every secret scoped to [project], answering how many went.
     *
     * **Called when a project is deleted**, before its folder is. A secret whose project no longer
     * exists is a row nothing can ever read, and leaving it would be a leak nobody can see.
     */
    fun forgetProjectSecrets(project: String): Int = 0

    /**
     * The names of this account's secrets, **never their values**, so a person (and the agent) can see what
     * exists without learning anything that could be used.
     *
     * Empty rather than throwing, for the same reason as the other defaults.
     */
    fun secretNames(): List<String> = emptyList()

    /**
     * **Every row, with the scope and host each value really has.**
     *
     * The window draws these rather than names, because a name alone could not say which of two values
     * with that name a person is looking at — or which one Remove would delete. The general scope is
     * included: it used to be filtered out of [secretNames], so a general secret could be created and
     * then never seen, rotated or removed.
     *
     * Empty rather than throwing, like the rest: a store that keeps no named secrets keeps no rows.
     */
    fun secretRows(): List<SecretRow> = emptyList()

    /**
     * Forgets [name] **in [scope]**, so a name held twice loses only the row that was pressed.
     *
     * There is no name-only form on purpose: that is the call that removed the account's value while
     * the person was looking at a project's, and reported nothing.
     */
    fun forgetSecret(name: String, scope: SecretScope) {}

    /** The host [name] may be used at, or null when there is no such secret. Never the value. */
    fun secretHost(name: String): String? = null
}

/**
 * The order two rows with one name are drawn in: the widest first.
 *
 * **Widest first, because that is the one a person is usually surprised by.** "This account" and "all
 * projects, every account" are the rows that can be forgotten only by looking, while a project's is
 * the one somebody added on purpose.
 *
 * A function here rather than in the screen or the store, so the real vault and the fake order rows
 * the same way — a test that asserted the order of two same-named rows would otherwise be asserting
 * the fake's opinion.
 */
fun scopeOrder(scope: SecretScope): Int = when (scope) {
    SecretScope.General -> 0
    SecretScope.Account -> 1
    is SecretScope.Project -> 2
    is SecretScope.Branch -> 3
}

/**
 * How a scope reads on a row, in a refusal, and in the window that removes it.
 *
 * **Each is its own sentence**, because they are different facts and a person deciding where a key
 * should live — or reading why one was not sent — has to be able to tell them apart at a glance.
 */
fun scopeLabel(scope: SecretScope): String = when (scope) {
    SecretScope.Account -> "this account"
    SecretScope.General -> "all projects, every account"
    is SecretScope.Project -> "only ${scope.name}"
    is SecretScope.Branch -> "only ${scope.project}/${scope.branch}"
}

/** The three things a store can say about a stored key. */
enum class KeyState {
    /** Nothing is stored for this account. */
    NONE,

    /** Stored, and readable now. */
    READABLE,

    /** Stored, but the phone must be unlocked before it can be read. */
    LOCKED,

    /**
     * Stored, and the app could not read it for a reason it does not recognise.
     *
     * **This is its own answer, and reporting it as [NONE] was a lie the screen repeated.** The
     * unrecognised branch keeps the bytes on purpose — "an unrecognised failure is not proof the key
     * is gone" — and then answered `NONE`, which the agent block renders as *"No OpenRouter key on
     * this account yet"*. So a key that was stored, and may well work on the next read, was reported
     * as a key that had never been set. Reported twice, the second time after it had been "fixed":
     * *"still tells 'no openrouter key on this account yet' in agent block after setting the key"*.
     *
     * The two are different sentences because they are different situations, and a person can act on
     * only one of them: `NONE` means "type it in", this means "it is here and something is wrong with
     * reading it".
     */
    UNREADABLE,
}

/**
 * When a stored key is **gone** rather than merely unreadable.
 *
 * A ciphertext that will not open *with the key that encrypted it* is dead: the keystore entry was
 * invalidated (a lock-screen change, a restore onto another phone) or the bytes were damaged.
 * Nothing can bring it back, so keeping it would mean a row that says a key is stored and an agent
 * that can never use it.
 *
 * Everything else is kept. A locked phone is the ordinary case, not a failure — the agent polls with
 * the screen off, which is exactly when the store refuses — and an error nobody recognises is not
 * evidence of death either. **The destructive answer has to be earned**: deleting a credential
 * because of an exception whose meaning is unknown is how a person loses a key they typed a minute
 * ago.
 */
object KeyVerdict {

    /** True when [error] means the ciphertext can never be opened again. */
    fun isDead(error: Throwable): Boolean = chain(error).any { e ->
        // `AEADBadTagException` is a `BadPaddingException`; both mean the bytes did not open.
        e is javax.crypto.AEADBadTagException ||
            e is javax.crypto.BadPaddingException ||
            e is android.security.keystore.KeyPermanentlyInvalidatedException
    } || chain(error).any { e ->
        // The class name arrives as one word when the failure is reported as text.
        val said = message(e)
        "permanently invalidated" in said || "permanentlyinvalidated" in said
    }

    /** True when [error] means the phone has to be unlocked first. */
    fun isLocked(error: Throwable): Boolean = chain(error).any { e ->
        e is android.security.keystore.UserNotAuthenticatedException ||
            message(e).let { m ->
                "not authenticated" in m || "device is locked" in m || "device locked" in m
            }
    }

    private fun chain(error: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        var e: Throwable? = error
        while (e != null && out.size < 8) {
            out.add(e)
            e = e.cause
        }
        return out
    }

    private fun message(e: Throwable): String =
        (e.javaClass.simpleName + " " + (e.message ?: "")).lowercase()
}

/**
 * Where the keys of the stored accounts are kept.
 *
 * Every account has its own token, stored encrypted with a key from the Android
 * keystore: the key never leaves the keystore, so a token cannot be read without
 * the phone, and only while it is unlocked. The login names are not secret and
 * are stored in plain text — they name the account, they do not open it.
 */
class CredentialStore(context: Context) : Credentials {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The login of the active account, or null when nobody is signed in. */
    override var activeLogin: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value)
            }.apply()
        }

    /** The logins of the accounts stored on this phone, in the order added. */
    override fun logins(): List<String> =
        prefs.getString(KEY_LOGINS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** The token of [login], or null when there is none or it cannot be read. */
    override fun token(login: String): String? {
        val stored = prefs.getString(tokenKey(login), null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // The keystore key is gone (a factory reset, for instance): drop the
            // unreadable token rather than keeping a broken one.
            prefs.edit().remove(tokenKey(login)).apply()
            null
        }
    }

    /** Stores the token of [login], replacing any earlier one. */
    override fun setToken(login: String, token: String) {
        prefs.edit().putString(tokenKey(login), encrypt(token)).apply()
        val known = logins()
        if (login !in known) {
            prefs.edit().putString(KEY_LOGINS, (known + login).joinToString("\n")).apply()
        }
    }

    /** Removes [login] and its token from this phone. */
    override fun forget(login: String) {
        // **The named secrets go with the account.** A key that reached a server is that account's, and a
        // signed-out account leaving its MCP tokens on the phone would be a leak the person believes they
        // closed.
        // **The connections go with the account too** — a project's own address and key are that
        // account's rows, and leaving them would leave a key on the phone for an identity that is
        // gone. The general scope is deliberately *not* matched: it has no login in its key, because
        // it belongs to the app.
        val mine = prefs.all.keys.filter {
            it.startsWith("secret_${login}_") || it.startsWith("conn_${login}_")
        }
        prefs.edit()
            .remove(tokenKey(login))
            .remove(modelKeyKey(login))
            .also { edit -> mine.forEach { edit.remove(it) } }
            .putString(KEY_LOGINS, logins().filter { it != login }.joinToString("\n"))
            .apply()
        if (activeLogin == login) activeLogin = null
    }

    override fun modelKey(login: String): String? = opened(login).text

    override fun modelKeyState(login: String): KeyState = opened(login).state

    /**
     * Reads the stored key, and **decides what a failure means before it decides what to do**.
     *
     * The old version had one `catch (e: Exception)` that deleted the key and returned null, which
     * made three unrelated things look the same: the phone being locked (ordinary — the agent polls
     * with the screen off), the key being dead (real, and only then should the bytes go), and a
     * failure nobody understands (not evidence of anything). It also meant the app *destroyed* a key
     * on the strength of the locked case, which is the worst of the three.
     */
    private fun opened(login: String): Opened {
        val stored = prefs.getString(modelKeyKey(login), null) ?: return Opened(null, KeyState.NONE)
        return try {
            Opened(decrypt(stored), KeyState.READABLE)
        } catch (e: Exception) {
            if (KeyVerdict.isDead(e)) {
                // It cannot come back, so it is dropped — the same rule as the GitHub token.
                prefs.edit().remove(modelKeyKey(login)).apply()
                Opened(null, KeyState.NONE)
            } else if (KeyVerdict.isLocked(e)) {
                // Kept. It opens as soon as the phone does.
                Opened(null, KeyState.LOCKED)
            } else {
                // Kept as well: an unrecognised failure is not proof the key is gone, and a person
                // who types it again overwrites the bytes anyway.
                //
                // **And it says so**, rather than answering `NONE`. The bytes are still there, so
                // "no key yet" is false, and it was the sentence the operator saw twice.
                Opened(null, KeyState.UNREADABLE)
            }
        }
    }

    private data class Opened(val text: String?, val state: KeyState)

    override fun setModelKey(login: String, key: String) {
        prefs.edit().putString(modelKeyKey(login), encrypt(key)).apply()
    }

    /**
     * A named secret of the active account — `token: linear` in an MCP article resolves here.
     *
     * **Per account, and the account is the active one**, because that is the identity the app acts as and
     * the key that reaches a server belongs to it. The name is not a path: a secret called `../x` is a key
     * in this store like any other, and nothing is opened on disk.
     *
     * **The host is checked here rather than by every caller**, because a caller that forgot would be the
     * hole this signature exists to close. A secret stored for `api.github.com` answers nothing when asked
     * for `evil.example`, and the answer is null rather than an exception: the caller is a tool that says
     * "there is no secret for that host" and stops, which is what a person needs to hear.
     */
    override fun secret(name: String, host: String): String? {
        val login = activeLogin ?: return null
        val stored = prefs.getString(secretKey(login, name), null) ?: return null
        // **The host comes back with the value**, from the same string, so they cannot drift apart.
        val (allowed, encrypted) = splitHost(stored) ?: return null
        if (!hostMatches(host, allowed)) return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            // **Kept rather than dropped**, unlike the GitHub token: a locked phone is ordinary here too,
            // and the caller is a tool that can say "that secret could not be read" and try again.
            null
        }
    }

    override fun setSecret(name: String, value: String, host: String) {
        val login = activeLogin ?: return
        val packed = host.trim().lowercase() + HOST_SEPARATOR + encrypt(value)
        prefs.edit().putString(secretKey(login, name), packed).apply()
    }

    /**
     * **Branch, then project, then account, then general — narrowest first** (§13c.4).
     *
     * Each step is a deliberate shadow: a branch that needs a different key says so by storing one, a
     * project that needs a different key from the account's does the same, and so on. **General is
     * last** because it is the widest, and a narrower scope that exists must win or it would be
     * impossible to override the shared key for one branch.
     *
     * **A narrower scope bound to a *different host* does not block the wider one.** It says nothing
     * about this host, so the fallback is right — and it is what lets a project hold a key for
     * `api.acme.test` while the account's key still answers for `api.github.com`. The sentence a
     * refusal gives is built from [secretRows], which can say where the value really is.
     *
     * **General is consulted even with no account signed in**, which is the one place this differs
     * from the other three: it is not the account's secret to hide.
     */
    override fun secret(project: String?, branch: String?, name: String, host: String): String? {
        val login = activeLogin
        if (login != null) {
            if (project != null && branch != null) {
                val scoped = prefs.getString(branchSecretKey(login, project, branch, name), null)
                if (scoped != null) openedAt(scoped, host)?.let { return it }
            }
            if (project != null) {
                val scoped = prefs.getString(projectSecretKey(login, project, name), null)
                if (scoped != null) openedAt(scoped, host)?.let { return it }
            }
            val account = prefs.getString(secretKey(login, name), null)
            if (account != null) openedAt(account, host)?.let { return it }
        }
        return generalSecret(name, host)
    }

    override fun setSecret(project: String?, name: String, value: String, host: String) {
        val login = activeLogin ?: return
        val packed = host.trim().lowercase() + HOST_SEPARATOR + encrypt(value)
        val key = if (project == null) secretKey(login, name) else projectSecretKey(login, project, name)
        prefs.edit().putString(key, packed).apply()
    }

    override fun setBranchSecret(
        project: String,
        branch: String,
        name: String,
        value: String,
        host: String,
    ) {
        val login = activeLogin ?: return
        val packed = host.trim().lowercase() + HOST_SEPARATOR + encrypt(value)
        prefs.edit().putString(branchSecretKey(login, project, branch, name), packed).apply()
    }

    /**
     * **Deleting a branch takes its secrets with it**, for the reason [forgetProjectSecrets] gives: a
     * row whose branch no longer exists is a row nothing can read, and a credential nobody can see is
     * one nobody can revoke from here.
     */
    override fun forgetBranchSecrets(project: String, branch: String): Int {
        val login = activeLogin ?: return 0
        val scope = SecretScope.Branch(project, branch)
        // **One branch's rows, decided by parsing each key rather than by trusting a prefix.**
        //
        // This used to add every key under `secret_<login>_branch_` whose project part was this one,
        // which is *every branch of the project*: dropping `feature` deleted the tokens kept for
        // `main`, and the message said "the secrets kept for it". It is the `SecretRow` lesson one
        // level down — a row is `(scope, name)`, and here the scope has two parts — so the answer is
        // the same: parse the key, and compare the scope it *is*.
        val doomed = prefs.all.keys.filter { keyScope(it, login) == scope }
        if (doomed.isEmpty()) return 0
        val edit = prefs.edit()
        for (key in doomed) edit.remove(key)
        edit.apply()
        return doomed.size
    }

    // ---- connections: the pair, and the choice ------------------------------------------------

    /**
     * A narrower scope's connection, **with the address outside the ciphertext.**
     *
     * The reason is the same one `secretHost` has: the address is not a secret, and asking "does this
     * scope have a connection for this remote" has to work while the phone is locked — only the key
     * refuses then, and the refusal is already a state with a sentence. Two fields, one string, so
     * they cannot drift apart.
     */
    override fun connection(scope: SecretScope, kind: ConnKind): Connection? {
        val key = connKey(activeLogin, scope, kind) ?: return null
        val stored = prefs.getString(key, null) ?: return null
        val at = stored.indexOf(HOST_SEPARATOR)
        if (at < 0) return null
        val address = stored.substring(0, at)
        val json = try {
            JSONObject(decrypt(stored.substring(at + HOST_SEPARATOR.length)))
        } catch (t: Throwable) {
            // **A key that cannot be read is not a connection that can be used.** The states -
            // locked, dead, unreadable - are already told apart for the model key by
            // [modelKeyState]; a scoped row answers null and its absence is what the caller reports.
            return null
        }
        // **A blank key is a whole credential for a model server on this phone**, and half of one for
        // git — where a push with no token is a push as nobody. The store keeps the row as written and
        // each reader decides: the git lookup skips a blank secret, and a chat connection with none is
        // [Wired.keyless]. Refusing it here made a keyless server impossible to store at all.
        val secret = json.optString("secret")
        return Connection(
            address = address,
            secret = secret,
            user = json.optString("user"),
            shapes = shapesOf(json.optJSONObject("shapes")),
            // **Off unless it was written**, which is the only safe default for a permission: a row
            // stored before the checkmark existed is a row on which it was never given.
            nameable = json.optBoolean("nameable", false),
        )
    }

    override fun setConnection(scope: SecretScope, kind: ConnKind, connection: Connection) {
        val key = connKey(activeLogin, scope, kind) ?: return
        val json = JSONObject().apply {
            put("secret", connection.secret)
            if (connection.user.isNotBlank()) put("user", connection.user)
            if (connection.shapes.isNotEmpty()) put("shapes", shapesJson(connection.shapes))
            if (connection.nameable) put("nameable", true)
        }
        // **The address as written**, not lowercased: it is a base URL, and lowercasing a path is
        // how a connection starts 404ing on a server that is case-sensitive.
        prefs.edit().putString(key, connection.address.trim() + HOST_SEPARATOR + encrypt(json.toString())).apply()
    }

    /**
     * **A connection's shapes, one per kind, as a document.**
     *
     * Keyed by the kind's name rather than by an index, because the set can gain a kind and an index
     * would silently move what a stored row means. A shape whose body will not parse is dropped rather
     * than failing the whole row: the address and the key are still a credential, and a lost shape
     * falls back to the preset, which is the same request the app sent before it could store one.
     */
    private fun shapesJson(shapes: Map<ShapeKind, Shape>): JSONObject = JSONObject().apply {
        shapes.forEach { (kind, shape) ->
            put(kind.name, JSONObject().apply {
                put("url", shape.url)
                put("body", shape.body.toString())
            })
        }
    }

    private fun shapesOf(json: JSONObject?): Map<ShapeKind, Shape> {
        if (json == null) return emptyMap()
        val answer = LinkedHashMap<ShapeKind, Shape>()
        for (kind in ShapeKind.entries) {
            val one = json.optJSONObject(kind.name) ?: continue
            val url = one.optString("url")
            val body = try {
                JSONObject(one.optString("body"))
            } catch (t: Throwable) {
                continue
            }
            if (url.isNotBlank()) answer[kind] = Shape(url, body, kind)
        }
        return answer
    }

    /**
     * **The git connection that may be offered to [host]**, or null when this question has none.
     *
     * Narrowest first, and **a nearer scope bound to another host falls through** rather than answering
     * "nothing": a branch with a key for `gitlab.com` must not stop the project's key for `github.com`
     * from being used — the same rule the named secrets already follow, because it is the same
     * question ("is there a credential for *this* host") asked of a different store.
     */
    override fun gitConnectionFor(project: String?, branch: String?, host: String): Connection? {
        val at = connectionScope(project, branch) { scope ->
            connection(scope, ConnKind.GIT)?.let { hostCovers(hostOfAddress(it.address), host) } == true
        } ?: return null
        return connection(at, ConnKind.GIT)
    }

    override fun forgetConnection(scope: SecretScope, kind: ConnKind) {
        val key = connKey(activeLogin, scope, kind) ?: return
        // **The choice goes even when the pair is already gone.** A row can be left half-filled by an
        // earlier version of the window, or by a pair that could not be read back — and a "forget"
        // that left a model id behind would apply it to whatever the scope inherits next.
        val choice = connKey(activeLogin, scope, kind, CHOICE_SUFFIX) ?: return
        prefs.edit().remove(key).remove(choice).apply()
    }

    override fun choice(scope: SecretScope, kind: ConnKind): Choice? {
        val key = connKey(activeLogin, scope, kind, CHOICE_SUFFIX) ?: return null
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val json = JSONObject(stored)
            val model = json.optString("model")
            if (model.isBlank()) null else Choice(
                model = model,
                effort = json.optString("effort"),
                contextLength = json.optInt("contextLength", 0).takeIf { it > 0 },
                maxOutput = json.optInt("maxOutput", 0).takeIf { it > 0 },
                // **A word for a sentence, not a routing instruction** (see [Choice]). Written only
                // when somebody's window said one, which today is never — the account's own choice
                // keeps its word in `model_conf.json` and a row's choice names its address instead.
                provider = json.optString("provider"),
            )
        } catch (t: Throwable) {
            null
        }
    }

    override fun setChoice(scope: SecretScope, kind: ConnKind, choice: Choice) {
        val key = connKey(activeLogin, scope, kind, CHOICE_SUFFIX) ?: return
        // **A blank model removes the row**, which is what "inherit" means: the absence of a local
        // value is the inheritance, so there is no switch to keep in step with the text.
        if (choice.model.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val json = JSONObject().apply {
            put("model", choice.model)
            if (choice.effort.isNotBlank()) put("effort", choice.effort)
            choice.contextLength?.let { put("contextLength", it) }
            choice.maxOutput?.let { put("maxOutput", it) }
            if (choice.provider.isNotBlank()) put("provider", choice.provider)
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    /**
     * **`secret_general_<name>`, with no login in it** — which is the whole point of the scope.
     *
     * Every other key carries the account because the account is the identity. A general secret is
     * deliberately not: a key for a service the app itself uses should not become invisible because a
     * second person signs in. That also means `generalSecret` answers **with no account signed in**,
     * which is right — the value is not that account's to hide.
     */
    override fun generalSecret(name: String, host: String): String? {
        val stored = prefs.getString(generalSecretKey(name), null) ?: return null
        return openedAt(stored, host)
    }

    override fun setGeneralSecret(name: String, value: String, host: String) {
        val packed = host.trim().lowercase() + HOST_SEPARATOR + encrypt(value)
        prefs.edit().putString(generalSecretKey(name), packed).apply()
    }

    override fun generalSecretNames(): List<String> {
        val prefix = GENERAL_PREFIX
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                try {
                    java.net.URLDecoder.decode(key.removePrefix(prefix), "UTF-8")
                } catch (e: Exception) {
                    null
                }
            }
            .sorted()
    }

    override fun forgetGeneralSecret(name: String) {
        prefs.edit().remove(generalSecretKey(name)).apply()
    }

    override fun forgetProjectSecrets(project: String): Int {
        val login = activeLogin ?: return 0
        // **Its branches too, and this comment used to claim that while the code did not do it.**
        // A branch key is `secret_<login>_branch_<p>/<b>/<name>`, whose prefix is not the project's,
        // so deleting a project left a credential behind for a project that no longer exists. Both
        // kinds are decided by the same parse, which is why the parse is a function of its own: a
        // project called `acme` and one called `acme2` are one `startsWith` away from each other.
        val doomed = prefs.all.keys.filter { key ->
            when (val at = keyScope(key, login)) {
                is SecretScope.Project -> at.name == project
                is SecretScope.Branch -> at.project == project
                else -> false
            }
        }
        if (doomed.isEmpty()) return 0
        val edit = prefs.edit()
        for (key in doomed) edit.remove(key)
        edit.apply()
        return doomed.size
    }

    /**
     * **No `secretProject(name)` any more, and its absence is the fix.**
     *
     * It answered "the project a name is scoped to", which cannot be answered when two scopes hold
     * one name — and it said so in its own doc, then answered the first match anyway. Every screen
     * that asked it drew a row from a name and could not tell which value it was describing;
     * [secretRows] replaced it, because a row carries its own scope.
     */

    /** One secret, unpacked: the host is checked, the value is decrypted. */
    private fun openedAt(packed: String, host: String): String? {
        val (allowed, encrypted) = splitHost(packed) ?: return null
        if (!hostMatches(host, allowed)) return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * **The distinct names this account can see**, derived from the rows.
     *
     * Derived rather than parsed a second time: the names come from the same walk that produces the
     * rows, so a scope cannot be listed by one and unknown to the other — which is exactly what
     * happened while this parsed keys itself and skipped the general scope, and again when the branch
     * scope was added and `secret_dev_branch_acme/main/x` would have been listed as a secret called
     * `project_x`-shaped nonsense.
     *
     * **No decryption**, so it answers on a locked phone: knowing that a secret exists is not the same
     * as being able to use it, and a screen that showed nothing while the phone was locked would look
     * like the secret had been lost.
     *
     * A name held in two scopes appears once here — this is the list of *names*, and a caller that
     * needs to show or remove one value asks [secretRows].
     */
    override fun secretNames(): List<String> = secretRows().map { it.name }.distinct().sorted()

    /**
     * The host a stored secret is bound to, for a screen that shows where a name may be used.
     *
     * **The account-scoped row only**, because a name is not unique across scopes and this cannot say
     * which one was meant. A screen draws [secretRows], which carries a host per row.
     */
    override fun secretHost(name: String): String? {
        val login = activeLogin ?: return null
        val stored = prefs.getString(secretKey(login, name), null) ?: return null
        return splitHost(stored)?.first
    }

    /**
     * **The rows, read from the keys themselves.**
     *
     * The key prefix *is* the scope and the first line of the value *is* the host, so a row cannot
     * disagree with the value it describes — which is the defect this replaced: the window asked
     * `secretHost(name)`, which only ever read the account's key, and printed "used by this phone
     * only" for a project secret that is sent to a host.
     *
     * **No decryption**, so this answers on a locked phone, for the same reason the name list does:
     * knowing that a value exists is not the same as being able to use it.
     */
    override fun secretRows(): List<SecretRow> {
        val login = activeLogin
        val rows = ArrayList<SecretRow>()
        for ((key, value) in prefs.all) {
            val packed = value as? String ?: continue
            val host = splitHost(packed)?.first.orEmpty()
            // **General first, and it is not the account's.** The check is here rather than as an
            // exclusion in the account branch because general rows are listed **with no account signed
            // in** — they are the one scope that is not somebody's to hide.
            if (key.startsWith(GENERAL_PREFIX)) {
                val name = decode(key.removePrefix(GENERAL_PREFIX)) ?: continue
                rows.add(SecretRow(name, host, SecretScope.General))
                continue
            }
            if (login == null) continue
            val branchPrefix = "secret_${login}_branch_"
            val projectPrefix = "secret_${login}_project_"
            val accountPrefix = "secret_${login}_"
            if (key.startsWith(branchPrefix)) {
                // **Three encoded parts, so three separators.** A branch may be `feature/x`, and a
                // slash is escaped inside every part — which is what makes it the one character that
                // is unambiguously a boundary (the same rule the project scope needed).
                val parts = key.removePrefix(branchPrefix).split('/')
                if (parts.size != 3) continue
                val project = decode(parts[0]) ?: continue
                val branch = decode(parts[1]) ?: continue
                val name = decode(parts[2]) ?: continue
                rows.add(SecretRow(name, host, SecretScope.Branch(project, branch)))
            } else if (key.startsWith(projectPrefix)) {
                val middle = key.removePrefix(projectPrefix)
                val project = decode(middle.substringBeforeLast('/')) ?: continue
                val name = decode(middle.substringAfterLast('/')) ?: continue
                rows.add(SecretRow(name, host, SecretScope.Project(project)))
            } else if (key.startsWith(accountPrefix)) {
                val name = decode(key.removePrefix(accountPrefix)) ?: continue
                rows.add(SecretRow(name, host, SecretScope.Account))
            }
        }
        // **By name, then by scope**, so two rows with one name sit together and the wider one reads
        // first — the order a person scanning for "which one is this" needs.
        return rows.sortedWith(compareBy({ it.name }, { scopeOrder(it.scope) }))
    }

    /**
     * Forgets one row. **The scope decides the key**, so pressing Remove on a project's `stripe`
     * cannot delete the account's.
     */
    override fun forgetSecret(name: String, scope: SecretScope) {
        when (scope) {
            SecretScope.General -> prefs.edit().remove(generalSecretKey(name)).apply()
            SecretScope.Account -> {
                val login = activeLogin ?: return
                prefs.edit().remove(secretKey(login, name)).apply()
            }
            is SecretScope.Project -> {
                val login = activeLogin ?: return
                prefs.edit().remove(projectSecretKey(login, scope.name, name)).apply()
            }
            is SecretScope.Branch -> {
                val login = activeLogin ?: return
                prefs.edit()
                    .remove(branchSecretKey(login, scope.project, scope.branch, name))
                    .apply()
            }
        }
    }

    /** One part of a prefs key, decoded, or null when it was not written by this app. */
    private fun decode(encoded: String): String? = try {
        java.net.URLDecoder.decode(encoded, "UTF-8")
    } catch (e: Exception) {
        null
    }

    /** True when [login] has a token stored. */
    override fun hasToken(login: String): Boolean = token(login) != null

    private fun tokenKey(login: String): String = "token_$login"

    /** A different prefs key, because the two secrets must not overwrite each other. */
    private fun modelKeyKey(login: String): String = "model_key_$login"

    /**
     * A prefs key per (account, name).
     *
     * **The name is escaped rather than used raw**, because `a/b` and `a_b` would otherwise be one key and
     * one article's token would be another's. Percent-encoding is reversible and needs no table.
     */
    private fun secretKey(login: String, name: String): String =
        "secret_${login}_${java.net.URLEncoder.encode(name, "UTF-8")}"

    /**
     * A prefs key per (account, project, name).
     *
     * **The literal word `project` is in the key, and it is load-bearing.** Account keys are
     * `secret_${login}_<name>`, so a project *called* `project` would otherwise make
     * `secret_dev_project_x` mean two different things depending on who wrote it. The marker makes
     * the two shapes unambiguous, and [secretNames]' own prefix still excludes these, so a
     * project-scoped secret never appears as an account one.
     *
     * Both the project and the name are percent-encoded, because a project called `a_b` and one
     * called `a/b` would otherwise be one key.
     */
    private fun projectSecretKey(login: String, project: String, name: String): String =
        projectSecretPrefix(login, project) + java.net.URLEncoder.encode(name, "UTF-8")

    /**
     * `secret_<login>_project_<project>/`
     *
     * **The separator is a slash, and that is the whole reason it is not an underscore.** `URLEncoder`
     * leaves `_` unescaped, so a project called `a_b` and one called `a` followed by a secret named
     * `b_x` produce the same key — and no amount of parsing can tell them apart afterwards. A slash is
     * escaped to `%2F` in both the project and the name, so **it cannot occur inside either**, which
     * makes it the one character that is unambiguously a boundary.
     *
     * This changed the format, and that is free: no project-scoped secret has ever been stored (§13c.4).
     */
    /** `secret_general_<name>` — **no login**, because a general secret belongs to the app. */
    private fun generalSecretKey(name: String): String =
        GENERAL_PREFIX + java.net.URLEncoder.encode(name, "UTF-8")

    private fun projectSecretPrefix(login: String, project: String): String =
        "secret_${login}_project_${java.net.URLEncoder.encode(project, "UTF-8")}/"

    /**
     * `secret_<login>_branch_<project>/<branch>/<name>` — the project scope with one more part.
     *
     * **Each part is percent-encoded and the separator is a slash**, so a branch called `feature/x`
     * is one part rather than two, and no combination of names can produce another row's key.
     */
    private fun branchSecretPrefix(login: String, project: String, branch: String): String =
        "secret_${login}_branch_" +
            java.net.URLEncoder.encode(project, "UTF-8") + "/" +
            java.net.URLEncoder.encode(branch, "UTF-8") + "/"

    /**
     * `conn_<login>_project_<p>/<kind>` and `conn_<login>_branch_<p>/<b>/<kind>` — and `conn_general_<kind>`.
     *
     * **The same shape as the secret keys, and for the same reason**: every part is percent-encoded
     * and the separator is a slash, so a project called `a/b` is one part and no two rows can share a
     * key. The kind is the last part rather than a prefix, so every row of one project is under one
     * prefix and the sweeps in [forgetProjectSecrets] stay a scan rather than a guess.
     *
     * **The account answers null.** Its pair is `token_<login>`/`model_key_<login>` with the address
     * in the account's own records — one record each, which is the point of the vault.
     */
    private fun connPrefix(login: String?, scope: SecretScope): String? = when (scope) {
        SecretScope.Account -> null
        SecretScope.General -> "conn_general_"
        // **No login, no row.** Every scope but general belongs to an identity, and a key written
        // with the word "null" in it would be a row nobody could ever read again.
        is SecretScope.Project -> login?.let {
            "conn_${it}_project_" + java.net.URLEncoder.encode(scope.name, "UTF-8") + "/"
        }
        is SecretScope.Branch -> login?.let {
            "conn_${it}_branch_" +
                java.net.URLEncoder.encode(scope.project, "UTF-8") + "/" +
                java.net.URLEncoder.encode(scope.branch, "UTF-8") + "/"
        }
    }

    private fun connKey(login: String?, scope: SecretScope, kind: ConnKind, field: String = ""): String? =
        connPrefix(login, scope)?.let { it + kind.name.lowercase() + field }

    private fun branchSecretKey(login: String, project: String, branch: String, name: String): String =
        branchSecretPrefix(login, project, branch) + java.net.URLEncoder.encode(name, "UTF-8")

    /**
     * Splits a stored secret into the host it is bound to and the ciphertext.
     *
     * **Null when there is no separator**, which is every secret written before hosts existed. Those are
     * refused rather than trusted: a secret with no host is one that could go anywhere, and the whole point
     * of the change is that it cannot. The cost is that an existing secret must be entered again, which is
     * the right trade — a silent widening of where a token may go is not.
     */
    private fun splitHost(stored: String): Pair<String, String>? {        val at = stored.indexOf(HOST_SEPARATOR)
        if (at < 0) return null
        return stored.substring(0, at) to stored.substring(at + HOST_SEPARATOR.length)
    }

    /**
     * True when [host] may have the secret stored for [allowed].
     *
     * **Whole labels, never a suffix.** `"evil-github.com".endsWith("github.com")` is false, but the naive
     * version of this check is the kind of thing that passes review and fails an attacker: a bare
     * `endsWith` would accept `notgithub.com` for `github.com`. Requiring the dot makes the subdomain case
     * work and the look-alike case fail.
     *
     * A subdomain is allowed because a service's API and its upload host are routinely different names
     * (`api.github.com`, `objects.githubusercontent.com`) and a person setting up one secret should not have
     * to guess which one the app will use.
     *
     * **An empty stored host matches only an empty question**, and the reverse. That is what keeps a
     * decryption key from being sent and a token from being printed into a log.
     */
    private fun hostMatches(host: String, allowed: String): Boolean = hostCovers(allowed, host)

    // ---- encryption --------------------------------------------------------

    /**
     * Encrypts the token with a key from the Android keystore. The key never
     * leaves the keystore, so the token cannot be read without the device.
     */
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /** Decrypts a token that was stored by [encrypt]. */
    private fun decrypt(stored: String): String? {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) return null
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val encrypted = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH, iv),
        )
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** The encryption key, created on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(ALGORITHM, KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // The key that protects the tokens is only usable while the phone
            // is unlocked, so a locked phone cannot be made to decrypt them.
            spec.setUnlockedDeviceRequired(true)
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "muffy_git"
        const val KEY_ACTIVE = "active"
        const val KEY_LOGINS = "logins"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "muffy_codes_git_token"
        const val ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 128

        /**
         * Between the host and the ciphertext in a stored secret.
         *
         * **A newline, because neither part can contain one**: a host is a normalised hostname and the
         * ciphertext is base64. Any separator would do; this one is the one that cannot be forged.
         */
        const val HOST_SEPARATOR = "\n"

        /** The field a scope's choice is stored under, beside its connection. */
        private const val CHOICE_SUFFIX = "_choice"

        /**
         * The prefix of a general secret's key: `secret_general_<name>`.
         *
         * **No login**, unlike every other key here, because a general secret belongs to the app rather
         * than to the account that happens to be signed in (§13c.4). `secretNames()` filters this prefix
         * out so a general secret is not listed twice — once as an account secret with the name
         * `general_x`, which nothing could use.
         */
        const val GENERAL_PREFIX = "secret_general_"
    }
}
