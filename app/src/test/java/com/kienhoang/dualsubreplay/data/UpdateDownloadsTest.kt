package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadsTest {
    private val digest = "136c5c6363625a137e1b07cd4fcc6e00fd64ae23bfca427cfeff01ff038f080e"

    private val update =
        AppUpdate(
            versionName = "1.8.25",
            downloadUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/download/v1.8.25/DualSub-Replay.apk",
            sha256 = digest,
            checksumUrl = null,
            sizeBytes = 34_251_919,
            pageUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/tag/v1.8.25",
            critical = true,
            securityFixCode = 10825,
        )

    @Test
    fun theRecordSurvivesTheAppBeingClosed() {
        val record = UpdateDownloadRecord(update, digest, asked = true)
        assertEquals(record, parseUpdateDownloadRecord(updateDownloadRecordJson(record)))
        val automatic = record.copy(asked = false)
        assertEquals(automatic, parseUpdateDownloadRecord(updateDownloadRecordJson(automatic)))
    }

    @Test
    fun aBrokenOrForeignRecordIsIgnored() {
        assertNull(parseUpdateDownloadRecord(""))
        assertNull(parseUpdateDownloadRecord("{"))
        val good = updateDownloadRecordJson(UpdateDownloadRecord(update, digest, asked = false))
        assertNull(parseUpdateDownloadRecord(good.replace("\"schema\":1", "\"schema\":2")))
        assertNull(parseUpdateDownloadRecord(good.replace(digest, "not-a-checksum")))
        // The download address must stay a GitHub HTTPS one.
        assertNull(parseUpdateDownloadRecord(good.replace("https://github.com", "https://example.com")))
        assertNull(
            parseUpdateDownloadRecord(
                good.replace("https://github.com/hoangkien1703/dual-sub-replay/releases/download", "http://github.com/x"),
            ),
        )
    }

    @Test
    fun onlyTheSameFileCarriesOn() {
        val record = UpdateDownloadRecord(update, digest, asked = false)
        assertTrue(isSameUpdateDownload(record, update))
        assertTrue(isSameUpdateDownload(record, update.copy(sha256 = null), expectedSha256 = null))
        assertFalse(isSameUpdateDownload(record, update.copy(versionName = "1.8.26")))
        assertFalse(isSameUpdateDownload(record, update.copy(downloadUrl = update.downloadUrl.replace("v1.8.25", "v1.8.26"))))
        assertFalse(isSameUpdateDownload(record, update, expectedSha256 = "0".repeat(64)))
    }

    @Test
    fun theRestStartsWhereContentRangeSays() {
        assertEquals(1_000L, contentRangeStart("bytes 1000-34251918/34251919"))
        assertEquals(0L, contentRangeStart("Bytes 0-9/10"))
        assertNull(contentRangeStart(null))
        assertNull(contentRangeStart("bytes */34251919"))
        assertNull(contentRangeStart("items 1-2/3"))
    }
}
