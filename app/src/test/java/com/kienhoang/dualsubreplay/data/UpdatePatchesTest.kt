package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdatePatchesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Made by `tools/apk_patch.py` (see `tools/tests/test_apk_patch.py`), so both sides read one format. */
    private fun fixture(name: String): File {
        val bytes = requireNotNull(javaClass.getResource("/update-patch/$name")).readBytes()
        return File(folder.root, name).apply { writeBytes(bytes) }
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun aPatchFromThePythonToolRebuildsTheNewApkExactly() {
        val output = folder.newFile("out.apk")
        val hash = applyUpdatePatch(fixture("old.zip"), fixture("new-from-old.patch"), output)
        val expected = fixture("new.zip").readBytes()
        assertArrayEquals(expected, output.readBytes())
        assertEquals(sha256Hex(expected), hash)
    }

    @Test
    fun aPatchForAnotherApkIsRefused() {
        val other = folder.newFile("other.apk").apply { writeBytes(fixture("old.zip").readBytes() + 1) }
        assertRefused { applyUpdatePatch(other, fixture("new-from-old.patch"), folder.newFile("out.apk")) }
    }

    @Test
    fun aDamagedOrCutPatchIsRefused() {
        val patch = fixture("new-from-old.patch").readBytes()
        val cut = folder.newFile("cut.patch").apply { writeBytes(patch.copyOf(patch.size - 20)) }
        assertRefused { applyUpdatePatch(fixture("old.zip"), cut, folder.newFile("out1.apk")) }
        val damaged =
            folder.newFile("damaged.patch").apply {
                writeBytes(
                    patch.copyOf().also {
                        it[it.size - 30] =
                            (it[it.size - 30] + 1).toByte()
                    },
                )
            }
        assertRefused { applyUpdatePatch(fixture("old.zip"), damaged, folder.newFile("out2.apk")) }
        val wrongMagic = folder.newFile("magic.patch").apply { writeBytes(patch.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertRefused { applyUpdatePatch(fixture("old.zip"), wrongMagic, folder.newFile("out3.apk")) }
    }

    private fun assertRefused(block: () -> Unit) {
        val refused =
            try {
                block()
                false
            } catch (_: IOException) {
                true
            }
        assertTrue("the patch should be refused", refused)
    }

    private val hash = "a".repeat(64)

    @Test
    fun updateInfoOffersThePatchFromTheInstalledVersion() {
        val json =
            """
            {"schema":1,"version":"1.8.26","version_code":118,"minimum_safe_version_code":0,
             "apk":"DualSub-Replay.apk","sha256":"${"b".repeat(64)}","size":25000000,
             "patches":[
              {"from_version_code":117,"from_sha256":"${"c".repeat(
                64,
            )}","name":"update-from-v1.8.25.patch","sha256":"$hash","size":2600000},
              {"from_version_code":116,"name":"../evil.patch","sha256":"$hash","size":2600000},
              {"from_version_code":115,"name":"update-from-v1.8.23.patch","sha256":"short","size":2600000}
             ]}
            """.trimIndent()
        val info = requireNotNull(parseUpdateInfo(json))
        assertEquals(1, info.patches.size)
        val update = requireNotNull(updateFromInfo(info, installedVersionCode = 117))
        assertEquals(
            UpdatePatch(
                "https://github.com/hoangkien1703/dual-sub-replay/releases/download/v1.8.26/update-from-v1.8.25.patch",
                hash,
                2_600_000,
            ),
            update.patch,
        )
        assertEquals(2_600_000L, update.downloadBytes)
        // No patch from an older version: the whole APK.
        val older = requireNotNull(updateFromInfo(info, installedVersionCode = 110))
        assertNull(older.patch)
        assertEquals(25_000_000L, older.downloadBytes)
    }

    @Test
    fun anUpdateInfoWithoutPatchesStillWorks() {
        val json = """{"schema":1,"version":"1.8.24","version_code":116,"apk":"DualSub-Replay.apk","sha256":"$hash","size":39764900}"""
        assertNull(requireNotNull(updateFromInfo(requireNotNull(parseUpdateInfo(json)), 110)).patch)
    }

    @Test
    fun aPreviewBuildTakesThePatchFromItsOwnBuild() {
        val release =
            """
            {"html_url":"https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview","assets":[
              {"name":"DualSub-Replay-PR207-build701-preview.apk","size":40000000,"digest":"sha256:$hash",
               "browser_download_url":"https://github.com/hoangkien1703/dual-sub-replay/releases/download/preview/DualSub-Replay-PR207-build701-preview.apk"},
              {"name":"DualSub-Replay-PR207-build701-from-build700.patch","size":2500000,"digest":"sha256:${"d".repeat(64)}",
               "browser_download_url":"https://github.com/hoangkien1703/dual-sub-replay/releases/download/preview/DualSub-Replay-PR207-build701-from-build700.patch"},
              {"name":"DualSub-Replay-PR207-build701-from-build699.patch","size":2500000,"digest":"sha256:${"e".repeat(64)}",
               "browser_download_url":"https://github.com/hoangkien1703/dual-sub-replay/releases/download/preview/DualSub-Replay-PR207-build701-from-build699.patch"}
            ]}
            """.trimIndent()
        val fromBuild700 = requireNotNull(parsePreviewRelease(release, UpdateChannel.Preview(207, 700)))
        assertEquals("d".repeat(64), fromBuild700.patch?.sha256)
        assertEquals(2_500_000L, fromBuild700.downloadBytes)
        val fromBuild698 = requireNotNull(parsePreviewRelease(release, UpdateChannel.Preview(207, 698)))
        assertNull(fromBuild698.patch)
        assertNull(
            previewPatchFor(listOf(JSONObject("""{"name":"DualSub-Replay-PR207-build701-from-build700.patch","size":1}""")), 207, 700, 701),
        )
    }

    @Test
    fun onlyDevicesThatRunArmAppsGetReleaseUpdates() {
        assertTrue(canRunReleaseApk(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        // An Intel Chromebook with ARM translation lists ARM after x86.
        assertTrue(canRunReleaseApk(listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")))
        assertFalse(canRunReleaseApk(listOf("x86_64", "x86")))
        assertEquals(UpdateChannel.UnsupportedDevice, updateChannelFor("com.kienhoang.dualsubreplay", false, "1.8.26", listOf("x86_64")))
        assertEquals(
            UpdateChannel.Official,
            updateChannelFor("com.kienhoang.dualsubreplay", false, "1.8.26", listOf("x86_64", "arm64-v8a")),
        )
        // Preview builds keep every ABI.
        assertEquals(
            UpdateChannel.Preview(207, 700),
            updateChannelFor("com.kienhoang.dualsubreplay.preview", true, "1.8.26-pr207-build700", listOf("x86_64")),
        )
    }
}
