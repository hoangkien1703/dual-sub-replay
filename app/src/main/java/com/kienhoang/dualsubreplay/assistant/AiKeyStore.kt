package com.kienhoang.dualsubreplay.assistant

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The preferences file holding the encrypted keys. Backups leave it out (backup_rules.xml and
 * data_extraction_rules.xml): the Keystore key that unlocks it never leaves this phone anyway.
 */
internal const val AI_KEYS_PREFERENCES = "ai_assistant_keys"

private const val KEY_PREFIX = "key_"
private const val HINT_PREFIX = "hint_"
private const val CHECKED_PREFIX = "checked_"
private const val HINT_LENGTH = 4

/** Locks and unlocks small secrets. The app uses [KeystoreSecretCipher]; tests use a stand-in. */
internal interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/** A small key-value file; SharedPreferences in the app, a map in tests. */
internal interface SecretStorage {
    fun get(name: String): String?

    fun put(values: Map<String, String>)

    fun remove(names: List<String>)
}

internal class SharedPreferencesSecretStorage(
    private val preferences: SharedPreferences,
) : SecretStorage {
    override fun get(name: String): String? = preferences.getString(name, null)

    // commit, not apply: the key must be on disk before the screen says it is saved.
    override fun put(values: Map<String, String>) {
        preferences.edit(commit = true) { values.forEach { (name, value) -> putString(name, value) } }
    }

    override fun remove(names: List<String>) {
        preferences.edit(commit = true) { names.forEach(::remove) }
    }
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore. The app can ask the Keystore to
 * encrypt and decrypt, but the key itself can never be read out, copied or backed up. The stored
 * form is the 12-byte IV followed by the ciphertext and tag.
 */
internal class KeystoreSecretCipher(
    private val alias: String = "dualsub_ai_api_keys",
) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("The stored key is too short.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}

/** What reading a saved key gave: the key, nothing saved, or a key this phone can no longer unlock. */
internal sealed interface StoredAiKey {
    data class Found(
        val key: String,
    ) : StoredAiKey

    data object Missing : StoredAiKey

    /** The Keystore lost its key (for example after a security reset); the user pastes the key again. */
    data object Unreadable : StoredAiKey
}

/**
 * One API key per service, encrypted; the last 4 characters are kept apart to show which key it is.
 * Next to each key is the setup it last answered with (see [aiCheckedSetup]); a new key starts unchecked.
 */
internal class AiKeyStore(
    private val storage: SecretStorage,
    private val cipher: SecretCipher,
) {
    fun save(
        provider: AiProvider,
        apiKey: String,
    ) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty())
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        storage.put(
            mapOf(
                KEY_PREFIX + provider.key to sealed,
                HINT_PREFIX + provider.key to trimmed.takeLast(HINT_LENGTH),
                CHECKED_PREFIX + provider.key to "",
            ),
        )
    }

    fun load(provider: AiProvider): StoredAiKey {
        val sealed = storage.get(KEY_PREFIX + provider.key) ?: return StoredAiKey.Missing
        return try {
            StoredAiKey.Found(String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            remove(provider)
            StoredAiKey.Unreadable
        } catch (_: IllegalArgumentException) {
            remove(provider)
            StoredAiKey.Unreadable
        }
    }

    /** The last characters of the saved key, or null when none is saved. Reading it needs no decryption. */
    fun hint(provider: AiProvider): String? =
        storage.get(KEY_PREFIX + provider.key)?.let { storage.get(HINT_PREFIX + provider.key).orEmpty() }

    /** The setup the saved key last worked with, or null when it has not been checked since it was saved. */
    fun checkedSetup(provider: AiProvider): String? =
        storage.get(CHECKED_PREFIX + provider.key)?.takeIf { it.isNotEmpty() && storage.get(KEY_PREFIX + provider.key) != null }

    fun markChecked(
        provider: AiProvider,
        setup: String,
    ) {
        storage.put(mapOf(CHECKED_PREFIX + provider.key to setup))
    }

    fun clearChecked(provider: AiProvider) {
        storage.remove(listOf(CHECKED_PREFIX + provider.key))
    }

    fun remove(provider: AiProvider) {
        storage.remove(listOf(KEY_PREFIX + provider.key, HINT_PREFIX + provider.key, CHECKED_PREFIX + provider.key))
    }
}
