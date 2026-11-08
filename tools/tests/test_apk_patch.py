import hashlib
import io
import json
from pathlib import Path
import random
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apk_patch
import automatic_release as release

# The app's unit test applies this patch (UpdatePatchesTest), so both sides read the same format.
FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/resources/update-patch"


def archive(entries):
    """A zip like an APK: fixed dates, some entries stored and some deflated."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for name, data, kind in entries:
            info = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 2))
            info.compress_type = kind
            output.writestr(info, data)
    return buffer.getvalue()


def fixture_archives():
    """Old and new 'APKs': the same library and data, a changed classes.dex and a new file."""
    noise = random.Random(7)
    library = bytes(noise.getrandbits(8) for _ in range(20_000))
    data = ("word\tmeaning\n" * 800).encode()
    old = archive([("AndroidManifest.xml", b"<manifest version='1'/>", zipfile.ZIP_DEFLATED),
                   ("classes.dex", b"dex one " * 600, zipfile.ZIP_DEFLATED),
                   ("lib/arm64-v8a/libtranslate_jni.so", library, zipfile.ZIP_DEFLATED),
                   ("assets/words.txt", data, zipfile.ZIP_STORED)])
    new = archive([("AndroidManifest.xml", b"<manifest version='2'/>", zipfile.ZIP_DEFLATED),
                   ("classes.dex", b"dex two " * 600, zipfile.ZIP_DEFLATED),
                   ("lib/arm64-v8a/libtranslate_jni.so", library, zipfile.ZIP_DEFLATED),
                   ("assets/words.txt", data, zipfile.ZIP_STORED),
                   ("assets/new.txt", b"new file", zipfile.ZIP_STORED)])
    return old, new


class ApkPatchTest(unittest.TestCase):
    def setUp(self):
        self.old, self.new = fixture_archives()

    def test_patch_rebuilds_the_new_apk_exactly(self):
        patch = apk_patch.make_patch(self.old, self.new)
        self.assertEqual(apk_patch.apply_patch(self.old, patch), self.new)

    def test_unchanged_entries_are_copied_not_downloaded(self):
        plan = apk_patch.copy_plan(self.old, self.new)
        copied = sum(length for _, _, length in plan)
        # The 20 kB library and the stored word list come from the installed APK.
        self.assertGreater(copied, 30_000)
        self.assertLess(len(apk_patch.make_patch(self.old, self.new)), len(self.new) // 4)

    def test_patch_refuses_another_old_apk(self):
        patch = apk_patch.make_patch(self.old, self.new)
        with self.assertRaisesRegex(ValueError, "another APK"):
            apk_patch.apply_patch(self.old + b"x", patch)

    def test_damaged_patch_is_refused(self):
        patch = bytearray(apk_patch.make_patch(self.old, self.new))
        patch[0] ^= 1
        with self.assertRaisesRegex(ValueError, "Not an update patch"):
            apk_patch.apply_patch(self.old, bytes(patch))
        # New SHA-256 in the header no longer matches what the operations make.
        patch = bytearray(apk_patch.make_patch(self.old, self.new))
        patch[60] ^= 1
        with self.assertRaisesRegex(ValueError, "not the one"):
            apk_patch.apply_patch(self.old, bytes(patch))

    def test_unrelated_files_still_patch(self):
        old, new = b"not a zip at all", b"neither is this"
        self.assertEqual(apk_patch.apply_patch(old, apk_patch.make_patch(old, new)), new)

    def test_release_patches_name_each_earlier_release(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "new.apk").write_bytes(self.new)
            for version, code, apk in (("1.8.23", 115, self.old), ("1.8.24", 116, self.old)):
                (root / "old" / version).mkdir(parents=True)
                (root / "old" / version / "DualSub-Replay.apk").write_bytes(apk)
                (root / "old" / version / "update-info.json").write_text(json.dumps(
                    {"version": version, "version_code": code, "sha256": hashlib.sha256(apk).hexdigest()}))
            # An APK that does not match its own update-info.json gets no patch.
            (root / "old" / "1.8.22").mkdir()
            (root / "old" / "1.8.22" / "DualSub-Replay.apk").write_bytes(b"other")
            (root / "old" / "1.8.22" / "update-info.json").write_text(json.dumps(
                {"version": "1.8.22", "version_code": 114, "sha256": "0" * 64}))
            (root / "out").mkdir()
            entries = apk_patch.make_release_patches(root / "new.apk", root / "old", root / "out")
            self.assertEqual([entry["from_version_code"] for entry in entries], [116, 115])
            first = entries[0]
            self.assertEqual(first["name"], "update-from-v1.8.24.patch")
            self.assertEqual(first["from_sha256"], hashlib.sha256(self.old).hexdigest())
            patch = (root / "out" / first["name"]).read_bytes()
            self.assertEqual(first["sha256"], hashlib.sha256(patch).hexdigest())
            self.assertEqual(first["size"], len(patch))
            info = release.update_info("1.8.25", 117, 0, root / "new.apk", entries)
            self.assertEqual(info["patches"], entries)
            self.assertNotIn("patches", release.update_info("1.8.25", 117, 0, root / "new.apk", []))

    def test_app_fixture_matches_this_tool(self):
        # The fixture was made by this tool (python3 tools/tests/test_apk_patch.py --write-fixture);
        # zlib versions may compress differently, so check that it applies rather than byte equality.
        old = (FIXTURE / "old.zip").read_bytes()
        patch = (FIXTURE / "new-from-old.patch").read_bytes()
        self.assertEqual(apk_patch.apply_patch(old, patch), (FIXTURE / "new.zip").read_bytes())
        self.assertGreater(sum(length for _, _, length in apk_patch.copy_plan(old, (FIXTURE / "new.zip").read_bytes())), 30_000)


if __name__ == "__main__":
    if "--write-fixture" in sys.argv:
        old, new = fixture_archives()
        FIXTURE.mkdir(parents=True, exist_ok=True)
        (FIXTURE / "old.zip").write_bytes(old)
        (FIXTURE / "new.zip").write_bytes(new)
        (FIXTURE / "new-from-old.patch").write_bytes(apk_patch.make_patch(old, new))
    else:
        unittest.main()
