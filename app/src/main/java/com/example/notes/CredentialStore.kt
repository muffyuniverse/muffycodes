package com.example.notes

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the keys of the stored accounts are kept.
 *
 * Every account has its own token, stored encrypted with a key from the Android
 * keystore: the key never leaves the keystore, so a token cannot be read without
 * the phone, and only while it is unlocked. The login names are not secret and
 * are stored in plain text — they name the account, they do not open it.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The login of the active account, or null when nobody is signed in. */
    var activeLogin: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value)
            }.apply()
        }

    /** The logins of the accounts stored on this phone, in the order added. */
    fun logins(): List<String> =
        prefs.getString(KEY_LOGINS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** The token of [login], or null when there is none or it cannot be read. */
    fun token(login: String): String? {
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
    fun setToken(login: String, token: String) {
        prefs.edit().putString(tokenKey(login), encrypt(token)).apply()
        val known = logins()
        if (login !in known) {
            prefs.edit().putString(KEY_LOGINS, (known + login).joinToString("\n")).apply()
        }
    }

    /** Removes [login] and its token from this phone. */
    fun forget(login: String) {
        prefs.edit()
            .remove(tokenKey(login))
            .putString(KEY_LOGINS, logins().filter { it != login }.joinToString("\n"))
            .apply()
        if (activeLogin == login) activeLogin = null
    }

    /** True when [login] has a token stored. */
    fun hasToken(login: String): Boolean = token(login) != null

    private fun tokenKey(login: String): String = "token_$login"

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
