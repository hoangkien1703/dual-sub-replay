#!/usr/bin/env python3
"""
Write the README's downloads badge as a shields.io endpoint file.

Reads the releases API pages that `gh api --paginate --slurp` prints on stdin and writes
{"schemaVersion": 1, "label": "downloads", "message": "5.6k", ...} on stdout. pages.yml
publishes it as downloads.json on the site, and the README badge reads it through
https://img.shields.io/endpoint.

Only real downloads count (owner pick 2026-11-11): the APK and the update patches that replace
it for phones that update. Not update-info.json (the app reads it once a day to check for a new
version), not the .sha256 files, and not the `preview` prerelease that holds PR test builds.
site/downloads.js counts the same way.
"""

import json
import sys

COUNTED_SUFFIXES = (".apk", ".patch")


def releases_from(text):
    """The releases in `--slurp` output (a list of pages) or in one page (a list of releases)."""
    data = json.loads(text)
    if data and all(isinstance(page, list) for page in data):
        return [release for page in data for release in page]
    return data


def count_downloads(releases):
    return sum(
        asset["download_count"]
        for release in releases
        if not release.get("prerelease") and not release.get("draft")
        for asset in release.get("assets", [])
        if asset["name"].endswith(COUNTED_SUFFIXES)
    )


def short_number(n):
    """5627 -> "5.6k", like shields.io's own download badges."""
    for size, unit in ((1_000_000, "M"), (1_000, "k")):
        if n >= size:
            text = f"{n / size:.1f}".removesuffix(".0")
            return text + unit
    return str(n)


def badge(total):
    return {
        "schemaVersion": 1,
        "label": "downloads",
        "message": short_number(total),
        "color": "brightgreen",
    }


def main():
    total = count_downloads(releases_from(sys.stdin.read()))
    if total <= 0:
        sys.exit("No downloads counted; the releases API reply looks wrong.")
    json.dump(badge(total), sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
