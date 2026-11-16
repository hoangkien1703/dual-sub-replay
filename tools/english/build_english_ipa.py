#!/usr/bin/env python3
"""Builds app/src/main/learner-data/en-ipa.txt, the English IPA the word card shows.

Usage:
  python3 tools/english/build_english_ipa.py en_US.txt en_50k.txt app/src/main/learner-data

Inputs:
- open-dict-data/ipa-dict, American English (data/en_US.txt), MIT licence,
  https://github.com/open-dict-data/ipa-dict (commit 972cd67dd5f96bc5660e28070cde26e3928abcd7 was used).
  ipa-dict made it from cmudict-ipa (MIT), a conversion of the CMU Pronouncing Dictionary
  (Carnegie Mellon University, BSD-style licence).
- hermitdave/FrequencyWords, OpenSubtitles 2018 English top 50,000 words (content/2018/en/en_50k.txt),
  CC BY-SA 4.0. Only the words of this list are kept, so the file stays small; the output is
  therefore CC BY-SA 4.0 too (credited in THIRD_PARTY_NOTICES.md and the licence screen).

Output: one word per line, "word<TAB>ipa" or "word<TAB>ipa,ipa" (at most two pronunciations, the
first is the dictionary's first), sorted by word, without the dictionary's slashes. Never edit the
output by hand: run this script again.
"""

import os
import sys

MAX_PRONUNCIATIONS = 2

# ipa-dict writes the dark l and the approximant r that learner dictionaries print as plain l and r.
REPLACEMENTS = {"ɫ": "l", "ɹ": "r"}

# Vowel symbols; a run of them (a diphthong such as oʊ) is one syllable.
VOWELS = set("aeiouæɑɒɔəɛɜɝɚɪʊʌyøœɐɘɵɤ")
STRESS_MARKS = "ˈˌ"

HEADER = (
    "# English IPA for DualSub Replay, made by tools/english/build_english_ipa.py.\n"
    "# Data: open-dict-data/ipa-dict en_US (MIT; from cmudict-ipa, MIT, and the CMU Pronouncing Dictionary, "
    "BSD-style), words limited to hermitdave/FrequencyWords en_50k (OpenSubtitles 2018, CC BY-SA 4.0).\n"
    "# This file is licensed CC BY-SA 4.0. Format: word<TAB>ipa[,ipa]. Do not edit: run the script.\n"
)


def clean_pronunciation(text):
    value = text.strip().strip("/").strip()
    for old, new in REPLACEMENTS.items():
        value = value.replace(old, new)
    # Learner dictionaries mark stress only on words of more than one syllable: /ɡoʊ/, /ˈwɔtɝ/.
    if syllables(value) <= 1:
        value = "".join(char for char in value if char not in STRESS_MARKS)
    return value


def syllables(pronunciation):
    count = 0
    previous_vowel = False
    for char in pronunciation:
        vowel = char in VOWELS
        if vowel and not previous_vowel:
            count += 1
        previous_vowel = vowel
    return count


def read_dictionary(path):
    entries = {}
    with open(path, encoding="utf-8") as source:
        for line in source:
            word, _, pronunciations = line.rstrip("\n").partition("\t")
            if not word or not pronunciations:
                continue
            cleaned = []
            for item in pronunciations.split(","):
                value = clean_pronunciation(item)
                if value and value not in cleaned and "," not in value:
                    cleaned.append(value)
            if cleaned:
                entries[word] = cleaned[:MAX_PRONUNCIATIONS]
    return entries


def read_frequent_words(path):
    words = []
    with open(path, encoding="utf-8") as source:
        for line in source:
            word = line.split(" ", 1)[0].strip().lower()
            if word:
                words.append(word)
    return words


def build(dictionary, frequent):
    kept = {}
    for word in frequent:
        if word in dictionary and all(char.isalpha() or char in "'-" for char in word):
            kept[word] = dictionary[word]
    return "".join(f"{word}\t{','.join(kept[word])}\n" for word in sorted(kept))


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    dictionary = read_dictionary(argv[1])
    frequent = read_frequent_words(argv[2])
    body = build(dictionary, frequent)
    with open(os.path.join(argv[3], "en-ipa.txt"), "w", encoding="utf-8", newline="\n") as output:
        output.write(HEADER)
        output.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
