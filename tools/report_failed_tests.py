#!/usr/bin/env python3
"""
List failed tests from JUnit XML results in the CI job summary.

Since AGP 9.4 the managed-device test task only says "There were failing tests", so the failing
names were only in the report artifact (owner request 2026-10-27). This prints each failed test
with the first lines of its error to the log, as a GitHub error annotation, and to the summary.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MESSAGE_LINES = 3
MESSAGE_CHARS = 600


def short_message(text: str) -> str:
    lines = [line.strip() for line in (text or "").strip().splitlines() if line.strip()]
    kept = [line for line in lines if not line.startswith(("at ", "..."))][:MESSAGE_LINES]
    message = "\n".join(kept)
    return message if len(message) <= MESSAGE_CHARS else message[: MESSAGE_CHARS - 1] + "…"


def failed_tests(result_dirs):
    """Returns sorted (test name, short message) pairs for every failed or errored test case."""
    failures = set()
    for result_dir in result_dirs:
        for xml_file in sorted(Path(result_dir).rglob("*.xml")):
            try:
                root = ET.parse(xml_file).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                if problem is None:
                    continue
                class_name = case.get("classname", "").rsplit(".", 1)[-1]
                name = f"{class_name}.{case.get('name', '')}" if class_name else case.get("name", "")
                message = short_message(problem.text or problem.get("message", ""))
                failures.add((name, message))
    return sorted(failures)


def summary_markdown(title: str, failures) -> str:
    if not failures:
        return f"### {title}\nNo failed tests found.\n"
    parts = [f"### {title}: {len(failures)} failed\n"]
    for name, message in failures:
        parts.append(f"- **{name}**\n")
        if message:
            parts.append("  ```\n" + "\n".join("  " + line for line in message.splitlines()) + "\n  ```\n")
    return "".join(parts)


def annotation(name: str, message: str) -> str:
    # GitHub workflow commands need %, CR and LF escaped.
    text = f"{name}: {message}" if message else name
    text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return f"::error title=Test failed::{text}"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_dirs", nargs="+", help="Folders holding JUnit XML results")
    parser.add_argument("--title", default="Failed tests")
    args = parser.parse_args(argv)

    failures = failed_tests(args.result_dirs)
    for name, message in failures:
        print(annotation(name, message))
    markdown = summary_markdown(args.title, failures)
    print(markdown)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
