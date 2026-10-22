package com.example.notes

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the GitHub account details are kept.
 *
 * The personal access token is the sensitive part, so it is encrypted with a
 * key held in the Android keystore before it is written to disk. The account
 * name and email are not secret and are stored in plain text.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("muffy_git", Context.MODE_PRIVATE)

    /** The name of the signed-in GitHub account, or null. */
    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    /** The email used for commits, or null. */
    var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    /** The personal access token, or null when the account is not set up. */
    var token: String?
        get() {
            val stored = prefs.getString(KEY_TOKEN, null) ?: return null
            return try {
                decrypt(stored)
            } catch (e: Exception) {
                // The keystore key is gone (for example after a factory reset):
                // drop the unreadable token rather than keeping a broken one.
                prefs.edit().remove(KEY_TOKEN).apply()
                null
            }
        }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_TOKEN).apply()
            } else {
                prefs.edit().putString(KEY_TOKEN, encrypt(value)).apply()
            }
        }

    /** True when an account has been set up. */
    val isSignedIn: Boolean get() = !accountName.isNullOrBlank()

    /** The identity used for the commits the app makes. */
    fun author(): GitAuthor = GitAuthor(
        name = accountName?.takeIf { it.isNotBlank() } ?: "Muffy World",
        email = accountEmail?.takeIf { it.isNotBlank() } ?: "muffyworld365@gmail.com",
    )

    /** Forgets the account and its token. */
    fun signOut() {
        prefs.edit()
            .remove(KEY_ACCOUNT)
            .remove(KEY_EMAIL)
            .remove(KEY_TOKEN)
            .apply()
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
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "muffy_git"
        const val KEY_ACCOUNT = "account"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "muffy_codes_git_token"
        const val ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 128
    }
}
