"""Checks for tools/indexnow.py, the IndexNow ping after each site deploy."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import indexnow  # noqa: E402

BASE = indexnow.BASE_URL
SITEMAP = [BASE, BASE + "vi/", BASE + "learn-english/", BASE + "vi/learn-english/"]


class IndexNowTest(unittest.TestCase):
    def test_key_file_is_published_and_valid(self):
        key = indexnow.find_key()
        self.assertRegex(key, r"^[0-9a-f]{32}$")
        # pages.yml copies all of site/ into the published site, so the key file goes live too.
        self.assertTrue((indexnow.SITE / f"{key}.txt").is_file())

    def test_key_location_covers_every_submitted_url(self):
        # A key file in /dual-sub-replay/ only vouches for URLs under that folder.
        body = indexnow.payload("0" * 32, indexnow.sitemap_urls())
        folder = body["keyLocation"].rsplit("/", 1)[0] + "/"
        self.assertEqual(folder, BASE)
        self.assertEqual(body["host"], "hoangkien1703.github.io")
        for url in body["urlList"]:
            self.assertTrue(url.startswith(folder), url)

    def test_changed_pages_map_to_their_urls(self):
        changed = [
            "site/index.html",
            "site/vi/learn-english/index.html",
            "site/llms.txt",
            "site/styles.css",
            "docs/images/dualsub-replay-landscape.png",
            "app/src/main/AndroidManifest.xml",
        ]
        self.assertEqual(
            indexnow.changed_urls(changed, SITEMAP),
            [BASE, BASE + "vi/learn-english/", BASE + "llms.txt"],
        )

    def test_pages_outside_the_sitemap_are_never_submitted(self):
        # setup/ is noindex, so it is left out of the sitemap and must not be pinged either.
        self.assertEqual(indexnow.changed_urls(["site/setup/index.html"], SITEMAP), [])

    def test_duplicates_are_submitted_once(self):
        changed = ["site/index.html", "site/index.html"]
        self.assertEqual(indexnow.changed_urls(changed, SITEMAP), [BASE])


if __name__ == "__main__":
    unittest.main()
