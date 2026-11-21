from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_play_manifest

HEAD = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.kienhoang.dualsubreplay">'


class PlayManifestTests(unittest.TestCase):
    def test_a_clean_manifest_passes(self):
        manifest = HEAD + (
            '<uses-permission android:name="android.permission.INTERNET" />'
            '<application><activity android:name=".MainActivity" /></application></manifest>'
        )
        self.assertEqual([], check_play_manifest.problems(manifest))

    def test_the_installer_and_foreground_permissions_fail(self):
        manifest = HEAD + (
            '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />'
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />'
            "<application /></manifest>"
        )
        self.assertEqual(3, len(check_play_manifest.problems(manifest)))

    def test_the_playback_service_and_update_receiver_fail_by_short_or_full_name(self):
        manifest = HEAD + (
            '<application><service android:name=".playback.WebVideoPlaybackService" />'
            '<receiver android:name="com.kienhoang.dualsubreplay.update.AppUpdateReceiver" /></application></manifest>'
        )
        found = check_play_manifest.problems(manifest)
        self.assertEqual(2, len(found), found)


if __name__ == "__main__":
    unittest.main()
