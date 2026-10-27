from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report_failed_tests

RESULTS = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="pixel2Api36" tests="3" failures="1" errors="1">
  <testcase name="passes" classname="com.kienhoang.dualsubreplay.ui.FineTest" />
  <testcase name="appWorkAroundThePageStaysWithinItsCpuBudget" classname="com.kienhoang.dualsubreplay.ui.PageCpuUseTest">
    <failure>java.lang.AssertionError: Paused used 6.4 ms of CPU a second
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.assertTrue(Assert.java:42)</failure>
  </testcase>
  <testcase name="crashes" classname="com.kienhoang.dualsubreplay.ui.CrashTest">
    <error message="java.lang.IllegalStateException: boom" />
  </testcase>
</testsuite>
"""


class FailedTestsTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        nested = Path(self.folder.name, "managedDevice", "debug", "pixel2Api36")
        nested.mkdir(parents=True)
        (nested / "TEST-pixel2Api36.xml").write_text(RESULTS, encoding="utf-8")
        (nested / "broken.xml").write_text("<testsuite", encoding="utf-8")

    def tearDown(self):
        self.folder.cleanup()

    def test_lists_failures_and_errors_without_stack_lines(self):
        self.assertEqual(
            report_failed_tests.failed_tests([self.folder.name]),
            [
                ("CrashTest.crashes", "java.lang.IllegalStateException: boom"),
                (
                    "PageCpuUseTest.appWorkAroundThePageStaysWithinItsCpuBudget",
                    "java.lang.AssertionError: Paused used 6.4 ms of CPU a second",
                ),
            ],
        )

    def test_summary_names_each_failed_test(self):
        markdown = report_failed_tests.summary_markdown(
            "Device tests", report_failed_tests.failed_tests([self.folder.name])
        )
        self.assertIn("### Device tests: 2 failed", markdown)
        self.assertIn("- **CrashTest.crashes**", markdown)
        self.assertIn("Paused used 6.4 ms", markdown)
        self.assertNotIn("Assert.java", markdown)

    def test_no_failures_says_so(self):
        self.assertIn("No failed tests found.", report_failed_tests.summary_markdown("Device tests", []))

    def test_annotation_escapes_new_lines(self):
        self.assertEqual(
            report_failed_tests.annotation("A.b", "first\nsecond 100%"),
            "::error title=Test failed::A.b: first%0Asecond 100%25",
        )


if __name__ == "__main__":
    unittest.main()
