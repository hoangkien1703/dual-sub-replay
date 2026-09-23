#!/usr/bin/env python3
"""Plan/resume and publish releases. Mutations run only in the serialized main workflow."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


OWNER = "hoangkien1703"
GRADLE = "app/build.gradle.kts"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
MARKER = re.compile(r"<!-- automatic-release-v1 (\{[^\n]+\}) -->")
REQUIRED_JOBS = {"verify-build", "managed-device-tests"}


def command(*args, input_text=None):
    return subprocess.run(args, input=input_text, text=True, check=True, capture_output=True).stdout


class GitHub:
    def __init__(self, repository):
        self.repository = repository

    def api(self, path, data=None, method=None):
        args = ["gh", "api", f"repos/{self.repository}/{path}"]
        if data is not None:
            args += ["--method", method or "POST", "--input", "-"]
        return json.loads(command(*args, input_text=json.dumps(data) if data is not None else None))

    def pages(self, path, key=None):
        result = []
        for page in range(1, 1001):
            separator = "&" if "?" in path else "?"
            response = self.api(f"{path}{separator}per_page=100&page={page}")
            items = response[key] if key else response
            result.extend(items)
            if len(items) < 100:
                return result
        raise ValueError("Pagination limit exceeded; refusing incomplete release history")


def version_tuple(value):
    if not VERSION.fullmatch(value):
        raise ValueError(f"Invalid stable version: {value!r}; expected X.Y.Z")
    return tuple(map(int, value.split(".")))


def stable_tag(tag):
    return tag.startswith("v") and VERSION.fullmatch(tag[1:]) is not None


def release_request(pr):
    labels = {label["name"] for label in pr.get("labels", [])}
    modes = labels & {"release:patch", "release:minor", "release:major", "release:skip"}
    # Ignore template comments, including the example Release-Version directive.
    body = re.sub(r"<!--[\s\S]*?-->", "", pr.get("body") or "")
    explicit = re.findall(r"^[ \t]*Release-Version:[ \t]*([^\r\n]*)$", body, re.MULTILINE)
    if len(modes) > 1 or len(explicit) > 1 or (modes and explicit):
        raise ValueError("Use only one release label OR one Release-Version directive")
    if explicit:
        requested = explicit[0].strip()
        version_tuple(requested)
        return requested
    return next(iter(modes), "release:patch").split(":")[1]


def next_version(previous, request):
    major, minor, patch = version_tuple(previous)
    if request == "patch":
        result = (major, minor, patch + 1)
    elif request == "minor":
        result = (major, minor + 1, 0)
    elif request == "major":
        result = (major + 1, 0, 0)
    else:
        result = version_tuple(request)
    if result <= (major, minor, patch):
        raise ValueError("An explicit version must exceed every existing/reserved stable version")
    return ".".join(map(str, result))


def metadata(release):
    match = MARKER.search(release.get("body") or "")
    return json.loads(match[1]) if match else None


def gradle_version(source):
    name = re.search(r'^val appVersionName = "([^"]+)"$', source, re.MULTILINE)
    code = re.search(r"^val appVersionCode = ([0-9]+)$", source, re.MULTILINE)
    if not name or not code:
        raise ValueError("Cannot find appVersionName/appVersionCode in Gradle source")
    version_tuple(name[1])
    return name[1], int(code[1])


def historical_version_code(source):
    # Early releases declared versionCode directly inside defaultConfig.
    match = re.search(r"^\s*(?:val appVersionCode|versionCode) = ([0-9]+)\s*$", source, re.MULTILINE)
    if not match:
        raise ValueError("Cannot determine a historical Android versionCode")
    return int(match[1])


def versioned_source(source, version, code):
    gradle_version(source)
    if not 0 < code <= 2100000000:
        raise ValueError("Android versionCode is out of range")
    source = re.sub(r'^val appVersionName = "[^"]+"$', f'val appVersionName = "{version}"', source, flags=re.MULTILINE)
    return re.sub(r"^val appVersionCode = [0-9]+$", f"val appVersionCode = {code}", source, flags=re.MULTILINE)


def verify_ci(api, pr):
    runs = api.pages(
        f"actions/workflows/android.yml/runs?event=pull_request&head_sha={pr['head']['sha']}",
        "workflow_runs",
    )
    # Do not accept a successful run for an older commit, another PR, or an older retry.
    # GitHub can clear pull_requests after merge or branch deletion. The exact
    # final head SHA and workflow identity remain available in that case.
    runs = [run for run in runs if run["head_sha"] == pr["head"]["sha"]
            and (not run["pull_requests"]
                 or any(item["number"] == pr["number"] for item in run["pull_requests"]))]
    if not runs:
        raise ValueError("No Android PR CI run exists for the final PR head")
    run = max(runs, key=lambda item: item["id"])
    if run["status"] != "completed" or run["conclusion"] != "success":
        raise ValueError("The latest Android PR CI run must complete successfully before release")
    jobs = api.pages(f"actions/runs/{run['id']}/jobs?filter=latest", "jobs")
    successful = {job["name"] for job in jobs if job["conclusion"] == "success"}
    if not REQUIRED_JOBS <= successful or any(job["conclusion"] != "success" for job in jobs):
        raise ValueError("Both required Android jobs must succeed; skipped/cancelled jobs do not qualify")
    return run["id"]


def find_pr(api, event_name, event, sha, requested_pr):
    if event_name == "workflow_dispatch":
        if not re.fullmatch(r"[1-9][0-9]*", requested_pr):
            raise ValueError("Manual recovery requires a merged PR number")
        return api.api(f"pulls/{requested_pr}")
    if event_name != "push" or event.get("ref") != "refs/heads/main":
        raise ValueError("Only main pushes and manual recovery are supported")
    candidates = api.pages(f"commits/{sha}/pulls")
    candidates = [pr for pr in candidates if pr.get("merge_commit_sha") == sha
                  and pr["base"]["ref"] == "main"]
    if not candidates:
        return None  # Direct pushes are not release approvals.
    if len(candidates) != 1:
        raise ValueError("Cannot unambiguously identify the merged PR")
    return api.api(f"pulls/{candidates[0]['number']}")


def prepare(api, pr):
    if not pr or not pr.get("merged") or pr["base"]["ref"] != "main":
        return {"eligible": False}
    if pr["base"]["repo"]["full_name"] != api.repository or pr["merged_by"]["login"] != OWNER:
        return {"eligible": False}
    merge = pr["merge_commit_sha"]
    command("git", "fetch", "origin", merge)
    # main can advance while this release waits; always use this PR's exact merge.
    command("git", "merge-base", "--is-ancestor", merge, "origin/main")
    releases = api.pages("releases")
    existing = [release for release in releases if metadata(release)
                and metadata(release)["merge_sha"] == merge]
    if len(existing) > 1:
        raise ValueError("Multiple release reservations exist for this merge")
    if existing and not existing[0]["draft"]:
        return {"eligible": False}  # Successful retries never rebuild or bump again.
    tags = [tag for tag in command("git", "tag", "--list").splitlines() if stable_tag(tag)]
    if any(command("git", "rev-parse", f"{tag}^{{commit}}").strip() == merge for tag in tags):
        return {"eligible": False}  # Already released by the previous/manual workflow.
    ci_run = verify_ci(api, pr)
    if existing:
        release = existing[0]
        meta = metadata(release)
        if release["tag_name"] != "v" + meta["version"] or release["target_commitish"] != meta["release_sha"]:
            raise ValueError("The reserved draft has been edited; refusing to change its identity")
        return dict(meta, eligible=True, release_needed=True, release_id=release["id"], source_sha=meta["release_sha"])
    request = release_request(pr)
    if request == "skip":
        return {"eligible": True, "release_needed": False, "source_sha": merge}

    # Queue order can differ from merge order. Never assign a newer version to
    # older app source. A pre-existing draft may still be retried at its old version.
    prior_sources = tags + [metadata(item)["merge_sha"] for item in releases if metadata(item)]
    for prior in prior_sources:
        if command("git", "merge-base", merge, prior).strip() == merge:
            raise ValueError("A newer merge already has a release version; refusing a source rollback")

    source = command("git", "show", f"{merge}:{GRADLE}")
    baseline, base_code = gradle_version(source)
    versions = [baseline]
    codes = [base_code]
    for tag in tags:
        versions.append(tag[1:])
        codes.append(historical_version_code(command("git", "show", f"{tag}:{GRADLE}")))
    for release in releases:
        if stable_tag(release["tag_name"]):
            versions.append(release["tag_name"][1:])
            meta = metadata(release)
            if meta:
                codes.append(meta["version_code"])
    version = next_version(max(versions, key=version_tuple), request)
    code = max(codes) + 1
    # A release-only commit keeps the tag reproducible without a bot push to main,
    # branch-protection bypass, or another CI/release loop. Only two constants change.
    parent = api.api(f"git/commits/{merge}")
    tree = api.api("git/trees", {
        "base_tree": parent["tree"]["sha"],
        "tree": [{"path": GRADLE, "mode": "100644", "type": "blob",
                  "content": versioned_source(source, version, code)}],
    })
    commit = api.api("git/commits", {"message": f"Release v{version} from PR #{pr['number']}",
                                     "tree": tree["sha"], "parents": [merge]})
    meta = {"pr": pr["number"], "merge_sha": merge, "release_sha": commit["sha"],
            "version": version, "version_code": code, "ci_run": ci_run}
    previous = [release["tag_name"] for release in releases
                if not release["draft"] and not release["prerelease"] and stable_tag(release["tag_name"])]
    notes_request = {"tag_name": "v" + version, "target_commitish": commit["sha"]}
    if previous:
        notes_request["previous_tag_name"] = max(previous, key=lambda tag: version_tuple(tag[1:]))
    notes = api.api("releases/generate-notes", notes_request)["body"]
    body = ("Download **DualSub-Replay.apk** below to install or update the official app. "
            "Preview builds install separately. The `.sha256` file verifies the download.\n\n"
            f"Built from merged PR #{pr['number']} (`{merge}`), with automatic release versioning.\n\n"
            + notes + "\n\n<!-- automatic-release-v1 " + json.dumps(meta, sort_keys=True) + " -->\n")
    # Persist before building. Drafts reserve both version numbers across failures.
    release = api.api("releases", {"tag_name": "v" + version, "target_commitish": commit["sha"],
                                   "name": f"DualSub Replay v{version}", "body": body,
                                   "draft": True, "prerelease": False})
    return dict(meta, eligible=True, release_needed=True, release_id=release["id"], source_sha=commit["sha"])


def publish(api, release_id):
    release = api.api(f"releases/{release_id}")
    meta = metadata(release)
    if not meta:
        raise ValueError("Release reservation metadata is missing")
    if not release["draft"]:
        return  # Publication already completed, including a lost API response.
    if release["tag_name"] != "v" + meta["version"] or release["target_commitish"] != meta["release_sha"]:
        raise ValueError("Release reservation identity changed")
    if command("git", "rev-parse", "HEAD").strip() != meta["release_sha"]:
        raise ValueError("The checked-out source does not match the reserved release")
    assets = api.pages(f"releases/{release_id}/assets")
    required = {"DualSub-Replay.apk", "DualSub-Replay.apk.sha256"}
    if not required <= {asset["name"] for asset in assets if asset["size"] > 0}:
        raise ValueError("Both verified release assets must be uploaded before publication")
    releases = api.pages("releases")
    newer = any(not item["draft"] and not item["prerelease"] and stable_tag(item["tag_name"])
                and version_tuple(item["tag_name"][1:]) > version_tuple(meta["version"]) for item in releases)
    api.api(f"releases/{release_id}", {"draft": False, "prerelease": False,
                                      "make_latest": "false" if newer else "true"}, method="PATCH")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "publish"])
    parser.add_argument("--release-id", type=int)
    args = parser.parse_args()
    if os.environ["GITHUB_REF"] != "refs/heads/main":
        raise ValueError("Release automation must run from main")
    api = GitHub(os.environ["GITHUB_REPOSITORY"])
    if args.action == "publish":
        if not args.release_id:
            raise ValueError("A reserved release ID is required")
        publish(api, args.release_id)
        return
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    pr = find_pr(api, os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_SHA"],
                 os.environ.get("RELEASE_PR", ""))
    result = prepare(api, pr)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for key, value in result.items():
            output.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write("### Release plan\n\n```json\n" + json.dumps(result, indent=2) + "\n```\n")


if __name__ == "__main__":
    main()
