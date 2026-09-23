import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import automatic_release as release


SOURCE = 'plugins {}\nval appVersionCode = 35\nval appVersionName = "1.0.4"\n// app code\n'
PR = {"number": 72, "merged": True, "merge_commit_sha": "merge72",
      "merged_by": {"login": "hoangkien1703"}, "head": {"sha": "head72"},
      "base": {"ref": "main", "repo": {"full_name": "hoangkien1703/dual-sub-replay"}},
      "labels": [], "body": ""}


class FakeGitHub:
    repository = "hoangkien1703/dual-sub-replay"

    def __init__(self):
        self.releases = [{"id": 1, "tag_name": "v1.0.4", "target_commitish": "main",
                          "draft": False, "prerelease": False, "body": "Legacy notes"}]
        self.runs = [{"id": 20, "head_sha": "head72", "pull_requests": [],
                      "status": "completed", "conclusion": "success"}]
        self.jobs = [{"name": name, "conclusion": "success"} for name in release.REQUIRED_JOBS]
        self.assets = [{"name": name, "size": 100} for name in
                       ("DualSub-Replay.apk", "DualSub-Replay.apk.sha256")]
        self.writes = []
        self.associated_prs = [PR]

    def pages(self, path, key=None):
        if path == "releases":
            return copy.deepcopy(self.releases)
        if path.startswith("actions/workflows/"):
            return self.runs
        if path.endswith("/jobs?filter=latest"):
            return self.jobs
        if path.endswith("/assets"):
            return self.assets
        if path.startswith("commits/"):
            return self.associated_prs
        raise AssertionError(path)

    def api(self, path, data=None, method=None):
        if data is not None:
            self.writes.append((path, copy.deepcopy(data), method))
        if path.startswith("git/commits/"):
            return {"tree": {"sha": "base-tree"}}
        if path == "git/trees":
            return {"sha": "release-tree"}
        if path == "git/commits":
            return {"sha": "release" + str(len(self.releases))}
        if path == "releases/generate-notes":
            return {"body": "## What's changed\n* A merged PR"}
        if path == "releases":
            result = dict(data, id=len(self.releases) + 1)
            self.releases.append(result)
            return copy.deepcopy(result)
        if path.startswith("releases/"):
            item = next(item for item in self.releases if item["id"] == int(path.split("/")[1]))
            if data:
                item.update(data)
            return copy.deepcopy(item)
        if path.startswith("pulls/"):
            return copy.deepcopy(PR)
        raise AssertionError(path)


def git_command(*args, **kwargs):
    if args[:3] == ("git", "tag", "--list"):
        return "v1.0.4\npreview\nv1.1.0-preview\nv0.9.2$previewVersionNameSuffix\n"
    if args[:2] == ("git", "show"):
        return SOURCE
    if args[:3] == ("git", "rev-parse", "HEAD"):
        return "release1\n"
    if args[:2] == ("git", "rev-parse"):
        return "legacy-release\n"
    if args[:2] == ("git", "fetch") or args[:2] == ("git", "merge-base"):
        return ""
    raise AssertionError(args)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub()
        self.pr = copy.deepcopy(PR)
        self.patcher = patch.object(release, "command", side_effect=git_command)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_default_patch_reserves_draft_and_changes_only_version_constants(self):
        plan = release.prepare(self.api, self.pr)
        self.assertEqual((plan["version"], plan["version_code"]), ("1.0.5", 36))
        self.assertTrue(self.api.releases[-1]["draft"])
        tree = next(data for path, data, _ in self.api.writes if path == "git/trees")
        self.assertEqual(len(tree["tree"]), 1)
        self.assertEqual(tree["tree"][0]["content"], SOURCE.replace("35", "36").replace("1.0.4", "1.0.5"))
        commit = next(data for path, data, _ in self.api.writes if path == "git/commits")
        self.assertEqual(commit["parents"], ["merge72"])

    def test_version_choices_and_patch_rollover(self):
        for mode, expected in [("patch", "1.0.10"), ("minor", "1.1.0"),
                               ("major", "2.0.0"), ("3.4.5", "3.4.5")]:
            with self.subTest(mode=mode):
                self.assertEqual(release.next_version("1.0.9", mode), expected)

    def test_exact_version_in_pr_description(self):
        self.pr["body"] = "## Notes\nRelease-Version: 1.2.0\n"
        self.assertEqual(release.prepare(self.api, self.pr)["version"], "1.2.0")

    def test_minor_label(self):
        self.pr["labels"] = [{"name": "release:minor"}]
        self.assertEqual(release.prepare(self.api, self.pr)["version"], "1.1.0")

    def test_skip_keeps_preview_but_does_not_reserve_a_release(self):
        self.pr["labels"] = [{"name": "release:skip"}]
        self.assertEqual(release.prepare(self.api, self.pr),
                         {"eligible": True, "release_needed": False, "source_sha": "merge72"})
        self.assertEqual(self.api.writes, [])

    def test_invalid_conflicting_or_backward_overrides_fail_without_writes(self):
        for labels, body in [(["release:minor", "release:major"], ""),
                             (["release:skip"], "Release-Version: 2.0.0"),
                             ([], "Release-Version: 1.0.4"),
                             ([], "Release-Version: 1.0.3"),
                             ([], "Release-Version: 01.2.3"),
                             ([], "Release-Version: 1.2.3-preview"),
                             ([], "Release-Version: $(echo bad)"),
                             ([], "Release-Version:"),
                             ([], "Release-Version: 1.2.3\nRelease-Version: 1.2.4")]:
            with self.subTest(labels=labels, body=body):
                self.pr.update(labels=[{"name": label} for label in labels], body=body)
                with self.assertRaises(ValueError):
                    release.prepare(self.api, self.pr)
                self.assertEqual(self.api.writes, [])

    def test_template_example_is_ignored(self):
        self.pr["body"] = "<!--\nRelease-Version: 1.2.0\n-->\n"
        self.assertEqual(release.release_request(self.pr), "patch")

    def test_failed_build_retry_reuses_original_draft_even_if_pr_body_changes(self):
        first = release.prepare(self.api, self.pr)
        self.pr["body"] = "Release-Version: 9.0.0"
        before = len(self.api.writes)
        self.assertEqual(release.prepare(self.api, self.pr), first)
        self.assertEqual(len(self.api.writes), before)

    def test_two_merges_reserve_distinct_versions_even_if_first_build_failed(self):
        first = release.prepare(self.api, self.pr)
        self.pr.update(number=73, merge_commit_sha="merge73", head={"sha": "head73"})
        self.api.runs[0].update(head_sha="head73")
        second = release.prepare(self.api, self.pr)
        self.assertEqual((first["version"], second["version"]), ("1.0.5", "1.0.6"))
        self.assertEqual((first["version_code"], second["version_code"]), (36, 37))

    def test_published_retry_is_noop(self):
        release.prepare(self.api, self.pr)
        self.api.releases[-1]["draft"] = False
        before = len(self.api.writes)
        self.assertEqual(release.prepare(self.api, self.pr), {"eligible": False})
        self.assertEqual(len(self.api.writes), before)

    def test_latest_ci_failure_blocks_old_success(self):
        self.api.runs.append(dict(self.api.runs[0], id=21, conclusion="failure"))
        with self.assertRaisesRegex(ValueError, "latest Android"):
            release.prepare(self.api, self.pr)
        self.assertEqual(self.api.writes, [])

    def test_missing_stale_pending_or_skipped_ci_blocks_release(self):
        for change in ("missing", "stale", "pending", "skipped", "missing_job", "other_pr"):
            with self.subTest(change=change):
                api = FakeGitHub()
                if change == "missing":
                    api.runs = []
                elif change == "stale":
                    api.runs[0]["head_sha"] = "old-head"
                elif change == "pending":
                    api.runs[0]["status"] = "in_progress"
                elif change == "skipped":
                    api.jobs[0]["conclusion"] = "skipped"
                elif change == "missing_job":
                    api.jobs.pop()
                else:
                    api.runs[0]["pull_requests"] = [{"number": 99}]
                with self.assertRaises(ValueError):
                    release.prepare(api, self.pr)
                self.assertEqual(api.writes, [])

    def test_closed_unmerged_other_branch_or_other_merger_does_not_release(self):
        for field, value in [("merged", False), ("merged_by", {"login": "someone-else"}),
                             ("base", {"ref": "development", "repo": PR["base"]["repo"]})]:
            pr = copy.deepcopy(self.pr)
            pr[field] = value
            self.assertEqual(release.prepare(self.api, pr), {"eligible": False})
        self.assertEqual(self.api.writes, [])

    def test_direct_push_has_no_release(self):
        self.api.associated_prs = []
        self.assertIsNone(release.find_pr(self.api, "push", {"ref": "refs/heads/main"}, "direct", ""))

    def test_legacy_tagged_merge_does_not_release_again(self):
        def already_tagged(*args, **kwargs):
            if args[:2] == ("git", "rev-parse"):
                return "merge72\n"
            return git_command(*args, **kwargs)
        with patch.object(release, "command", side_effect=already_tagged):
            self.assertEqual(release.prepare(self.api, self.pr), {"eligible": False})
        self.assertEqual(self.api.writes, [])

    def test_out_of_order_merge_cannot_release_older_source_as_a_new_version(self):
        def newer_already_released(*args, **kwargs):
            if args[:2] == ("git", "merge-base") and args[2] != "--is-ancestor":
                return "merge72\n"
            return git_command(*args, **kwargs)
        with patch.object(release, "command", side_effect=newer_already_released):
            with self.assertRaisesRegex(ValueError, "source rollback"):
                release.prepare(self.api, self.pr)
        self.assertEqual(self.api.writes, [])

    def test_manual_recovery_requires_a_pr_number(self):
        with self.assertRaises(ValueError):
            release.find_pr(self.api, "workflow_dispatch", {}, "main", "")
        self.assertEqual(release.find_pr(self.api, "workflow_dispatch", {}, "main", "72"), PR)

    def test_publish_requires_all_assets_and_the_reserved_source(self):
        plan = release.prepare(self.api, self.pr)
        self.api.assets.pop()
        with self.assertRaisesRegex(ValueError, "Both verified"):
            release.publish(self.api, plan["release_id"])
        self.assertTrue(self.api.releases[-1]["draft"])
        self.api.assets = FakeGitHub().assets
        with patch.object(release, "command", return_value="wrong-commit"):
            with self.assertRaisesRegex(ValueError, "checked-out source"):
                release.publish(self.api, plan["release_id"])

    def test_publish_then_retry_does_not_mutate_public_release(self):
        plan = release.prepare(self.api, self.pr)
        release.publish(self.api, plan["release_id"])
        self.assertFalse(self.api.releases[-1]["draft"])
        self.assertEqual(self.api.releases[-1]["make_latest"], "true")
        before = len(self.api.writes)
        release.publish(self.api, plan["release_id"])
        self.assertEqual(len(self.api.writes), before)

    def test_old_draft_retry_cannot_replace_a_newer_latest_release(self):
        plan = release.prepare(self.api, self.pr)
        self.api.releases.append(dict(self.api.releases[0], id=3, tag_name="v1.0.6"))
        release.publish(self.api, plan["release_id"])
        self.assertEqual(self.api.releases[1]["make_latest"], "false")

    def test_tampered_reservation_is_rejected(self):
        release.prepare(self.api, self.pr)
        self.api.releases[-1]["target_commitish"] = "wrong"
        with self.assertRaisesRegex(ValueError, "reserved draft"):
            release.prepare(self.api, self.pr)

    def test_historical_version_codes_and_android_limit(self):
        self.assertEqual(release.historical_version_code("        versionCode = 10\n"), 10)
        with self.assertRaises(ValueError):
            release.versioned_source(SOURCE, "1.0.5", 2100000001)

    def test_pagination_does_not_silently_drop_history(self):
        api = release.GitHub(FakeGitHub.repository)
        with patch.object(api, "api", side_effect=[list(range(100)), [100]]) as mock:
            self.assertEqual(len(api.pages("releases")), 101)
            self.assertEqual(mock.call_args.args[0], "releases?per_page=100&page=2")


if __name__ == "__main__":
    unittest.main()
