from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fdroid_tag


class FakeGitHub:
    def __init__(self, releases=None, refs=None):
        self.releases = releases if releases is not None else [
            {"tag_name": "v1.3.6", "draft": False, "prerelease": False}]
        self.refs = refs or []
        self.writes = []

    def pages(self, path, key=None):
        if path == "releases":
            return self.releases
        if path.startswith("git/matching-refs/tags/"):
            prefix = "refs/tags/" + path.removeprefix("git/matching-refs/tags/")
            return [item for item in self.refs if item["ref"].startswith(prefix)]
        raise AssertionError(path)

    def api(self, path, data=None, method=None):
        if data is not None:
            self.writes.append((path, data))
            return {}
        if path == "commits/v1.3.6":
            return {"sha": "release136"}
        raise AssertionError(path)


class SendToFdroidTests(unittest.TestCase):
    def test_tags_the_release_commit(self):
        api = FakeGitHub()
        self.assertEqual("created", fdroid_tag.send_to_fdroid(api, "1.3.6"))
        self.assertEqual([("git/refs", {"ref": "refs/tags/fdroid-v1.3.6", "sha": "release136"})], api.writes)

    def test_existing_tag_on_the_same_commit_is_a_no_op(self):
        api = FakeGitHub(refs=[{"ref": "refs/tags/fdroid-v1.3.6", "object": {"sha": "release136"}},
                               {"ref": "refs/tags/fdroid-v1.3.60", "object": {"sha": "other"}}])
        self.assertEqual("exists", fdroid_tag.send_to_fdroid(api, "1.3.6"))
        self.assertEqual([], api.writes)

    def test_existing_tag_elsewhere_is_never_moved(self):
        api = FakeGitHub(refs=[{"ref": "refs/tags/fdroid-v1.3.6", "object": {"sha": "other"}}])
        with self.assertRaisesRegex(ValueError, "different commit"):
            fdroid_tag.send_to_fdroid(api, "1.3.6")
        self.assertEqual([], api.writes)

    def test_only_published_stable_releases_are_sent(self):
        for releases in ([], [{"tag_name": "v1.3.6", "draft": True, "prerelease": False}],
                         [{"tag_name": "v1.3.6", "draft": False, "prerelease": True}]):
            api = FakeGitHub(releases=releases)
            with self.assertRaisesRegex(ValueError, "not a published release"):
                fdroid_tag.send_to_fdroid(api, "1.3.6")
            self.assertEqual([], api.writes)

    def test_rejects_anything_but_x_y_z(self):
        for version in ("1.3", "v1.3.6", "1.3.6-rc1", "1.3.6; rm -rf /", ""):
            with self.assertRaises(ValueError):
                fdroid_tag.send_to_fdroid(FakeGitHub(), version)


if __name__ == "__main__":
    unittest.main()
