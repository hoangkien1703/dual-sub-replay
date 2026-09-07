package com.kienhoang.dualsubreplay.translation

/** Exact input and language pairs only; never reuse across language changes. */
internal class TranslationCache(
    private val maxEntries: Int = 256,
    private val maxCharacters: Int = 128_000,
) {
    private data class Key(
        val source: String,
        val target: String,
        val text: String,
    )

    private val entries = LinkedHashMap<Key, String>(16, 0.75f, true)
    private var characters = 0

    @Synchronized fun get(
        source: String,
        target: String,
        text: String,
    ): String? = entries[Key(source, target, text)]

    @Synchronized fun put(
        source: String,
        target: String,
        text: String,
        translation: String,
    ) {
        val key = Key(source, target, text)
        entries.remove(key)?.let { characters -= text.length + it.length }
        if (text.length + translation.length > maxCharacters) return
        entries[key] = translation
        characters += text.length + translation.length
        while (entries.size > maxEntries || characters > maxCharacters) {
            val first = entries.entries.iterator()
            val entry = first.next()
            characters -= entry.key.text.length + entry.value.length
            first.remove()
        }
    }
}
