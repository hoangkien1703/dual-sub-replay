"""Extracts the English tagger model and WordNet lists into browser-extension/data/learner-data/.

The same inputs and output as the Android app's build (app/build.gradle.kts, learnerData and writeWordNet):

    python3 -I tools/english/build_extension_english_data.py \\
        opennlp-models-pos-en-1.3.0.jar extjwnl-data-wn31-1.2.jar browser-extension/data/learner-data

Download both jars from Maven Central first:
    org/apache/opennlp/opennlp-models-pos-en/1.3.0  (SHA-1 2d7b648de2d1e531cc72f5c124a8d7e1a3c7837f)
    net/sf/extjwnl/extjwnl-data-wn31/1.2            (SHA-1 dddcd43a5c53574346ce3d9e4110a7b0296b147d)
"""

import hashlib
import io
import re
import sys
import zipfile

MODEL_JAR_SHA1 = "2d7b648de2d1e531cc72f5c124a8d7e1a3c7837f"
WORDNET_JAR_SHA1 = "dddcd43a5c53574346ce3d9e4110a7b0296b147d"
MODEL_ENTRY = "opennlp-en-ud-ewt-pos-1.3-2.5.4.bin"
WORDNET = "net/sf/extjwnl/data/wordnet/wn31"
WORD = re.compile(r"[a-z][a-z'-]*")


def checked(path, sha1):
    data = open(path, "rb").read()
    if hashlib.sha1(data).hexdigest() != sha1:
        sys.exit(f"{path}: SHA-1 does not match Maven Central's {sha1}")
    return zipfile.ZipFile(io.BytesIO(data))


def main(model_jar, wordnet_jar, out):
    # The GIS model inside OpenNLP's model package, as OpenNlpPosTagger.load reads it.
    package = zipfile.ZipFile(io.BytesIO(checked(model_jar, MODEL_JAR_SHA1).read(MODEL_ENTRY)))
    open(f"{out}/en-pos.model", "wb").write(package.read("pos.model"))

    # WordNet's single words with their parts of speech (run nv) and irregular forms (went v go).
    wordnet = checked(wordnet_jar, WORDNET_JAR_SHA1)
    parts, exceptions = {}, []
    for name, letter in (("noun", "n"), ("verb", "v"), ("adj", "a"), ("adv", "r")):
        for line in wordnet.read(f"{WORDNET}/index.{name}").decode("utf-8").splitlines():
            lemma = line.split(" ", 1)[0]
            if not line.startswith(" ") and WORD.fullmatch(lemma):
                parts[lemma] = parts.get(lemma, "") + letter
        for line in wordnet.read(f"{WORDNET}/{name}.exc").decode("utf-8").splitlines():
            fields = line.strip().split(" ")
            if len(fields) >= 2 and all(WORD.fullmatch(f) for f in fields):
                exceptions.append(f"{fields[0]} {letter} {' '.join(fields[1:])}")
    # Sorted, as Android writes them, so a rebuild gives the same files.
    with open(f"{out}/en-words.txt", "w", encoding="utf-8", newline="\n") as f:
        for lemma in sorted(parts):
            f.write(f"{lemma} {parts[lemma]}\n")
    with open(f"{out}/en-exceptions.txt", "w", encoding="utf-8", newline="\n") as f:
        for line in sorted(exceptions):
            f.write(line + "\n")
    print(f"en-pos.model, {len(parts)} words, {len(exceptions)} exceptions")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
