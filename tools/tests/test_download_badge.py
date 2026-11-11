"""Checks for tools/download_badge.py, the README downloads badge."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import download_badge  # noqa: E402

RELEASES = [
    {
        "tag_name": "v1.8.35",
        "prerelease": False,
        "assets": [
            {"name": "DualSub-Replay.apk", "download_count": 22},
            {"name": "DualSub-Replay.apk.sha256", "download_count": 2},
            {"name": "update-from-v1.8.34.patch", "download_count": 3},
            {"name": "update-info.json", "download_count": 40},
        ],
    },
    {
        "tag_name": "preview",
        "prerelease": True,
        "assets": [{"name": "DualSub-Replay-PR216-build700-preview.apk", "download_count": 9}],
    },
    {"tag_name": "v0.9.4", "prerelease": False, "assets": [{"name": "DualSub-Replay.apk", "download_count": 5}]},
]


class DownloadBadgeTest(unittest.TestCase):
    def test_counts_only_apks_and_patches_of_published_releases(self):
        self.assertEqual(30, download_badge.count_downloads(RELEASES))

    def test_reads_slurped_pages_and_a_single_page(self):
        pages = json.dumps([RELEASES[:2], RELEASES[2:]])
        self.assertEqual(RELEASES, download_badge.releases_from(pages))
        self.assertEqual(RELEASES, download_badge.releases_from(json.dumps(RELEASES)))

    def test_short_number_matches_shields(self):
        cases = {7: "7", 999: "999", 1000: "1k", 5627: "5.6k", 7858: "7.9k", 12_345: "12.3k", 2_000_000: "2M"}
        for number, text in cases.items():
            with self.subTest(number=number):
                self.assertEqual(text, download_badge.short_number(number))

    def test_badge_is_a_shields_endpoint(self):
        self.assertEqual(
            {"schemaVersion": 1, "label": "downloads", "message": "5.6k", "color": "brightgreen"},
            download_badge.badge(5627),
        )

    def test_site_script_counts_the_same_files(self):
        script = (Path(__file__).resolve().parents[2] / "site/downloads.js").read_text(encoding="utf-8")
        for suffix in download_badge.COUNTED_SUFFIXES:
            self.assertIn(f"'{suffix}'", script)
        self.assertIn("release.prerelease", script)


if __name__ == "__main__":
    unittest.main()
