"""The app's rules for the dictionary form of a Spanish noun, adjective or determiner (keep in sync
with `spanishWordLemma` in SpanishLexicon.kt): the word itself, then without a plural ending
(-s, -es, -ces → -z), each also from feminine to masculine (-a → -o, -a → nothing); the first
that is a listed word of that part of speech wins, exactly or with other accents."""

import unicodedata


def strip_accents(word):
    return "".join(c for c in unicodedata.normalize("NFC", word).translate(ACCENTS))


ACCENTS = str.maketrans("áéíóúü", "aeiouu")


def candidates(word):
    singular = [word]
    if word.endswith("s") and len(word) > 2:
        singular.append(word[:-1])
    if word.endswith("es") and len(word) > 3:
        singular.append(word[:-2])
    if word.endswith("ces") and len(word) > 4:
        singular.append(word[:-3] + "z")
    found = []
    for base in singular:
        found.append(base)
        if base.endswith("a") and len(base) > 2:
            found += [base[:-1] + "o", base[:-1]]
    return found


_index_cache = {}


def lemma_of(word, letter, kept):
    key = id(kept)
    if key not in _index_cache:
        index = {}
        for lemma, letters in kept.items():
            index.setdefault(strip_accents(lemma), []).append(lemma)
        _index_cache[key] = {plain: sorted(found) for plain, found in index.items()}
    index = _index_cache[key]
    for candidate in candidates(word):
        if letter in kept.get(candidate, ""):
            return candidate
        for lemma in index.get(strip_accents(candidate), []):
            if letter in kept[lemma]:
                return lemma
    return None
