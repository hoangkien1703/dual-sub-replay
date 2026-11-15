from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report_apk


class ForbiddenPermissionTests(unittest.TestCase):
    def test_media_playback_foreground_service_is_allowed(self):
        self.assertFalse(report_apk.is_forbidden_permission("android.permission.FOREGROUND_SERVICE"))
        self.assertFalse(
            report_apk.is_forbidden_permission("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        )

    def test_other_foreground_service_types_stay_forbidden(self):
        for permission in (
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        ):
            self.assertTrue(report_apk.is_forbidden_permission(permission), permission)

    def test_listed_permissions_stay_forbidden(self):
        self.assertTrue(report_apk.is_forbidden_permission("android.permission.POST_NOTIFICATIONS"))
        self.assertTrue(report_apk.is_forbidden_permission("android.permission.WAKE_LOCK"))
        self.assertFalse(report_apk.is_forbidden_permission("android.permission.INTERNET"))
        # Check for updates installs the app's own newer GitHub release (owner request 2026-10-20).
        self.assertFalse(report_apk.is_forbidden_permission("android.permission.REQUEST_INSTALL_PACKAGES"))


class SizeGrowthTests(unittest.TestCase):
    def test_small_growth_is_only_reported(self):
        lines, warning = report_apk.size_growth_lines(27_700_000, 32_000_000, 27_663_792, "v1.9.2", 500_000)
        self.assertIsNone(warning)
        self.assertIn("| v1.9.2 | 27,663,792 | 27.66 |", lines)
        self.assertIn("| Change | +36,208 | +0.04 |", lines)

    def test_large_growth_warns_without_a_baseline_label(self):
        _, warning = report_apk.size_growth_lines(29_000_000, 32_000_000, 27_000_000, None, 500_000)
        self.assertIn("grew by 2,000,000 bytes since the last release", warning)

    def test_no_baseline_still_shows_the_build_and_its_limit(self):
        lines, warning = report_apk.size_growth_lines(27_000_000, 32_000_000, None, None, 500_000)
        self.assertIsNone(warning)
        self.assertIn("| Limit (fails above) | 32,000,000 | 32.00 |", lines)
        self.assertFalse(any(line.startswith("| Change") for line in lines))


if __name__ == "__main__":
    unittest.main()
