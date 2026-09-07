"""Reject increases in legacy formatting violations per file and rule.

Counts tolerate line movement during focused edits. New files have a zero budget.
The initial budget is derived from the pre-change checkout, never current code.
"""
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET


def counts(path):
    result = {}
    for item in ET.parse(path).getroot().findall("file"):
        result[item.attrib["name"].replace("\\", "/")] = dict(
            collections.Counter(error.attrib["source"] for error in item.findall("error"))
        )
    return result


if __name__ == "__main__":
    baseline = json.loads(pathlib.Path("config/quality/format-budget.json").read_text())
    current = counts("build/reports/format.xml")
    failures = []
    for file, rules in current.items():
        for rule, count in rules.items():
            allowed = baseline.get(file, {}).get(rule, 0)
            if count > allowed:
                failures.append(f"{file}: {rule}: {count} > existing budget {allowed}")
    print("\n".join(failures) if failures else "Formatting ratchet passed (new files require zero violations).")
    sys.exit(bool(failures))
