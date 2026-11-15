from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report_memory

SCRIPT = Path(__file__).resolve().parents[1] / "report_memory.py"
LOG = (
    "11-15 10:00:00.000  1234  1250 I MemoryUse: scenario=playing app_pss_kb=102400 page_pss_kb=51200 "
    "total_pss_kb=153600 storage_kb=20480\n"
)


class ReportMemoryTests(unittest.TestCase):
    def test_reads_the_last_line_of_each_scenario(self):
        with tempfile.TemporaryDirectory() as folder:
            logs = Path(folder, "device", "logcat-PageCpuUseTest.txt")
            logs.parent.mkdir()
            logs.write_text("noise\n" + LOG.replace("153600", "1") + LOG, encoding="utf-8")
            results = report_memory.read_results(Path(folder))
        self.assertEqual(
            {"playing": {"app_pss_kb": 102400, "page_pss_kb": 51200, "total_pss_kb": 153600, "storage_kb": 20480}},
            results,
        )

    def test_warns_only_above_the_threshold(self):
        now = {"playing": {"total_pss_kb": 130_000, "storage_kb": 30_000}}
        before = {"playing": {"total_pss_kb": 100_000, "storage_kb": 25_000}}
        warnings = report_memory.compare(now, before, 25.0)
        self.assertEqual(1, len(warnings))
        self.assertIn("total_pss_kb grew 30%", warnings[0])
        self.assertEqual([], report_memory.compare(now, before, 40.0))

    def test_never_fails_even_without_results_or_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            summary = Path(folder, "summary.md")
            done = subprocess.run(
                [sys.executable, str(SCRIPT), str(Path(folder, "missing")), "--baseline", str(Path(folder, "none.json")),
                 "--summary", str(summary)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("No MemoryUse results found.", summary.read_text(encoding="utf-8"))

    def test_a_big_jump_is_a_warning_annotation_not_a_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "logcat.txt").write_text(LOG, encoding="utf-8")
            baseline = Path(folder, "baseline.json")
            baseline.write_text(json.dumps({"playing": {"total_pss_kb": 100_000, "storage_kb": 20_480}}), encoding="utf-8")
            output = Path(folder, "memory-use.json")
            done = subprocess.run(
                [sys.executable, str(SCRIPT), folder, "--baseline", str(baseline), "--baseline-label", "main",
                 "--output-json", str(output)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("::warning title=Memory use::playing total_pss_kb grew 54%", done.stdout)
            self.assertIn("| playing | 100.0 | 50.0 | 150.0 | 20.0 | +54% |", done.stdout)
            self.assertEqual(153600, json.loads(output.read_text(encoding="utf-8"))["playing"]["total_pss_kb"])


if __name__ == "__main__":
    unittest.main()
