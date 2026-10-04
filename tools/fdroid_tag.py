#!/usr/bin/env python3
"""Send a published GitHub release to F-Droid by tagging its commit fdroid-vX.Y.Z.

F-Droid's recipe builds only fdroid-v tags (docs/fdroid/README.md), so the owner decides which
releases reach F-Droid. Runs from the "Send release to F-Droid" workflow.
"""

import os
import sys

from automatic_release import GitHub, version_tuple


def send_to_fdroid(api, version):
    """Tag release vVERSION as fdroid-vVERSION; return "created" or "exists"."""
    version_tuple(version)
    release = next((item for item in api.pages("releases") if item["tag_name"] == f"v{version}"), None)
    if release is None or release["draft"] or release["prerelease"]:
        raise ValueError(f"v{version} is not a published release")
    commit = api.api(f"commits/v{version}")["sha"]
    ref = f"refs/tags/fdroid-v{version}"
    existing = [item for item in api.pages(f"git/matching-refs/tags/fdroid-v{version}") if item["ref"] == ref]
    if existing:
        if existing[0]["object"]["sha"] != commit:
            raise ValueError(f"fdroid-v{version} already exists on a different commit; refusing to move it")
        return "exists"
    api.api("git/refs", {"ref": ref, "sha": commit})
    return "created"


def main():
    version = os.environ["FDROID_VERSION"].strip().removeprefix("v")
    result = send_to_fdroid(GitHub(os.environ["GITHUB_REPOSITORY"]), version)
    message = {"created": f"Tagged v{version} as fdroid-v{version}; F-Droid picks it up on its next update check.",
               "exists": f"fdroid-v{version} already points at v{version}; nothing to do."}[result]
    print(message)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(message + "\n")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(f"::error::{error}")
        sys.exit(1)
