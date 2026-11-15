#!/usr/bin/env python3
"""
Report the memory and storage lines PageCpuUseTest logs on the CI emulator (tag MemoryUse).

Report only (owner pick 2026-11-15, docs/specs/2026-11-15-ai-request-and-size-checks.md): emulator
memory varies between runs, so this never fails. It writes the numbers to the job summary, compares
them with a baseline from main when one is given, and warns when total PSS or storage grew by more
than --warn-percent.
"""

import argparse
import json
import pathlib
import re
import sys

LINE = re.compile(
    r"scenario=(?P<scenario>[a-z0-9_]+) app_pss_kb=(?P<app_pss_kb>\d+) page_pss_kb=(?P<page_pss_kb>\d+) "
    r"total_pss_kb=(?P<total_pss_kb>\d+) storage_kb=(?P<storage_kb>\d+)(?P<unreadable> page_process=unreadable)?"
)
FIELDS = ("app_pss_kb", "page_pss_kb", "total_pss_kb", "storage_kb")
WATCHED = ("total_pss_kb", "storage_kb")


def read_results(folder: pathlib.Path) -> dict:
    """The last reading of each scenario found in any file under folder (logcat files hold the lines)."""
    results = {}
    files = sorted(path for path in folder.rglob("*") if path.is_file()) if folder.is_dir() else []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for match in LINE.finditer(text):
            reading = {field: int(match[field]) for field in FIELDS}
            if match["unreadable"]:
                reading["page_process"] = "unreadable"
            results[match["scenario"]] = reading
    return results


def mb(kb: int) -> str:
    return f"{kb / 1024:.1f}"


def compare(results: dict, baseline: dict, warn_percent: float) -> list:
    """Warnings for watched numbers that grew more than warn_percent over the baseline."""
    warnings = []
    for scenario, reading in sorted(results.items()):
        before = baseline.get(scenario)
        if not before:
            continue
        for field in WATCHED:
            old, new = before.get(field), reading.get(field)
            if not old or new is None:
                continue
            change = (new / old - 1) * 100
            if change > warn_percent:
                warnings.append(f"{scenario} {field} grew {change:.0f}% ({mb(old)} MB to {mb(new)} MB)")
    return warnings


def summary_lines(results: dict, baseline, baseline_label, warnings: list) -> list:
    lines = ["### Memory and storage use (report only)", ""]
    if not results:
        return lines + ["No MemoryUse results found.", ""]
    header = "| Scenario | App PSS MB | Page PSS MB | Total PSS MB | App data MB |"
    if baseline:
        header += f" Total PSS vs {baseline_label or 'main'} |"
    lines += [header, "|---|---:|---:|---:|---:|" + ("---:|" if baseline else "")]
    for scenario, reading in sorted(results.items()):
        row = (
            f"| {scenario} | {mb(reading['app_pss_kb'])} | "
            f"{'n/a' if reading.get('page_process') else mb(reading['page_pss_kb'])} | "
            f"{mb(reading['total_pss_kb'])} | {mb(reading['storage_kb'])} |"
        )
        if baseline:
            old = (baseline.get(scenario) or {}).get("total_pss_kb")
            row += f" {(reading['total_pss_kb'] / old - 1) * 100:+.0f}% |" if old else " no baseline |"
        lines.append(row)
    lines.append("")
    if not baseline:
        lines += ["No baseline from main yet, so nothing was compared.", ""]
    for warning in warnings:
        lines += [f"> [!WARNING]\n> {warning}", ""]
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=pathlib.Path, help="Folder with the device test results and logcat files")
    parser.add_argument("--output-json", type=pathlib.Path, help="Where to write this run's numbers")
    parser.add_argument("--baseline", type=pathlib.Path, help="A previous run's --output-json, from main")
    parser.add_argument("--baseline-label", help="What the baseline is, shown in the summary")
    parser.add_argument("--warn-percent", type=float, default=25.0, help="Warn above this growth (default 25%%)")
    parser.add_argument("--summary", type=pathlib.Path, help="Markdown file to append to (GITHUB_STEP_SUMMARY)")
    args = parser.parse_args()

    results = read_results(args.results)
    baseline = None
    if args.baseline and args.baseline.is_file():
        try:
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"Baseline unreadable, not compared: {error}", file=sys.stderr)
    warnings = compare(results, baseline, args.warn_percent) if baseline else []

    lines = summary_lines(results, baseline, args.baseline_label, warnings)
    print("\n".join(lines))
    for warning in warnings:
        print(f"::warning title=Memory use::{warning}")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as file:
            file.write("\n".join(lines) + "\n")
    if args.output_json and results:
        args.output_json.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
