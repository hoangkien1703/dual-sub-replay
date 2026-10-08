package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiAttachmentReaderTest {
    @Test
    fun bigPicturesAreScaledToTheLongestSideAndSmallOnesKeptAsTheyAre() {
        assertEquals(1568 to 1176, aiPictureTargetSize(4032, 3024))
        assertEquals(882 to 1568, aiPictureTargetSize(1080, 1920))
        assertEquals(800 to 600, aiPictureTargetSize(800, 600))
        // A very thin picture never becomes zero pixels wide.
        assertEquals(1 to 1568, aiPictureTargetSize(1, 20_000))
    }

    @Test
    fun hugePhotosAreDecodedAtAFractionFirst() {
        assertEquals(1, aiPictureSampleSize(1568, 1000))
        assertEquals(1, aiPictureSampleSize(3000, 2000))
        assertEquals(2, aiPictureSampleSize(4032, 3024))
        assertEquals(4, aiPictureSampleSize(8000, 6000))
        // Thumbnails decode at a much smaller size.
        assertEquals(8, aiPictureSampleSize(1568, 1176, maxSide = 192))
    }

    @Test
    fun textFilesAreReadAsUtf8OrUtf16() {
        assertEquals("字幕", aiDecodeText("字幕".toByteArray(Charsets.UTF_8)))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "字幕".toByteArray(Charsets.UTF_16LE)
        assertEquals("字幕", aiDecodeText(utf16))
    }

    @Test
    fun dataUrlsCarryTheBytes() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        val url = aiDataUrl("image/jpeg", bytes)
        assertEquals("data:image/jpeg;base64,AQID/w==", url)
        assertArrayEquals(bytes, aiDataUrlBytes(url))
        assertNull(aiDataUrlBytes("https://example.com/a.jpg"))
        assertNull(aiDataUrlBytes("data:image/jpeg;base64,***"))
    }
}
