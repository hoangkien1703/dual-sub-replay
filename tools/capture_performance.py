"""Repeatable adb startup and stable-screen memory evidence; never clears user data."""
import argparse
import hashlib
import json
import pathlib
import re
import statistics
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--package", required=True)
parser.add_argument("--apk", type=pathlib.Path, required=True)
parser.add_argument("--output", type=pathlib.Path, required=True)
parser.add_argument("--runs", type=int, default=5)
parser.add_argument("--compare", type=pathlib.Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def adb(*command):
    return subprocess.check_output(["adb", "-s", args.serial, *command], text=True, encoding="utf-8", timeout=60)


assert adb("shell", "getprop", "sys.boot_completed").strip() == "1", "Device has not finished booting"
launches = []
pss = []
for index in range(args.runs):
    adb("shell", "am", "force-stop", args.package)
    launch = adb("shell", "am", "start", "-W", "-n", args.package + "/com.kienhoang.dualsubreplay.MainActivity")
    assert "Status: ok" in launch, launch
    (args.output / f"startup-{index}.txt").write_text(launch)
    launches.append(int(re.search(r"TotalTime:\s*(\d+)", launch)[1]))
    time.sleep(2)
    memory = adb("shell", "dumpsys", "meminfo", args.package)
    (args.output / f"memory-{index}.txt").write_text(memory)
    match = re.search(r"TOTAL PSS:\s*(\d+)", memory)
    if match:
        pss.append(int(match[1]))
(args.output / "frames.txt").write_text(adb("shell", "dumpsys", "gfxinfo", args.package, "framestats"))
(args.output / "device.txt").write_text(adb("shell", "getprop"))
result = {
    "serial": args.serial,
    "fingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
    "package": args.package,
    "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    "apk_bytes": args.apk.stat().st_size,
    "startup_ms": launches,
    "startup_median_ms": statistics.median(launches),
    "pss_kb": pss,
    "pss_median_kb": statistics.median(pss) if pss else None,
    "caveat": "TTID and stable-screen memory only. Keep app data/screen identical. Emulator timing is noisy.",
}
if args.compare:
    previous = json.loads(args.compare.read_text())
    assert previous["fingerprint"] == result["fingerprint"] and previous["package"] == result["package"], "Incompatible baseline"
    for key in ("startup_median_ms", "pss_median_kb"):
        if previous[key] and result[key]:
            result[key + "_change_percent"] = round((result[key] / previous[key] - 1) * 100, 2)
            if result[key + "_change_percent"] > 10:
                print(f"Investigate repeatability: {key} increased by {result[key + '_change_percent']}%")
(args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
