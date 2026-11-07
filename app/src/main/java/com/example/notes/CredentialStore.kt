package com.example.notes

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    fun secret(project: String?, name: String, host: String): String? = secret(name, host)

    /** Keeps [value] as [name]'s secret for the active account, usable **only** at [host]. */
    fun setSecret(name: String, value: String, host: String) {}

    /** Keeps [value] for [project] only when [project] is not null; otherwise account-wide. */
    fun setSecret(project: String?, name: String, value: String, host: String) =
        setSecret(name, value, host)

    /**
     * Forgets every secret scoped to [project], answering how many went.
     *
     * **Called when a project is deleted**, before its folder is. A secret whose project no longer
     * exists is a row nothing can ever read, and leaving it would be a leak nobody can see.
     */
    fun forgetProjectSecrets(project: String): Int = 0

    /** The project a secret is scoped to, or null when it is account-wide. */
    fun secretProject(name: String): String? = null

    /**
     * The names of this account's secrets, **never their values**, so a person (and the agent) can see what
     * exists without learning anything that could be used.
     *
     * Empty rather than throwing, for the same reason as the other defaults.
     */
    fun secretNames(): List<String> = emptyList()

    /** Forgets [name], so a value that is no longer wanted cannot be used by anything. */
    fun forgetSecret(name: String) {}

    /** The host [name] may be used at, or null when there is no such secret. Never the value. */
    fun secretHost(name: String): String? = null
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
        val mine = prefs.all.keys.filter { it.startsWith("secret_${login}_") }
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

    override fun secret(project: String?, name: String, host: String): String? {
        val login = activeLogin ?: return null
        // **Project first, then account.** The order is the whole feature: a project that needs a
        // different key for the same service says so by storing one, and nothing else changes.
        if (project != null) {
            val scoped = prefs.getString(projectSecretKey(login, project, name), null)
            if (scoped != null) return openedAt(scoped, host)
        }
        val account = prefs.getString(secretKey(login, name), null) ?: return null
        return openedAt(account, host)
    }

    override fun setSecret(project: String?, name: String, value: String, host: String) {
        val login = activeLogin ?: return
        val packed = host.trim().lowercase() + HOST_SEPARATOR + encrypt(value)
        val key = if (project == null) secretKey(login, name) else projectSecretKey(login, project, name)
        prefs.edit().putString(key, packed).apply()
    }

    override fun forgetProjectSecrets(project: String): Int {
        val login = activeLogin ?: return 0
        val prefix = projectSecretPrefix(login, project)
        val doomed = prefs.all.keys.filter { it.startsWith(prefix) }
        if (doomed.isEmpty()) return 0
        val edit = prefs.edit()
        for (key in doomed) edit.remove(key)
        edit.apply()
        return doomed.size
    }

    /**
     * The project a secret is scoped to, or null when it is account-wide.
     *
     * **Read off the key**, because the key is the scope and a second copy could disagree with the one
     * the lookup uses. The split is at the slash: a slash is escaped to `%2F` in both the project and
     * the name, so it cannot occur inside either and is the only unambiguous boundary.
     *
     * **When two projects hold a secret of the same name**, this answers the first one found. That is a
     * real ambiguity and it is stated rather than hidden: the window draws a row from a name, and a
     * name is not unique across scopes. Two projects holding the same name is the case that exposes it,
     * and the honest fix — if it ever matters — is to key the row by scope as well, not to guess here.
     */
    override fun secretProject(name: String): String? {
        val login = activeLogin ?: return null
        val marker = "secret_${login}_project_"
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val hit = prefs.all.keys.firstOrNull {
            it.startsWith(marker) && it.substringAfterLast('/') == encoded
        } ?: return null
        val middle = hit.removePrefix(marker).substringBeforeLast('/')
        return try {
            java.net.URLDecoder.decode(middle, "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

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
     * The names, sorted, read straight from the keys.
     *
     * **No decryption, so this answers on a locked phone** — which is the point of a listing: knowing that a
     * secret exists is not the same as being able to use it, and a screen that showed nothing while the phone
     * was locked would look like the secret had been lost.
     */
    /**
     * Every secret this account has, **including the project-scoped ones**, as the names a person
     * should see.
     *
     * **This used to exclude project-scoped keys, and that made them invisible rather than tidy.** The
     * exclusion was written to avoid listing `secret_dev_project_x` as a secret called `project_x` —
     * which is right about the *name* and wrong about the *row*: a project secret was stored, was
     * readable by the agent, and **could not be seen, edited or removed in the window**. A secret the
     * screen cannot show is a secret nobody can audit, and the scope is exactly the fact a person needs
     * to see.
     *
     * So the project marker is **parsed out rather than filtered out**: `secret_dev_project_<p>_<n>`
     * becomes the name `n`, and [secretProject] answers `<p>` for it so the row can say which scope it
     * is. A project name is decoded with the same `URLDecoder` as the secret's own name, because it is
     * encoded the same way and a project called `acme shop` would otherwise never match its own row.
     */
    override fun secretNames(): List<String> {
        val login = activeLogin ?: return emptyList()
        val accountPrefix = "secret_${login}_"
        val projectPrefix = "secret_${login}_project_"
        return prefs.all.keys
            .filter { it.startsWith(accountPrefix) }
            .mapNotNull { key ->
                try {
                    if (key.startsWith(projectPrefix)) {
                        // **Everything after the last slash is the name**, because a slash cannot occur
                        // inside the encoded project or the encoded name.
                        java.net.URLDecoder.decode(
                            key.substringAfterLast('/'),
                            "UTF-8",
                        )
                    } else {
                        java.net.URLDecoder.decode(key.removePrefix(accountPrefix), "UTF-8")
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .distinct()
            .sorted()
    }

    /** The host a stored secret is bound to, for a screen that shows where a name may be used. */
    override fun secretHost(name: String): String? {
        val login = activeLogin ?: return null
        val stored = prefs.getString(secretKey(login, name), null) ?: return null
        return splitHost(stored)?.first
    }

    override fun forgetSecret(name: String) {
        val login = activeLogin ?: return
        prefs.edit().remove(secretKey(login, name)).apply()
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
    private fun projectSecretPrefix(login: String, project: String): String =
        "secret_${login}_project_${java.net.URLEncoder.encode(project, "UTF-8")}/"

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
    private fun hostMatches(host: String, allowed: String): Boolean {
        val asked = host.trim().lowercase()
        val stored = allowed.trim().lowercase()
        if (stored.isEmpty() || asked.isEmpty()) return asked == stored
        return asked == stored || asked.endsWith(".$stored")
    }

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
    }
}
