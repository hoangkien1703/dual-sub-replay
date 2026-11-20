#!/usr/bin/env python3
"""Lists the files in an outside PR that need a careful look before anyone approves its CI run.

Owner request 2026-11-15 (docs/specs/2026-11-15-flag-risky-outside-prs.md). The workflow
`.github/workflows/outside-pr-flags.yml` feeds this the PR's file list from GitHub's API (JSON, one
file object per line or one array). Nothing here runs or imports the PR's code: file names and diff
text are only matched against patterns, and everything quoted back is cleaned first, so a hostile
file name cannot break out of the comment's formatting or mention anyone.

Usage: pr_review_flags.py FILES_JSON --changed-files N --body OUT.md
Prints `risky=true` or `risky=false` (for $GITHUB_OUTPUT) and writes the comment body.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

MARKER = "<!-- outside-pr-review-flags -->"
LABEL = "needs careful review"
MAX_LISTED = 25
LARGE_FILE_ADDITIONS = 3000

# Files that run on CI, on the owner's computer, or when the folder is opened in an editor.
RUNS_CODE = [
    ".github/*",
    "*.gradle", "*.gradle.kts", "gradle.properties", "gradle/*", "gradlew", "gradlew.bat", "buildSrc/*",
    "tools/*",
    # Unit, device and benchmark tests run on CI as soon as the run is approved.
    "app/src/test/*", "app/src/androidTest/*", "benchmark/*",
    "*.sh", "*.bash", "*.zsh", "*.ps1", "*.psm1", "*.bat", "*.cmd", "*.py", "*.js", "*.mjs", "*.cjs",
    "*.ts", "*.tsx", "*.rb", "*.pl",
    "package.json", "*/package.json", "package-lock.json", "*/package-lock.json", "*.lock",
    "Makefile", "*/Makefile", "Dockerfile", "*/Dockerfile", ".devcontainer/*", ".vscode/*", ".idea/*",
    ".pre-commit-config.yaml", ".husky/*", ".gitmodules", ".envrc",
]
# Files that AI agents (the owner's reviewers too) read as instructions.
AGENT_INSTRUCTIONS = [
    "AGENTS.md", "*/AGENTS.md", "CLAUDE.md", "*/CLAUDE.md", ".claude/*", ".cursor/*", ".cursorrules",
    ".mcp.json", ".windsurfrules", "GEMINI.md", ".github/copilot-instructions.md",
]
# What the installed app may do: permissions, exported parts, network rules, code shrinking.
APP_PERMISSIONS = ["*AndroidManifest.xml", "app/src/main/res/xml/network_security_config*", "app/proguard-rules.pro"]
SECRETS = [
    "*.jks", "*.keystore", "*.p12", "*.pfx", "*.pem", "*.key", "keystore.properties", "*/keystore.properties",
    "local.properties", "google-services.json", "*/google-services.json", ".env", "*.env",
]
BINARY_EXTENSIONS = {
    ".jar", ".aar", ".apk", ".aab", ".dex", ".so", ".dylib", ".dll", ".exe", ".bin", ".class", ".o", ".a",
    ".wasm", ".zip", ".gz", ".tgz", ".xz", ".bz2", ".7z", ".rar", ".tar", ".db", ".sqlite", ".onnx", ".tflite",
}
# Added app code that loads or runs code, or opens the WebView to app code: rare here, so worth a look.
RISKY_CODE = {
    "runs a system command": re.compile(r"Runtime\.getRuntime\(\)\.exec|ProcessBuilder\("),
    "loads code at run time": re.compile(r"\b(Dex|Path|InMemoryDex|URL)ClassLoader\b|System\.load(Library)?\("),
    "gives web pages access to app code": re.compile(r"addJavascriptInterface\("),
    "decodes hidden data": re.compile(r"Base64\.(getDecoder|decode)|\bfromHex\("),
}
PERMISSION = re.compile(r"<uses-permission[^>]*android:name\s*=\s*\"([A-Za-z0-9_.]+)\"")
EXPORTED = re.compile(r"android:exported\s*=\s*\"true\"")
HOST = re.compile(r"https?://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)")
NEW_REPOSITORY = re.compile(r"\bmaven\s*[({]|\burl\s*=\s*uri\(|\bmavenLocal\(|\bivy\s*[({]")
APP_CODE = (".kt", ".java", ".xml", ".js", ".html")
DEPENDENCY = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*=\s*(.*)$")


def matches(path: str, patterns: list[str]) -> bool:
    name = path.rsplit("/", 1)[-1]
    for pattern in patterns:
        if pattern.endswith("/*") and path.startswith(pattern[:-1]):
            return True
        if fnmatch.fnmatchcase(path, pattern) or ("/" not in pattern and fnmatch.fnmatchcase(name, pattern)):
            return True
    return False


def clean(text: str, limit: int = 160) -> str:
    """Text safe inside a Markdown code span: no backticks, line breaks or control characters."""
    text = "".join(c if c.isprintable() else "?" for c in text).replace("`", "'")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def added_lines(file: dict) -> list[str]:
    patch = file.get("patch") or ""
    return [line[1:] for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++")]


@dataclass
class Flags:
    sections: dict[str, list[str]] = field(default_factory=dict)

    def add(self, section: str, line: str) -> None:
        entries = self.sections.setdefault(section, [])
        if line not in entries:
            entries.append(line)


def describe(file: dict) -> str:
    path = clean(file.get("filename", "?"))
    status = file.get("status", "modified")
    previous = file.get("previous_filename")
    extra = f", was `{clean(previous)}`" if previous else ""
    return f"`{path}` ({status}{extra})"


def classify(files: list[dict], changed_files: int) -> Flags:
    flags = Flags()
    for file in files:
        names = [file.get("filename", "")] + ([file["previous_filename"]] if file.get("previous_filename") else [])
        status = file.get("status", "modified")
        label = describe(file)
        added = added_lines(file)

        if any(matches(n, RUNS_CODE) for n in names):
            flags.add("Runs on CI or on your computer", label)
        if any(matches(n, AGENT_INSTRUCTIONS) for n in names):
            flags.add("Instructions for AI agents (could steer a reviewing agent)", label)
        if any(matches(n, SECRETS) for n in names):
            flags.add("Keys, signing or secrets files", label)

        path = names[0]
        suffix = path[path.rfind("."):].lower() if "." in path.rsplit("/", 1)[-1] else ""
        if status != "removed":
            if suffix in BINARY_EXTENSIONS:
                flags.add("Binary or very large files (ask where they came from)", label)
            elif not file.get("patch") and (file.get("changes", 0) > 0 or status == "added"):
                flags.add("Binary or very large files (ask where they came from)", f"{label}, no diff shown by GitHub")
            elif file.get("additions", 0) >= LARGE_FILE_ADDITIONS:
                flags.add("Binary or very large files (ask where they came from)",
                          f"{label}, {file['additions']} lines added")

        if any(matches(n, APP_PERMISSIONS) for n in names):
            flags.add("App permissions, exported parts or network rules", label)
            for line in added:
                for permission in PERMISSION.findall(line):
                    flags.add("App permissions, exported parts or network rules", f"new permission `{permission}`")
                if EXPORTED.search(line):
                    flags.add("App permissions, exported parts or network rules",
                              f"something set `android:exported=\"true\"` in `{clean(path)}`")

        if path.endswith("libs.versions.toml"):
            for line in added:
                found = DEPENDENCY.match(line)
                if found and not line.lstrip().startswith("#"):
                    flags.add("New or changed libraries", f"`{clean(found.group(1), 60)} = {clean(found.group(2), 100)}`")
        if path.endswith((".gradle", ".gradle.kts")):
            for line in added:
                if NEW_REPOSITORY.search(line):
                    flags.add("New or changed libraries",
                              f"new download source in `{clean(path)}`: `{clean(line.strip(), 100)}`")

        if path.startswith("app/src/main/") and path.endswith(APP_CODE):
            for line in added:
                for host in HOST.findall(line):
                    flags.add("Internet addresses added to app code", f"`{host.lower()}` in `{clean(path)}`")
                for what, pattern in RISKY_CODE.items():
                    if pattern.search(line):
                        flags.add("App code that needs a close look", f"`{clean(path)}` {what}")

    if changed_files > len(files):
        flags.add("Runs on CI or on your computer",
                  f"GitHub listed only {len(files)} of {changed_files} changed files; check the rest by hand")
    return flags


def render(flags: Flags, head_sha: str) -> str:
    sha = clean(head_sha, 40)
    if not flags.sections:
        return (f"{MARKER}\n**Outside PR check:** commit `{sha}` no longer touches files that need a careful "
                "look, so the label is gone. The usual review still applies.\n")
    lines = [
        MARKER,
        f"**Outside PR check: please look at these files closely before approving CI** (commit `{sha}`).",
        "",
        "This PR comes from a fork. The files below can run code on CI or on a computer, change what the app "
        "may do, or bring in files that can't be read as text. Nothing here says they are harmful; it says "
        "a person should read them first. Approve the workflow run only once they look right: approving runs "
        "all of this PR's code, its tests too, on GitHub's machines.",
    ]
    for section, entries in flags.sections.items():
        lines += ["", f"**{section}**"]
        lines += [f"- {entry}" for entry in entries[:MAX_LISTED]]
        if len(entries) > MAX_LISTED:
            lines.append(f"- …and {len(entries) - MAX_LISTED} more")
    lines += ["", "_Written by the `outside-pr-flags` workflow from GitHub's file list; it ran none of this PR's code._"]
    return "\n".join(lines) + "\n"


def load(path: Path) -> list[dict]:
    """File objects from a JSON array, a list of pages, or a stream of objects (`gh api --jq '.[]'`)."""
    text = path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    items: list = []
    position = 0
    while True:
        while position < len(text) and text[position].isspace():
            position += 1
        if position >= len(text):
            break
        value, position = decoder.raw_decode(text, position)
        items.append(value)
    files: list[dict] = []
    for item in items:
        if isinstance(item, dict):
            files.append(item)
        elif isinstance(item, list):
            for entry in item:
                files.extend(entry if isinstance(entry, list) else [entry])
    return [f for f in files if isinstance(f, dict)]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", type=Path)
    parser.add_argument("--changed-files", type=int, default=0)
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--body", type=Path, required=True)
    args = parser.parse_args(argv)
    flags = classify(load(args.files), args.changed_files)
    args.body.write_text(render(flags, args.head_sha), encoding="utf-8")
    print(f"risky={'true' if flags.sections else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
