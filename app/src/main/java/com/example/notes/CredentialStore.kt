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
        prefs.edit()
            .remove(tokenKey(login))
            .remove(modelKeyKey(login))
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

    /** True when [login] has a token stored. */
    override fun hasToken(login: String): Boolean = token(login) != null

    private fun tokenKey(login: String): String = "token_$login"

    /** A different prefs key, because the two secrets must not overwrite each other. */
    private fun modelKeyKey(login: String): String = "model_key_$login"

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
    }
}
