#!/usr/bin/env python3
"""
Tell search engines about changed landing site pages through IndexNow (https://www.indexnow.org).

pages.yml runs this after each deploy. One request to api.indexnow.org reaches Bing, Yandex,
Seznam, Naver and the other IndexNow engines, so new or edited pages show up there (and in the
AI answers built on Bing, such as Copilot and ChatGPT search) without a manual submission.
Brave Search does not take IndexNow; it still needs search.brave.com/submit-url by hand.

The key is public by design: site/<key>.txt holds the key and is served next to the pages, and
keyLocation points at it, which limits the key to URLs under /dual-sub-replay/.

Usage:
  git diff --name-only A B | python3 tools/indexnow.py --changed [--send]
  python3 tools/indexnow.py --all [--send]
Without --send it only prints the request it would make.
"""

import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
BASE_URL = "https://hoangkien1703.github.io/dual-sub-replay/"
HOST = "hoangkien1703.github.io"
ENDPOINT = "https://api.indexnow.org/indexnow"
KEY_NAME = re.compile(r"^[0-9a-f]{32}\.txt$")
# Text files that are not pages but are worth indexing; AI crawlers read llms.txt.
EXTRA_FILES = ("llms.txt",)


def find_key(site=SITE):
    """The IndexNow key: the one site/<32 hex>.txt whose content is its own name."""
    files = [path for path in site.iterdir() if KEY_NAME.match(path.name)]
    if len(files) != 1:
        raise SystemExit(f"expected one IndexNow key file in {site}, found {len(files)}")
    key = files[0].stem
    if files[0].read_text(encoding="utf-8").strip() != key:
        raise SystemExit(f"{files[0].name} must contain only its own key")
    return key


def sitemap_urls(site=SITE):
    namespace = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9"}
    root = ET.parse(site / "sitemap.xml").getroot()
    return [node.text for node in root.findall("s:url/s:loc", namespace)]


def url_for(changed_path):
    """The published URL of a changed repo file, or None when it is not a page."""
    if not changed_path.startswith("site/"):
        return None
    relative = changed_path.removeprefix("site/")
    if relative in EXTRA_FILES:
        return BASE_URL + relative
    if relative == "index.html" or relative.endswith("/index.html"):
        return BASE_URL + relative.removesuffix("index.html")
    return None


def changed_urls(changed_paths, known_urls):
    """URLs to submit for these changed files: listed pages (sitemap) and EXTRA_FILES only.

    Pages left out of the sitemap, such as the noindex setup/ page, are never submitted.
    """
    allowed = set(known_urls) | {BASE_URL + name for name in EXTRA_FILES}
    urls = []
    for path in changed_paths:
        url = url_for(path.strip())
        if url in allowed and url not in urls:
            urls.append(url)
    return urls


def payload(key, urls):
    return {
        "host": HOST,
        "key": key,
        "keyLocation": f"{BASE_URL}{key}.txt",
        "urlList": urls,
    }


def send(body):
    """POST to IndexNow. Returns the process exit code.

    400/403/422 mean our request or key is wrong, so the job fails and someone looks.
    429 or a server error is IndexNow's side; warn and let the deploy stay green.
    """
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except urllib.error.URLError as error:
        print(f"::warning::IndexNow unreachable: {error.reason}")
        return 0
    print(f"IndexNow answered HTTP {status}")
    if status in (200, 202):
        return 0
    if status in (400, 403, 422):
        print(f"::error::IndexNow refused the request (HTTP {status}); check the key file.")
        return 1
    print(f"::warning::IndexNow returned HTTP {status}; pages were not submitted this time.")
    return 0


def main(argv):
    if "--all" in argv:
        urls = sitemap_urls() + [BASE_URL + name for name in EXTRA_FILES]
    elif "--changed" in argv:
        urls = changed_urls(sys.stdin.read().splitlines(), sitemap_urls())
    else:
        raise SystemExit(__doc__)
    if not urls:
        print("No page changed; nothing to submit.")
        return 0
    body = payload(find_key(), urls)
    print(json.dumps(body, indent=2))
    return send(body) if "--send" in argv else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
