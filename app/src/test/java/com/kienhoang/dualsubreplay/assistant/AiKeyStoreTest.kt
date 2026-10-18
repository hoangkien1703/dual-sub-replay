package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/** Stands in for the Keystore: reversible, but the stored bytes never contain the key. */
internal class FakeCipher : SecretCipher {
    var broken = false

    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (broken) throw GeneralSecurityException("Keystore key is gone")
        return sealed.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

internal class MapStorage : SecretStorage {
    val values = mutableMapOf<String, String>()

    override fun get(name: String) = values[name]

    override fun put(values: Map<String, String>) {
        this.values += values
    }

    override fun remove(names: List<String>) {
        names.forEach(values::remove)
    }
}

class AiKeyStoreTest {
    private val key = "AIza-FAKE-test-key-for-unit-tests-rstu"
    private val secondKey = "AIza-FAKE-second-key-for-unit-tests-wxyz"

    private fun AiKeyStore.addId(
        provider: AiProvider,
        apiKey: String,
    ): String = (add(provider, apiKey) as AiKeyAdded.Saved).id

    @Test
    fun aSavedKeyIsStoredEncryptedAndReadBack() {
        val storage = MapStorage()
        val store = AiKeyStore(storage, FakeCipher())
        val id = store.addId(AiProvider.GEMINI, "  $key\n")
        assertFalse(storage.values.values.any { key in it })
        assertEquals(StoredAiKey.Found(key), store.load(AiProvider.GEMINI, id))
        assertEquals(listOf(AiSavedKey(id, "rstu")), store.keys(AiProvider.GEMINI))
        assertEquals(StoredAiKey.Missing, store.load(AiProvider.OPENAI, id))
        assertTrue(store.keys(AiProvider.OPENAI).isEmpty())
    }

    @Test
    fun aServiceKeepsSeveralKeysInTheOrderTheyWereAdded() {
        var next = 0
        val store = AiKeyStore(MapStorage(), FakeCipher()) { "id${++next}" }
        val first = store.addId(AiProvider.GEMINI, key)
        val second = store.addId(AiProvider.GEMINI, secondKey)
        val openAi = store.addId(AiProvider.OPENAI, "sk-proj-abcdefghijklmnop1234")
        assertEquals(listOf("rstu", "wxyz"), store.keys(AiProvider.GEMINI).map { it.hint })
        assertEquals(StoredAiKey.Found(secondKey), store.load(AiProvider.GEMINI, second))
        // The same key again keeps its place instead of adding a copy.
        assertEquals(AiKeyAdded.Saved(first, existing = true), store.add(AiProvider.GEMINI, " $key "))
        assertEquals(2, store.keys(AiProvider.GEMINI).size)
        store.remove(AiProvider.GEMINI, first)
        assertEquals(listOf(second), store.keys(AiProvider.GEMINI).map { it.id })
        assertEquals(StoredAiKey.Missing, store.load(AiProvider.GEMINI, first))
        assertEquals(StoredAiKey.Found("sk-proj-abcdefghijklmnop1234"), store.load(AiProvider.OPENAI, openAi))
    }

    @Test
    fun aKeySavedWhenEachServiceHadOneKeyStillLoads() {
        val storage = MapStorage()
        val cipher = FakeCipher()
        // The names version 1.6 and earlier wrote.
        storage.values["key_gemini"] =
            java.util.Base64
                .getEncoder()
                .encodeToString(cipher.encrypt(key.toByteArray()))
        storage.values["hint_gemini"] = "rstu"
        storage.values["checked_gemini"] = "setup"
        val store = AiKeyStore(storage, cipher) { "new" }
        val saved = store.keys(AiProvider.GEMINI).single()
        assertEquals("rstu", saved.hint)
        assertEquals("setup", saved.checkedSetup)
        assertEquals(StoredAiKey.Found(key), store.load(AiProvider.GEMINI, saved.id))
        store.addId(AiProvider.GEMINI, secondKey)
        assertEquals(listOf(saved.id, "new"), store.keys(AiProvider.GEMINI).map { it.id })
    }

    @Test
    fun aKeyIsUncheckedUntilItAnswersAndANewKeyStartsUnchecked() {
        val storage = MapStorage()
        val store = AiKeyStore(storage, FakeCipher())
        val id = store.addId(AiProvider.GEMINI, key)
        assertNull(store.keys(AiProvider.GEMINI).single().checkedSetup)
        store.markChecked(AiProvider.GEMINI, id, "setup")
        assertEquals("setup", store.keys(AiProvider.GEMINI).single().checkedSetup)
        val second = store.addId(AiProvider.GEMINI, secondKey)
        assertNull(store.keys(AiProvider.GEMINI).single { it.id == second }.checkedSetup)
        store.clearChecked(AiProvider.GEMINI, id)
        assertNull(store.keys(AiProvider.GEMINI).first().checkedSetup)
        store.remove(AiProvider.GEMINI, id)
        // A check that ends after its key was removed leaves nothing behind.
        store.markChecked(AiProvider.GEMINI, id, "setup")
        store.remove(AiProvider.GEMINI, second)
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun aServiceKeepsAtMostTenKeys() {
        var next = 0
        val store = AiKeyStore(MapStorage(), FakeCipher()) { "id${++next}" }
        repeat(MAX_AI_KEYS_PER_SERVICE) { store.addId(AiProvider.OPENROUTER, "sk-or-v1-fake-key-number-$it-abcdefgh") }
        assertEquals(AiKeyAdded.Full, store.add(AiProvider.OPENROUTER, "sk-or-v1-fake-key-number-eleven-abcd"))
        assertEquals(MAX_AI_KEYS_PER_SERVICE, store.keys(AiProvider.OPENROUTER).size)
    }

    @Test
    fun aKeyThePhoneCanNoLongerUnlockIsRemoved() {
        val storage = MapStorage()
        val cipher = FakeCipher()
        val store = AiKeyStore(storage, cipher)
        val id = store.addId(AiProvider.GEMINI, key)
        store.markChecked(AiProvider.GEMINI, id, "setup")
        cipher.broken = true
        assertEquals(StoredAiKey.Unreadable, store.load(AiProvider.GEMINI, id))
        assertTrue(storage.values.isEmpty())
        assertTrue(store.keys(AiProvider.GEMINI).isEmpty())
    }

    @Test
    fun aDamagedEntryIsRemovedToo() {
        val storage = MapStorage()
        storage.values["key_gemini"] = "not base64 !!"
        val store = AiKeyStore(storage, FakeCipher())
        val id = store.keys(AiProvider.GEMINI).single().id
        assertEquals(StoredAiKey.Unreadable, store.load(AiProvider.GEMINI, id))
        assertTrue(storage.values.isEmpty())
    }
}
