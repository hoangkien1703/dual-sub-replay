import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
LOGIN = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")
# The all-contributors types this project uses; see CONTRIBUTORS.md for the key.
TYPES = {"code", "design", "doc", "ideas", "bug", "userTesting", "maintenance", "translation", "review", "question"}


class ContributorsTests(unittest.TestCase):
    """CONTRIBUTORS.md and README's badge are generated from .all-contributorsrc and must not drift."""

    def setUp(self):
        self.config = json.loads((ROOT / ".all-contributorsrc").read_text(encoding="utf-8"))
        self.people = self.config["contributors"]

    def test_entries_are_valid_and_unique(self):
        logins = [person["login"] for person in self.people]
        self.assertEqual(len({login.lower() for login in logins}), len(logins))
        for person in self.people:
            with self.subTest(login=person["login"]):
                self.assertRegex(person["login"], "^" + LOGIN.pattern + "$")
                self.assertEqual(person["profile"], "https://github.com/" + person["login"])
                self.assertRegex(person["avatar_url"], r"^https://avatars\.githubusercontent\.com/u/[0-9]+\?v=4$")
                self.assertTrue(person["contributions"])
                self.assertLessEqual(set(person["contributions"]), TYPES)

    def test_generated_files_match_the_config(self):
        table = (ROOT / "CONTRIBUTORS.md").read_text(encoding="utf-8")
        listed = re.findall(r'<a href="https://github\.com/([^"/]+)"><img', table)
        self.assertEqual(listed, [person["login"] for person in self.people],
                         "Run `npx all-contributors-cli@6.26.1 generate` after editing .all-contributorsrc")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn(f"all_contributors-{len(self.people)}-orange", readme)
        # Only the badge goes in README; the full table stays in CONTRIBUTORS.md (owner decision 2026-11-21).
        self.assertNotIn("ALL-CONTRIBUTORS-LIST", readme)


if __name__ == "__main__":
    unittest.main()
