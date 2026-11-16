from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import retry_device_tests

PASSED = '<testcase name="passes" classname="com.kienhoang.dualsubreplay.ui.FineTest" />'
FAILED = """<testcase name="aLinePausedHalfWayAndReplayedStillGivesItsClip" classname="com.kienhoang.dualsubreplay.ui.LineAudioWebViewTest">
    <failure>java.lang.AssertionError: the replayed line has no clip</failure>
  </testcase>
  <testcase name="crashes" classname="com.kienhoang.dualsubreplay.ui.CrashTest">
    <error message="java.lang.IllegalStateException: boom" />
  </testcase>"""
CRASHED = """<testcase name="captionTrackSyncSwitchesStickyLanguageToSpokenLanguage" classname="com.kienhoang.dualsubreplay.ui.BrowseWebViewLifecycleTest">
    <failure>Test failed with status -1</failure>
  </testcase>
  <testcase name="testRunFailed" classname="android-test">
    <failure>Test run failed to complete. Instrumentation run failed due to Process crashed.</failure>
  </testcase>"""


class RetryPlanTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.folder.cleanup()

    def plan(self, *cases):
        nested = Path(self.folder.name, "debug", "pixel2Api36")
        nested.mkdir(parents=True, exist_ok=True)
        (nested / "TEST-pixel2Api36.xml").write_text(
            "<testsuites><testsuite>" + "".join(cases) + "</testsuite></testsuites>", encoding="utf-8"
        )
        (nested / "broken.xml").write_text("<testsuite", encoding="utf-8")
        return retry_device_tests.retry_plan([self.folder.name])

    def test_failed_tests_are_named_for_the_gradle_class_filter(self):
        self.assertEqual(
            self.plan(PASSED, FAILED),
            "com.kienhoang.dualsubreplay.ui.CrashTest#crashes,"
            "com.kienhoang.dualsubreplay.ui.LineAudioWebViewTest#aLinePausedHalfWayAndReplayedStillGivesItsClip",
        )

    def test_a_crashed_run_runs_everything_again(self):
        self.assertEqual(self.plan(PASSED, CRASHED), "all")

    def test_no_failed_test_is_not_retried(self):
        self.assertEqual(self.plan(PASSED), "none")
        self.assertEqual(retry_device_tests.retry_plan([str(Path(self.folder.name, "missing"))]), "none")

    def test_a_name_that_is_not_a_plain_jvm_name_runs_everything(self):
        odd = '<testcase name="x; rm -rf /" classname="A"><failure>boom</failure></testcase>'
        self.assertEqual(self.plan(PASSED, odd), "all")


if __name__ == "__main__":
    unittest.main()
