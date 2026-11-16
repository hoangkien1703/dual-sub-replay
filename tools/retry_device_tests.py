#!/usr/bin/env python3
"""
Says which device tests to run again after a failed managed-device run.

The hosted emulator sometimes fails a test that passes on the same code minutes later: Chromium's
own WebView crashed the test process, a slow runner split recorded sound, a screen updated a moment
late (main run, 2026-11-15, three tries and three different tests). CI runs the failed tests once
more and fails only if they fail again, so a real failure still fails and a flaky one is listed.

Prints one line:
  none          no failed test in the results (the build failed, or nothing ran): don't retry
  all           the instrumentation run itself crashed, so later tests never ran: run everything
  <tests>       the failed tests as `package.Class#method,...` for
                -Pandroid.testInstrumentationRunnerArguments.class
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# A crashed instrumentation run shows up as this made-up test case.
RUN_FAILED = ("android-test", "testRunFailed")
# Only plain JVM names go on the Gradle command line.
SAFE_NAME = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$.]*$")


def retry_plan(result_dirs) -> str:
    failed = set()
    crashed = False
    for result_dir in result_dirs:
        for xml_file in sorted(Path(result_dir).rglob("*.xml")):
            try:
                root = ET.parse(xml_file).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                if case.find("failure") is None and case.find("error") is None:
                    continue
                class_name, name = case.get("classname", ""), case.get("name", "")
                if (class_name, name) == RUN_FAILED:
                    crashed = True
                elif SAFE_NAME.match(class_name) and SAFE_NAME.match(name):
                    failed.add(f"{class_name}#{name}")
                else:
                    crashed = True
    if crashed:
        return "all"
    return ",".join(sorted(failed)) if failed else "none"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_dirs", nargs="+", help="Folders holding JUnit XML results")
    args = parser.parse_args(argv)
    print(retry_plan(args.result_dirs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
