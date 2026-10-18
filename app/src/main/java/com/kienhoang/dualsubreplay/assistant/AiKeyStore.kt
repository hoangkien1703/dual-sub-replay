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

/** At most this many keys are kept for one service. */
internal const val MAX_AI_KEYS_PER_SERVICE = 10

/**
 * One saved key, without the key itself: [hint] is its last characters and [checkedSetup] the
 * setup it last answered with (see [aiCheckedSetup]), or null when unchecked.
 */
internal data class AiSavedKey(
    val id: String,
    val hint: String,
    val checkedSetup: String? = null,
)

/** What adding a key gave: its id (a key saved before keeps its own), or [Full]. */
internal sealed interface AiKeyAdded {
    data class Saved(
        val id: String,
        /** The same key was saved before; nothing changed. */
        val existing: Boolean,
    ) : AiKeyAdded

    data object Full : AiKeyAdded
}

/**
 * Several API keys per service, encrypted, in the order they were added; the last 4 characters
 * are kept apart to show which key it is. Next to each key is the setup it last answered with;
 * a new key starts unchecked. The key with [LEGACY_KEY_ID] uses the names of the time when each
 * service had one key, so a key saved then keeps working; [ORDER_PREFIX] lists the ids once a
 * service has more than that one.
 */
internal class AiKeyStore(
    private val storage: SecretStorage,
    private val cipher: SecretCipher,
    private val newId: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
            .take(ID_LENGTH)
    },
) {
    fun keys(provider: AiProvider): List<AiSavedKey> =
        ids(provider).mapNotNull { id ->
            storage.get(name(KEY_PREFIX, provider, id))?.let {
                AiSavedKey(
                    id = id,
                    hint = storage.get(name(HINT_PREFIX, provider, id)).orEmpty(),
                    checkedSetup = storage.get(name(CHECKED_PREFIX, provider, id))?.takeIf { it.isNotEmpty() },
                )
            }
        }

    /** Saves [apiKey] after the service's other keys; the same key saved again keeps its place. */
    fun add(
        provider: AiProvider,
        apiKey: String,
    ): AiKeyAdded {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty())
        keys(provider)
            .filter { it.hint == trimmed.takeLast(HINT_LENGTH) }
            .firstOrNull { (load(provider, it.id) as? StoredAiKey.Found)?.key == trimmed }
            ?.let { return AiKeyAdded.Saved(it.id, existing = true) }
        // Read again: a saved key the phone could no longer unlock was just removed.
        val current = keys(provider)
        if (current.size >= MAX_AI_KEYS_PER_SERVICE) return AiKeyAdded.Full
        val taken = current.map { it.id }.toSet() + LEGACY_KEY_ID
        val id = if (current.isEmpty()) LEGACY_KEY_ID else generateSequence(newId).first { it !in taken }
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        storage.put(
            mapOf(
                name(KEY_PREFIX, provider, id) to sealed,
                name(HINT_PREFIX, provider, id) to trimmed.takeLast(HINT_LENGTH),
                name(CHECKED_PREFIX, provider, id) to "",
                ORDER_PREFIX + provider.key to (current.map { it.id } + id).joinToString(ORDER_SEPARATOR),
            ),
        )
        return AiKeyAdded.Saved(id, existing = false)
    }

    fun load(
        provider: AiProvider,
        id: String,
    ): StoredAiKey {
        val sealed = storage.get(name(KEY_PREFIX, provider, id)) ?: return StoredAiKey.Missing
        return try {
            StoredAiKey.Found(String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            remove(provider, id)
            StoredAiKey.Unreadable
        } catch (_: IllegalArgumentException) {
            remove(provider, id)
            StoredAiKey.Unreadable
        }
    }

    /** Records the setup [id] answered with; a key removed meanwhile stays removed. */
    fun markChecked(
        provider: AiProvider,
        id: String,
        setup: String,
    ) {
        if (storage.get(name(KEY_PREFIX, provider, id)) != null) storage.put(mapOf(name(CHECKED_PREFIX, provider, id) to setup))
    }

    fun clearChecked(
        provider: AiProvider,
        id: String,
    ) {
        storage.remove(listOf(name(CHECKED_PREFIX, provider, id)))
    }

    fun remove(
        provider: AiProvider,
        id: String,
    ) {
        val rest = ids(provider).filter { it != id }
        storage.remove(listOf(name(KEY_PREFIX, provider, id), name(HINT_PREFIX, provider, id), name(CHECKED_PREFIX, provider, id)))
        if (rest.isEmpty()) {
            storage.remove(listOf(ORDER_PREFIX + provider.key))
        } else {
            storage.put(mapOf(ORDER_PREFIX + provider.key to rest.joinToString(ORDER_SEPARATOR)))
        }
    }

    private fun ids(provider: AiProvider): List<String> {
        val order = storage.get(ORDER_PREFIX + provider.key)
        if (order != null) return order.split(ORDER_SEPARATOR).filter { it.isNotEmpty() }
        return if (storage.get(name(KEY_PREFIX, provider, LEGACY_KEY_ID)) != null) listOf(LEGACY_KEY_ID) else emptyList()
    }

    private fun name(
        prefix: String,
        provider: AiProvider,
        id: String,
    ): String = if (id == LEGACY_KEY_ID) prefix + provider.key else prefix + provider.key + "." + id

    private companion object {
        /** The first key of a service, stored under the names one key per service used. */
        const val LEGACY_KEY_ID = "0"
        const val ORDER_PREFIX = "keys_"
        const val ORDER_SEPARATOR = ","
        const val ID_LENGTH = 8
    }
}

/**
 * The Tavily search key, encrypted like the AI keys in the same file, so backups leave it out too.
 * One key; saving another replaces it.
 */
internal class AiSearchKeyStore(
    private val storage: SecretStorage,
    private val cipher: SecretCipher,
) {
    /** The last 4 characters of the saved key, or null when none is saved. */
    fun hint(): String? = storage.get(TAVILY_KEY)?.let { storage.get(TAVILY_HINT).orEmpty() }

    fun save(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty())
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        storage.put(mapOf(TAVILY_KEY to sealed, TAVILY_HINT to trimmed.takeLast(HINT_LENGTH)))
    }

    fun load(): StoredAiKey {
        val sealed = storage.get(TAVILY_KEY) ?: return StoredAiKey.Missing
        return try {
            StoredAiKey.Found(String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            remove()
            StoredAiKey.Unreadable
        } catch (_: IllegalArgumentException) {
            remove()
            StoredAiKey.Unreadable
        }
    }

    fun remove() = storage.remove(listOf(TAVILY_KEY, TAVILY_HINT))

    private companion object {
        const val TAVILY_KEY = "search_key_tavily"
        const val TAVILY_HINT = "search_hint_tavily"
    }
}
