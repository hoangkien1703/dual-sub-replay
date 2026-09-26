"""Offline checks for the GitHub Pages landing site in site/."""

import json
import re
import unittest
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "site"
BASE_URL = "https://hoangkien1703.github.io/dual-sub-replay/"
PAGES = {"index.html": BASE_URL, "vi/index.html": BASE_URL + "vi/"}
LANGUAGES_SOURCE = (
    ROOT / "app/src/main/java/com/kienhoang/dualsubreplay/translation/TranslationLanguages.kt"
)


class PageParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.title = ""
        self.meta = {}
        self.links = []
        self.refs = []
        self.json_ld = []
        self.summaries = []
        self.language_items = []
        self._capture = None
        self._buffer = ""
        self._in_langs = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "title":
            self._start("title")
        elif tag == "meta":
            key = attrs.get("name") or attrs.get("property")
            if key:
                self.meta[key] = attrs.get("content", "")
        elif tag == "link":
            self.links.append(attrs)
        elif tag == "script" and attrs.get("type") == "application/ld+json":
            self._start("json")
        elif tag == "summary":
            self._start("summary")
        elif tag == "ul" and attrs.get("class") == "langs":
            self._in_langs = True
        elif tag == "li" and self._in_langs:
            self._start("lang")
        for key in ("href", "src"):
            if tag != "link" and key in attrs:
                self.refs.append(attrs[key])
        if tag == "link" and attrs.get("rel") in ("icon", "stylesheet"):
            self.refs.append(attrs["href"])

    def handle_endtag(self, tag):
        if tag == "ul":
            self._in_langs = False
        if self._capture is None:
            return
        text = self._buffer.strip()
        if tag == "title" and self._capture == "title":
            self.title = text
        elif tag == "script" and self._capture == "json":
            self.json_ld.append(json.loads(text))
        elif tag == "summary" and self._capture == "summary":
            self.summaries.append(text)
        elif tag == "li" and self._capture == "lang":
            self.language_items.append(text)
        else:
            return
        self._capture = None

    def handle_data(self, data):
        if self._capture is not None:
            self._buffer += data

    def _start(self, kind):
        self._capture = kind
        self._buffer = ""


def parse(relative_path):
    parser = PageParser()
    parser.feed((SITE / relative_path).read_text(encoding="utf-8"))
    return parser


def published_images():
    workflow = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
    return set(re.findall(r"docs/images/([\w.-]+)", workflow))


class SiteTest(unittest.TestCase):
    def test_pages_have_search_metadata(self):
        for path, url in PAGES.items():
            with self.subTest(path=path):
                page = parse(path)
                self.assertTrue(10 <= len(page.title) <= 65, page.title)
                description = page.meta.get("description", "")
                self.assertTrue(70 <= len(description) <= 160, description)
                canonical = [link["href"] for link in page.links if link.get("rel") == "canonical"]
                self.assertEqual([url], canonical)
                self.assertEqual(url, page.meta.get("og:url"))
                self.assertTrue(page.meta.get("og:image", "").startswith(BASE_URL + "images/"))

    def test_hreflang_alternates_are_reciprocal(self):
        expected = {("en", PAGES["index.html"]), ("vi", PAGES["vi/index.html"]), ("x-default", BASE_URL)}
        for path in PAGES:
            with self.subTest(path=path):
                page = parse(path)
                alternates = {
                    (link["hreflang"], link["href"])
                    for link in page.links
                    if link.get("rel") == "alternate" and "hreflang" in link
                }
                self.assertEqual(expected, alternates)

    def test_structured_data_matches_visible_faq(self):
        for path in PAGES:
            with self.subTest(path=path):
                page = parse(path)
                types = {item["@type"] for item in page.json_ld}
                self.assertEqual({"MobileApplication", "FAQPage"}, types)
                app = next(item for item in page.json_ld if item["@type"] == "MobileApplication")
                self.assertEqual("0", app["offers"]["price"])
                self.assertNotIn("aggregateRating", app)
                faq = next(item for item in page.json_ld if item["@type"] == "FAQPage")
                questions = [entry["name"] for entry in faq["mainEntity"]]
                self.assertEqual(page.summaries, questions)
                html = (SITE / path).read_text(encoding="utf-8")
                for entry in faq["mainEntity"]:
                    self.assertIn("<p>" + entry["acceptedAnswer"]["text"] + "</p>", html)

    def test_local_links_resolve_in_published_site(self):
        images = published_images()
        for path, url in PAGES.items():
            with self.subTest(path=path):
                for ref in parse(path).refs:
                    target = urlparse(urljoin(url, ref))
                    if target.netloc != urlparse(BASE_URL).netloc or ref.startswith("#"):
                        continue
                    relative = target.path.removeprefix(urlparse(BASE_URL).path)
                    if relative.startswith("images/"):
                        name = relative.removeprefix("images/")
                        self.assertIn(name, images, ref)
                        self.assertTrue((ROOT / "docs/images" / name).is_file(), ref)
                    else:
                        file = SITE / (relative or "index.html")
                        if relative.endswith("/") or not relative:
                            file = SITE / relative / "index.html"
                        self.assertTrue(file.is_file(), ref)

    def test_sitemap_lists_every_page(self):
        namespace = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9"}
        root = ET.parse(SITE / "sitemap.xml").getroot()
        locations = {node.text for node in root.findall("s:url/s:loc", namespace)}
        self.assertEqual(set(PAGES.values()), locations)

    def test_language_list_matches_app(self):
        source = LANGUAGES_SOURCE.read_text(encoding="utf-8")
        app_languages = re.findall(r'TranslationLanguageOption\("[^"]+", "([^"]+)"\)', source)
        page = parse("index.html")
        self.assertEqual(app_languages, page.language_items)
        self.assertIn(f"these {len(app_languages)} languages", (SITE / "index.html").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
