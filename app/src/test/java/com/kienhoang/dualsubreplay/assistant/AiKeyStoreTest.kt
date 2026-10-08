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

    @Test
    fun aSavedKeyIsStoredEncryptedAndReadBack() {
        val storage = MapStorage()
        val store = AiKeyStore(storage, FakeCipher())
        store.save(AiProvider.GEMINI, "  $key\n")
        assertFalse(storage.values.values.any { key in it })
        assertEquals(StoredAiKey.Found(key), store.load(AiProvider.GEMINI))
        assertEquals("rstu", store.hint(AiProvider.GEMINI))
        assertEquals(StoredAiKey.Missing, store.load(AiProvider.OPENAI))
        assertNull(store.hint(AiProvider.OPENAI))
    }

    @Test
    fun eachServiceKeepsItsOwnKey() {
        val store = AiKeyStore(MapStorage(), FakeCipher())
        store.save(AiProvider.GEMINI, key)
        store.save(AiProvider.OPENAI, "sk-proj-abcdefghijklmnop1234")
        store.remove(AiProvider.GEMINI)
        assertEquals(StoredAiKey.Missing, store.load(AiProvider.GEMINI))
        assertEquals(StoredAiKey.Found("sk-proj-abcdefghijklmnop1234"), store.load(AiProvider.OPENAI))
        assertEquals("1234", store.hint(AiProvider.OPENAI))
    }

    @Test
    fun aKeyIsUncheckedUntilItAnswersAndANewKeyStartsUncheckedAgain() {
        val storage = MapStorage()
        val store = AiKeyStore(storage, FakeCipher())
        store.save(AiProvider.GEMINI, key)
        assertNull(store.checkedSetup(AiProvider.GEMINI))
        store.markChecked(AiProvider.GEMINI, "setup")
        assertEquals("setup", store.checkedSetup(AiProvider.GEMINI))
        store.save(AiProvider.GEMINI, "AIza-FAKE-another-key-for-unit-tests")
        assertNull(store.checkedSetup(AiProvider.GEMINI))
        store.markChecked(AiProvider.GEMINI, "setup")
        store.clearChecked(AiProvider.GEMINI)
        assertNull(store.checkedSetup(AiProvider.GEMINI))
        store.markChecked(AiProvider.GEMINI, "setup")
        store.remove(AiProvider.GEMINI)
        assertNull(store.checkedSetup(AiProvider.GEMINI))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun aKeyThePhoneCanNoLongerUnlockIsRemoved() {
        val storage = MapStorage()
        val cipher = FakeCipher()
        val store = AiKeyStore(storage, cipher)
        store.save(AiProvider.GEMINI, key)
        store.markChecked(AiProvider.GEMINI, "setup")
        cipher.broken = true
        assertEquals(StoredAiKey.Unreadable, store.load(AiProvider.GEMINI))
        assertTrue(storage.values.isEmpty())
        assertNull(store.hint(AiProvider.GEMINI))
    }

    @Test
    fun aDamagedEntryIsRemovedToo() {
        val storage = MapStorage()
        storage.values["key_gemini"] = "not base64 !!"
        val store = AiKeyStore(storage, FakeCipher())
        assertEquals(StoredAiKey.Unreadable, store.load(AiProvider.GEMINI))
        assertTrue(storage.values.isEmpty())
    }
}
