"""Use a separately installed Anki Python backend against a disposable collection."""
import argparse
import pathlib
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--runtime", type=pathlib.Path, required=True)
parser.add_argument("--input", type=pathlib.Path, required=True)
parser.add_argument("--output", type=pathlib.Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.runtime.resolve()))
from anki.collection import Collection
from anki.import_export_pb2 import ImportCsvRequest

fields = ["word", "meaning", "reading", "wordLanguage", "meaningLanguage", "sentence",
          "translatedSentence", "source", "startMs", "endMs"]
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="anki-qa-") as directory:
    collection = Collection(str(pathlib.Path(directory) / "qa.anki2"))
    try:
        model = collection.models.new("DualSub Replay QA")
        for field in fields:
            collection.models.add_field(model, collection.models.new_field(field))
        template = collection.models.new_template("Card 1")
        template["qfmt"] = "{{word}}"
        template["afmt"] = "{{FrontSide}}<hr>{{meaning}}"
        collection.models.add_template(model, template)
        collection.models.add(model)
        metadata = collection.get_csv_metadata(str(args.input.resolve()), None)
        metadata.global_notetype.id = model["id"]
        metadata.global_notetype.field_columns[:] = range(1, 11)
        metadata.tags_column = 0
        collection.import_csv(ImportCsvRequest(path=str(args.input.resolve()), metadata=metadata))
        assert collection.note_count() == 1, "Expected the one-note test fixture"
        assert collection.export_note_csv(out_path=str(args.output.resolve()), limit=None,
            with_html=False, with_tags=False, with_deck=False, with_notetype=False, with_guid=False) == 1
        print("Real Anki import/export passed; validate the returned TSV with PracticeTransferTest.")
    finally:
        collection.close()
