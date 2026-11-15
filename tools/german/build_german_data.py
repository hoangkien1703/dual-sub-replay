#!/usr/bin/env python3
"""Builds the German learner data in app/src/main/learner-data/ from Wiktionary and a subtitle word list.

Usage:
  python3 tools/german/build_german_data.py kaikki.org-dictionary-German.jsonl de_50k.txt app/src/main/learner-data \
      [app/src/test/resources]

Inputs (both CC BY-SA 4.0, so the output files are CC BY-SA 4.0 too; credited in THIRD_PARTY_NOTICES.md):
- Wiktextract's German dictionary from English Wiktionary, https://kaikki.org/dictionary/German/
- hermitdave/FrequencyWords, OpenSubtitles 2018 German top 50,000 words (content/2018/de/de_50k.txt)

Outputs (deterministic: same inputs, same bytes; the inputs' SHA-256 go in each header):
- de-verbs.txt: one verb per line, most common first (by how often its forms appear in subtitles).
  `lemma|flags|3sg present|3sg preterite|participle II|subjunctive II (3sg, a/b)`. A part the rules in
  GermanConjugation.kt derive for a regular verb is left empty. flags: h = haben, s = sein, m = preterite-present
  (modal), r = reflexive (sich), then `+prefix` for a separable verb.
- de-words.txt: nouns `N|lemma|genders|genitives|plurals|flags` and adjectives `A|lemma|comparative|superlative`,
  most common first. genitives and plurals are `/`-separated forms, each `=` (same as the lemma), `+x` (lemma plus x) or in full; a participle field lists every participle II.
- With a fourth argument, test goldens: de-verbs-golden.tsv and de-nouns-golden.tsv, the forms Wiktionary's own
  tables give for a sample of the kept lemmas, so a test can compare the Kotlin conjugation with them.

Keep needs_e and the weak_* rules here in sync with GermanConjugation.kt (needsE, weakPresent3, weakPreterite, weakParticiple).
"""

import hashlib
import json
import os
import re
import sys

MAX_VERBS = 4500
MAX_NOUNS = 9000
MAX_ADJECTIVES = 3500
GOLDEN_VERBS = 160
GOLDEN_NOUNS = 220

SKIP_TAGS = {"subordinate-clause", "multiword-construction", "archaic", "dated", "obsolete", "rare", "poetic", "dialectal"}
GENDER_LETTER = {"masculine": "m", "feminine": "f", "neuter": "n"}
INFLECTION_TAGS = {
    "plural", "singular", "genitive", "dative", "accusative", "nominative", "participle", "imperative", "subjunctive",
    "indicative", "present", "past", "preterite", "infinitive", "comparative", "superlative", "first-person",
    "second-person", "third-person", "inflection",
}
# Real nouns whose lowercase is also a pronoun, adverb or particle (die Frau, das Mal); the rest of the
# function-word nouns are nominalisations (das Ich, das Hier) and are left out.
FUNCTION_WORD_NOUNS = {
    "frau", "zeit", "tag", "rede", "weg", "mensch", "platz", "paar", "augenblick", "bewegung", "ehe", "vorsicht",
    "richtung", "kraft", "sonne", "willen", "achtung", "respekt", "quatsch", "wahnsinn", "hintern", "tatsache",
    "schnauze", "gesundheit", "viertel", "hauptsache", "scheibe", "mahlzeit", "einspruch", "dank", "mist", "ober",
    "abflug", "abmarsch", "komma", "mithilfe", "alter", "ei", "bitte", "gut", "bar", "gen", "top", "entschuldigung",
    "verzeihung", "wiedersehen", "klappe", "hundert", "tausend", "null",
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clean(tags):
    return set(tags or [])


def first_form(forms, need, deny=SKIP_TAGS):
    """Forms whose tags include all of [need] and none of [deny], in table order, without duplicates."""
    found = []
    for item in forms:
        tags = clean(item.get("tags"))
        form = item.get("form", "")
        if need <= tags and not (tags & deny) and form not in ("-", "") and form not in found:
            found.append(form)
    return found


VOWELS = "aeiouäöüy"


def verb_stem(infinitive):
    return infinitive[:-2] if infinitive.endswith("en") else infinitive[:-1]


def needs_e(stem):
    """Whether -st/-t/-te take an e: arbeitet, atmet, rechnet, öffnet, but wohnt, kennt, lernt, kommt."""
    if stem.endswith(("d", "t")):
        return True
    if len(stem) < 2 or stem[-1] not in "mn":
        return False
    before = stem[-2]
    if before in VOWELS + "lr" or before == stem[-1]:
        return False
    return not (before == "h" and len(stem) > 2 and stem[-3] in VOWELS)


def weak_present3(infinitive):
    stem = verb_stem(infinitive)
    return stem + ("et" if needs_e(stem) else "t")


def weak_preterite(infinitive):
    stem = verb_stem(infinitive)
    return stem + ("ete" if needs_e(stem) else "te")


INSEPARABLE = ("be", "emp", "ent", "er", "ge", "miss", "ver", "zer")


def weak_participle(infinitive, prefix):
    stem = verb_stem(infinitive)
    ending = "et" if needs_e(stem) else "t"
    if prefix:
        return prefix + "ge" + stem + ending
    if infinitive.endswith("ieren") or infinitive.startswith(INSEPARABLE):
        return stem + ending
    return "ge" + stem + ending


def parse_verb(entry):
    word = entry["word"]
    forms = entry.get("forms", [])
    infinitive = word[5:] if word.startswith("sich ") else word
    reflexive = word.startswith("sich ") or any("reflexive" in sense.get("tags", []) for sense in entry.get("senses", []))
    present3 = first_form(forms, {"present", "singular", "third-person", "indicative"}) or first_form(forms, {"present", "singular", "third-person"})
    past3 = first_form(forms, {"preterite", "singular", "third-person", "indicative"}) or first_form(forms, {"past"}, SKIP_TAGS | {"subjunctive"})
    participle = first_form(forms, {"participle", "past"})
    subjunctive2 = first_form(forms, {"subjunctive-ii", "singular", "third-person"})
    if not (present3 and past3 and participle) or " " in infinitive:
        return None
    prefix = ""
    parts = present3[0].split(" ")
    if len(parts) == 2:
        prefix = parts[1]
    present3 = parts[0]
    past3 = past3[0].split(" ")[0]
    participles = list(dict.fromkeys(p.split(" ")[0] for p in participle))
    sub2 = [s.split(" ")[0] for s in subjunctive2]
    aux = [a for a in first_form(forms, {"auxiliary"}) if a in ("haben", "sein")]
    flags = ("h" if "haben" in aux else "") + ("s" if "sein" in aux else "")
    head = " ".join(h.get("expansion", "") for h in entry.get("head_templates", []))
    if "preterite-present" in head:
        flags += "m"
    if reflexive:
        flags += "r"
    base = infinitive[len(prefix):] if prefix and infinitive.startswith(prefix) else infinitive
    subjunctive_default = past3
    out_sub = [s for s in sub2 if s != subjunctive_default]
    fields = [
        infinitive,
        flags + ("+" + prefix if prefix else ""),
        "" if weak_present3(base) == present3 else present3,
        "" if weak_preterite(base) == past3 else past3,
        "" if participles == [weak_participle(base, prefix)] else "/".join(participles),
        "/".join(out_sub),
    ]
    golden = []
    for item in forms:
        tags = clean(item.get("tags"))
        form = item.get("form", "")
        if tags & SKIP_TAGS or " " in form or form in ("-", ""):
            continue
        if "indicative" in tags or "subjunctive" in tags or "imperative" in tags:
            person = "1" if "first-person" in tags else "2" if "second-person" in tags else "3" if "third-person" in tags else ""
            number = "s" if "singular" in tags else "p" if "plural" in tags else ""
            tense = (
                "pres" if "present" in tags and "subjunctive-i" not in tags and "imperative" not in tags and "subjunctive" not in tags
                else "prat" if "preterite" in tags and "subjunctive-ii" not in tags
                else "k1" if "subjunctive-i" in tags
                else "k2" if "subjunctive-ii" in tags
                else "imp" if "imperative" in tags
                else ""
            )
            if tense and person and number:
                golden.append((tense + person + number, form))
        elif "participle" in tags:
            golden.append(("pa1" if "present" in tags else "pa2", form))
    return infinitive, fields, golden, flags, bool(prefix)


def gender_of(entry):
    letters = set()
    for sense in entry.get("senses", []):
        for tag in sense.get("tags", []):
            if tag in GENDER_LETTER:
                letters.add(GENDER_LETTER[tag])
    if letters:
        return "".join(sorted(letters))
    # Many entries have no gender tag on their senses; the head line says it: "Lehrer m (strong, ...)", "Wand f".
    head = " ".join(h.get("expansion", "") for h in entry.get("head_templates", [])[:1])
    rest = head[len(entry["word"]):].lstrip() if head.startswith(entry["word"]) else ""
    marker = rest.split("(")[0]
    found = set(re.findall(r"\b(m|f|n)\b", marker))
    if re.search(r"\bmf\b", marker):
        found |= {"m", "f"}
    return "".join(sorted(found))


def compact(lemma, form):
    if form == lemma:
        return "="
    if form.startswith(lemma):
        return "+" + form[len(lemma):]
    return form


def parse_noun(entry):
    word = entry["word"]
    forms = entry.get("forms", [])
    genders = gender_of(entry)
    if not genders:
        return None
    heads = " ".join(h.get("expansion", "") for h in entry.get("head_templates", []))
    genitive = first_form(forms, {"genitive", "singular"}) or first_form(forms, {"genitive"}, SKIP_TAGS | {"plural", "diminutive"})
    plural = first_form(forms, {"nominative", "plural"}) or first_form(forms, {"plural"}, SKIP_TAGS | {"diminutive", "genitive"})
    flags = ""
    if "weak" in {t for s in entry.get("senses", []) for t in s.get("tags", [])} or "(weak" in heads:
        flags += "w"
    if "plural only" in heads or "plural-only" in heads or {"plural"} <= {t for s in entry.get("senses", []) for t in s.get("tags", [])} and not plural:
        flags += "p"
    if "no-plural" in heads or "uncountable" in heads or "singular only" in heads:
        flags += "u"
    gen = "/".join(compact(word, g) for g in genitive) if genitive else "="
    plu = "/".join(compact(word, g) for g in plural) if plural else "-"
    golden = []
    for item in forms:
        tags = clean(item.get("tags"))
        form = item.get("form", "")
        if tags & SKIP_TAGS or "definite" in tags or " " in form or "diminutive" in tags or "colloquial" in tags:
            continue
        case = next((c for c in ("nominative", "genitive", "dative", "accusative") if c in tags), None)
        number = "s" if "singular" in tags else "p" if "plural" in tags else None
        if case and number:
            golden.append((case[:3] + number, form))
    return word, ["N", word, genders, gen, plu, flags], golden


def parse_adjective(entry):
    word = entry["word"]
    forms = entry.get("forms", [])
    comp = first_form(forms, {"comparative"}, SKIP_TAGS | {"masculine", "feminine", "neuter", "plural"})
    sup = [f for f in first_form(forms, {"superlative"}) if f.startswith("am ")]
    if not comp:
        return None
    regular_comp = word + "er" if not word.endswith("e") else word + "r"
    sup_form = sup[0][3:] if sup else ""
    regular_sup = word + ("esten" if word.endswith(("d", "t", "s", "ß", "x", "z", "sch")) else "sten")
    return word, ["A", word, "" if comp[0] == regular_comp else comp[0], "" if sup_form in ("", regular_sup) else sup_form]


def main(argv):
    extract, frequency, out_dir = argv[1], argv[2], argv[3]
    test_dir = argv[4] if len(argv) > 4 else None
    ranks = {}
    with open(frequency, encoding="utf-8") as handle:
        for rank, line in enumerate(handle):
            word = line.split(" ")[0].strip()
            if word and word not in ranks:
                ranks[word] = rank
    # Lowercase words that are also pronouns, conjunctions, prepositions, particles or numerals (not adverbs:
    # many are real nouns, Weg, Mal, Morgen):
    # Wiktionary lists their nominalisations as nouns (Ich, Hier, Wenn), which are not worth a noun slot.
    function_pos = {"pron", "conj", "prep", "postp", "det", "intj", "particle", "num", "article", "contraction"}
    function_words = set()
    with open(extract, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith('{"pos": "') and line[9: line.index('"', 9)] in function_pos:
                function_words.add(json.loads(line)["word"].lower())
    verbs, nouns, adjectives = {}, {}, {}
    dropped = []
    seen_rank = {}

    def rank_of(entry):
        # Table metadata ("sein" tagged auxiliary) and rare cells (imperative, subjunctive I: "mal" of malen)
        # are not evidence that a word is common. A lemma counts when the lemma itself is in the list or at
        # least two of its forms are, so one homograph ("einen", "haten") does not rank a rare verb.
        skip = {"table-tags", "inflection-template", "class", "auxiliary", "romanization", "imperative", "subjunctive-i"}
        forms = [f.get("form", "") for f in entry.get("forms", []) if not (set(f.get("tags", [])) & skip)]
        matched = {form.lower() for form in forms if " " not in form and form.lower() in ranks}
        own = entry["word"].lower() in ranks
        if not own and len(matched) < 2:
            return None
        found = [ranks[m] for m in matched] + ([ranks[entry["word"].lower()]] if own else [])
        return min(found)

    with open(extract, encoding="utf-8") as handle:
        for line in handle:
            if '"pos": "verb"' not in line[:40] and '"pos": "noun"' not in line[:40] and '"pos": "adj"' not in line[:40]:
                continue
            entry = json.loads(line)
            if entry.get("lang_code", "de") != "de":
                continue
            senses = entry.get("senses", [])
            # An entry that only says "plural of Haus" is an inflected form; "agent noun of lehren" (Lehrer) is a word.
            if senses and all(("form-of" in s.get("tags", []) and INFLECTION_TAGS & set(s.get("tags", []))) or "alt-of" in s for s in senses):
                continue
            pos = entry["pos"]
            rank = rank_of(entry)
            if rank is None:
                continue
            word = entry["word"]
            if pos == "verb":
                parsed = parse_verb(entry)
                if parsed and (parsed[0] not in verbs or rank < verbs[parsed[0]][0]):
                    verbs[parsed[0]] = (rank, parsed)
            elif pos == "noun":
                parsed = None
                if word[:1].isupper() and len(word) > 1 and " " not in word and not word.isupper():
                    if word.lower() in function_words and word.lower() not in FUNCTION_WORD_NOUNS:
                        dropped.append((rank, word))
                    else:
                        parsed = parse_noun(entry)
                if parsed:
                    old = nouns.get(word)
                    if old is None:
                        nouns[word] = (rank, parsed)
                    elif parsed[1][2] not in old[1][1][2]:
                        merged = "".join(sorted(set(old[1][1][2]) | set(parsed[1][2])))
                        old[1][1][2] = merged
                        nouns[word] = (min(rank, old[0]), old[1])
            else:
                parsed = parse_adjective(entry)
                if parsed and word not in adjectives:
                    adjectives[word] = (rank, parsed)

    def ordered(table, limit):
        return sorted(table.items(), key=lambda kv: (kv[1][0], kv[0]))[:limit]

    header = [
        "# German learner data for DualSub Replay, made by tools/german/build_german_data.py.",
        "# Data: Wiktionary (via Wiktextract, kaikki.org) and hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0.",
        "# This file is licensed CC BY-SA 4.0. Most common first. Format: see the script.",
        f"# Inputs: {extract.split('/')[-1]} sha256 {sha256(extract)}; {frequency.split('/')[-1]} sha256 {sha256(frequency)}",
    ]
    keep_verbs = ordered(verbs, MAX_VERBS)
    with open(f"{out_dir}/de-verbs.txt", "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(header) + "\n")
        for _, (_, parsed) in keep_verbs:
            handle.write("|".join(parsed[1]) + "\n")
    keep_nouns = ordered(nouns, MAX_NOUNS)
    keep_adjectives = ordered(adjectives, MAX_ADJECTIVES)
    with open(f"{out_dir}/de-words.txt", "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(header) + "\n")
        for _, (_, parsed) in keep_nouns:
            handle.write("|".join(parsed[1]) + "\n")
        for _, (_, parsed) in keep_adjectives:
            handle.write("|".join(parsed[1]) + "\n")
    if test_dir:
        step = max(1, len(keep_verbs) // GOLDEN_VERBS)
        with open(f"{test_dir}/de-verbs-golden.tsv", "w", encoding="utf-8", newline="\n") as handle:
            handle.write("# Wiktionary's own forms for a sample of de-verbs.txt (CC BY-SA 4.0). lemma<TAB>cell<TAB>form\n")
            for _, (_, parsed) in keep_verbs[::step][:GOLDEN_VERBS]:
                for cell, form in parsed[2]:
                    handle.write(f"{parsed[0]}\t{cell}\t{form}\n")
        step = max(1, len(keep_nouns) // GOLDEN_NOUNS)
        with open(f"{test_dir}/de-nouns-golden.tsv", "w", encoding="utf-8", newline="\n") as handle:
            handle.write("# Wiktionary's own forms for a sample of de-words.txt (CC BY-SA 4.0). lemma<TAB>cell<TAB>form\n")
            for _, (_, parsed) in keep_nouns[::step][:GOLDEN_NOUNS]:
                for cell, form in parsed[2]:
                    handle.write(f"{parsed[0]}\t{cell}\t{form}\n")
    print(f"verbs {len(keep_verbs)} nouns {len(keep_nouns)} adjectives {len(keep_adjectives)}")
    if os.environ.get("GERMAN_REPORT_DROPPED"):
        print("dropped function-word nouns:", " ".join(w for _, w in sorted(dropped)[:150]))


if __name__ == "__main__":
    main(sys.argv)
