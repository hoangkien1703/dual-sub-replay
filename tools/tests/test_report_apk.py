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


if __name__ == "__main__":
    unittest.main()
