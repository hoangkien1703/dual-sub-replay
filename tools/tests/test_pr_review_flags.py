from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_review_flags as flags

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/pr_review_flags.py"
WORKFLOW = ROOT / ".github/workflows/outside-pr-flags.yml"


def file(name, status="modified", patch="@@ -1 +1 @@\n+x", **extra):
    entry = {"filename": name, "status": status, "additions": 1, "changes": 1}
    if patch is not None:
        entry["patch"] = patch
    entry.update(extra)
    return entry


class ClassifyTests(unittest.TestCase):
    def sections(self, *files, changed=0):
        return flags.classify(list(files), changed).sections

    def test_plain_app_and_docs_changes_are_not_flagged(self):
        self.assertEqual({}, self.sections(
            file("app/src/main/java/app/Player.kt"), file("README.md"), file("site/index.html"),
            file("app/src/main/res/values/strings.xml"), file("docs/specs/x.md")))

    def test_files_that_run_code_are_flagged(self):
        for name in [".github/workflows/android.yml", ".github/actions/x/action.yml", "app/build.gradle.kts",
                     "settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties", "gradlew",
                     "gradle.properties", "tools/release.py", "scripts/setup.sh", "a/b/run.ps1",
                     "tools/promo-video/package.json", ".vscode/tasks.json", ".gitmodules",
                     "app/src/test/java/app/PlayerTest.kt", "app/src/androidTest/java/app/PlayerTest.kt",
                     "benchmark/src/main/java/app/StartupBenchmark.kt"]:
            with self.subTest(name=name):
                self.assertIn("Runs on CI or on your computer", self.sections(file(name)))

    def test_a_rename_out_of_a_risky_folder_still_counts(self):
        moved = file("docs/old.yml", status="renamed", previous_filename=".github/workflows/x.yml")
        self.assertIn("Runs on CI or on your computer", self.sections(moved))

    def test_agent_instruction_files_are_flagged(self):
        for name in ["AGENTS.md", "CLAUDE.md", ".claude/settings.json", "app/AGENTS.md", ".mcp.json"]:
            with self.subTest(name=name):
                self.assertIn("Instructions for AI agents (could steer a reviewing agent)", self.sections(file(name)))

    def test_binaries_and_files_without_a_diff_are_flagged(self):
        result = self.sections(
            file("libs/helper.jar", status="added", patch=None, changes=0),
            file("app/src/main/learner-data/words.txt", status="added", patch=None, changes=0),
            file("data/big.tsv", status="added", additions=5000, changes=5000))
        entries = result["Binary or very large files (ask where they came from)"]
        self.assertEqual(3, len(entries))
        self.assertIn("5000 lines added", entries[2])

    def test_removed_binaries_are_not_flagged_as_new(self):
        self.assertEqual({}, self.sections(file("old/thing.zip", status="removed", patch=None)))

    def test_manifest_permissions_and_exported_parts_are_listed(self):
        patch = ('@@ -1 +1,3 @@\n+<uses-permission android:name="android.permission.READ_CONTACTS" />\n'
                 '+<receiver android:exported="true" />\n-<uses-permission android:name="android.permission.CAMERA" />')
        entries = self.sections(file("app/src/main/AndroidManifest.xml", patch=patch))[
            "App permissions, exported parts or network rules"]
        self.assertIn("new permission `android.permission.READ_CONTACTS`", entries)
        self.assertFalse(any("CAMERA" in e for e in entries))
        self.assertTrue(any("exported" in e for e in entries))

    def test_new_libraries_and_download_sources_are_listed(self):
        toml = '@@ -1 +1,2 @@\n+okhttp = "5.0.0"\n+# comment = "x"'
        gradle = '@@ -1 +1 @@\n+        maven { url = uri("https://evil.example/repo") }'
        entries = self.sections(file("gradle/libs.versions.toml", patch=toml), file("settings.gradle.kts", patch=gradle))[
            "New or changed libraries"]
        self.assertIn('`okhttp = "5.0.0"`', entries)
        self.assertTrue(any("new download source" in e for e in entries))
        self.assertFalse(any("comment" in e for e in entries))

    def test_app_code_hosts_and_risky_calls_are_listed(self):
        patch = ('@@ -1 +1,3 @@\n+val u = "https://Collect.Example.com/x"\n+Runtime.getRuntime().exec("sh")\n'
                 '+webView.addJavascriptInterface(bridge, "app")')
        result = self.sections(file("app/src/main/java/app/Sync.kt", patch=patch),
                               file("app/src/main/assets/licenses/libraries.txt", patch="@@\n+https://www.apache.org"))
        self.assertEqual(["`collect.example.com` in `app/src/main/java/app/Sync.kt`"],
                         result["Internet addresses added to app code"])
        self.assertEqual(2, len(result["App code that needs a close look"]))

    def test_secrets_files_are_flagged(self):
        self.assertIn("Keys, signing or secrets files", self.sections(file("release.jks", patch=None)))

    def test_a_pr_with_more_files_than_listed_says_so(self):
        entries = self.sections(file("README.md"), changed=3001)["Runs on CI or on your computer"]
        self.assertIn("only 1 of 3001", entries[0])


class RenderTests(unittest.TestCase):
    def test_hostile_file_names_cannot_break_the_comment(self):
        name = "x`\n\n## @hoangkien1703 [click](https://evil.example)`.sh"
        body = flags.render(flags.classify([file(name)], 0), "abc")
        listed = [line for line in body.splitlines() if line.startswith("- ")]
        self.assertEqual(1, len(listed))
        self.assertEqual(2, listed[0].count("`"), listed[0])
        self.assertFalse(any(line.startswith("##") for line in body.splitlines()))

    def test_long_lists_are_cut(self):
        files = [file(f"tools/t{i}.py") for i in range(40)]
        body = flags.render(flags.classify(files, 0), "abc")
        self.assertIn("…and 15 more", body)

    def test_clean_result_says_the_label_is_gone(self):
        body = flags.render(flags.Flags(), "abc")
        self.assertTrue(body.startswith(flags.MARKER))
        self.assertIn("no longer touches", body)

    def test_cli_reads_a_stream_of_objects_and_prints_the_output_line(self):
        with tempfile.TemporaryDirectory() as folder:
            files = Path(folder, "files.jsonl")
            files.write_text('{"filename": ".github/x.yml",\n "status": "added", "patch": "+a"}\n'
                             '{"filename": "README.md", "status": "modified", "patch": "+b"}\n', encoding="utf-8")
            body = Path(folder, "body.md")
            out = subprocess.run([sys.executable, "-I", str(SCRIPT), str(files), "--changed-files", "2",
                                  "--head-sha", "abc", "--body", str(body)],
                                 check=True, capture_output=True, text=True).stdout
            self.assertEqual("risky=true\n", out)
            self.assertIn("`.github/x.yml` (added)", body.read_text(encoding="utf-8"))


class WorkflowSafetyTests(unittest.TestCase):
    """pull_request_target has a write token, so the PR's code must never reach this workflow."""

    def setUp(self):
        # Comments may name the forbidden patterns; only the workflow itself must not use them.
        self.text = "\n".join(line for line in WORKFLOW.read_text(encoding="utf-8").splitlines()
                              if not line.lstrip().startswith("#"))

    def test_uses_pull_request_target_with_no_default_permissions(self):
        self.assertIn("pull_request_target:", self.text)
        self.assertRegex(self.text, r"(?m)^permissions: \{\}$")
        self.assertNotIn("environment:", self.text)
        self.assertNotIn("secrets.", self.text)

    def test_only_checks_out_main(self):
        # Each checkout step's lines, up to the next step.
        checkouts, current = [], None
        for line in self.text.splitlines():
            if line.lstrip().startswith("- "):
                current = None
            if "uses: actions/checkout@" in line:
                current = []
                checkouts.append(current)
            elif current is not None:
                current.append(line.strip())
        checkouts = ["\n".join(lines) for lines in checkouts]
        self.assertEqual(1, len(checkouts))
        self.assertIn("ref: ${{ github.event.repository.default_branch }}", checkouts[0])
        self.assertIn("persist-credentials: false", checkouts[0])
        self.assertNotRegex(self.text, r"refs/pull|pull_request\.head\.(ref|label)|merge_commit_sha")

    def test_pr_text_never_reaches_a_shell_directly(self):
        for expression in re.findall(r"\$\{\{([^}]*)\}\}", self.text):
            with self.subTest(expression=expression):
                self.assertNotRegex(expression, r"\.(title|body|ref|label|filename)\b|head_ref|user\.login")

    def test_actions_are_pinned_by_commit(self):
        for action in re.findall(r"uses: (\S+)", self.text):
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_runs_main_tool_in_isolated_mode(self):
        self.assertIn("python3 -I main-tools/tools/pr_review_flags.py", self.text)
        self.assertIn(flags.MARKER, self.text)
        self.assertIn(f'label="{flags.LABEL}"', self.text)


if __name__ == "__main__":
    unittest.main()
