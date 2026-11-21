#!/usr/bin/env python3
"""
Checks the Google Play build's merged manifest (docs/specs/2026-11-21-google-play-build.md).

Play's rules forbid an app to install its own updates and to play YouTube with the screen off, so the
Play build must not ask for the installer or foreground service permissions, and must not declare the
media playback service or the update receiver. Fails with the list of what came back.

Usage: check_play_manifest.py app/build/intermediates/merged_manifest/play/processPlayMainManifest/AndroidManifest.xml
"""

import argparse
import sys
import xml.etree.ElementTree as ElementTree

ANDROID = "{http://schemas.android.com/apk/res/android}"

FORBIDDEN_PERMISSIONS = {
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.FOREGROUND_SERVICE",
}
FORBIDDEN_COMPONENTS = {
    "com.kienhoang.dualsubreplay.playback.WebVideoPlaybackService",
    "com.kienhoang.dualsubreplay.update.AppUpdateReceiver",
}


def problems(manifest_xml: str) -> list:
    root = ElementTree.fromstring(manifest_xml)
    package = root.get("package", "")
    found = []
    for element in root.iter("uses-permission"):
        name = element.get(ANDROID + "name", "")
        if name in FORBIDDEN_PERMISSIONS or name.startswith("android.permission.FOREGROUND_SERVICE_"):
            found.append(f"permission {name}")
    application = root.find("application")
    if application is not None:
        for element in application:
            name = element.get(ANDROID + "name", "")
            full = package + name if name.startswith(".") else name
            if full in FORBIDDEN_COMPONENTS:
                found.append(f"{element.tag} {full}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("manifest")
    args = parser.parse_args()
    with open(args.manifest, encoding="utf-8") as handle:
        found = problems(handle.read())
    if found:
        print("The Google Play build must not have:", *found, sep="\n- ")
        return 1
    print("Play manifest OK: no installer or foreground service permission, no playback service or update receiver.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
