package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliVideosTest {
    @Test
    fun chinaVideoPagesBecomeFileSafeKeys() {
        assertEquals("bili-BV1kYsW6NE6E", VideoKeys.fromUrl("https://m.bilibili.com/video/BV1kYsW6NE6E?site=OVERSEAS&lang=zh-Hans"))
        assertEquals("bili-BV1kYsW6NE6E", VideoKeys.fromUrl("https://www.bilibili.com/video/BV1kYsW6NE6E/"))
        assertEquals("bili-BV1kYsW6NE6E-p3", VideoKeys.fromUrl("https://www.bilibili.com/video/BV1kYsW6NE6E/?spm_id_from=x&p=3"))
        assertNull(VideoKeys.fromUrl("https://m.bilibili.com/"))
        assertNull(VideoKeys.fromUrl("https://m.bilibili.com/video/BVshort"))
        assertNull(VideoKeys.fromUrl("http://www.bilibili.com/video/BV1kYsW6NE6E"))
    }

    @Test
    fun internationalEpisodesSeasonsAndUploads() {
        assertEquals("bilitv-ss34695-ep342144", VideoKeys.fromUrl("https://www.bilibili.tv/en/play/34695/342144?bstar_from=x"))
        assertEquals("bilitv-ss34695", VideoKeys.fromUrl("https://www.bilibili.tv/en/play/34695"))
        assertEquals("bilitv-av4801603124337152", VideoKeys.fromUrl("https://www.bilibili.tv/en/video/4801603124337152"))
        assertNull(VideoKeys.fromUrl("https://www.bilibili.tv/en/"))
    }

    @Test
    fun youTubeIdsStayAsTheyWere() {
        assertEquals("dQw4w9WgXcQ", VideoKeys.fromUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(VideoSite.YOUTUBE, VideoKeys.site("dQw4w9WgXcQ"))
        assertFalse(VideoKeys.isBilibili("dQw4w9WgXcQ"))
        assertTrue(VideoKeys.isBilibili("bili-BV1kYsW6NE6E"))
        assertEquals(VideoSite.BILIBILI_TV, VideoKeys.site("bilitv-av1"))
    }

    @Test
    fun keysLeadBackToTheirPages() {
        assertEquals("https://m.bilibili.com/video/BV1kYsW6NE6E?p=3&t=83", VideoKeys.watchUrl("bili-BV1kYsW6NE6E-p3", 83))
        assertEquals("https://m.bilibili.com/video/BV1kYsW6NE6E", VideoKeys.watchUrl("bili-BV1kYsW6NE6E"))
        assertEquals("https://www.bilibili.tv/play/34695/342144", VideoKeys.watchUrl("bilitv-ss34695-ep342144"))
        assertEquals("https://www.bilibili.tv/video/48?start_progress=5000", VideoKeys.watchUrl("bilitv-av48", 5))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=7s", VideoKeys.watchUrl("dQw4w9WgXcQ", 7))
        listOf("bili-BV1kYsW6NE6E-p3", "bilitv-ss34695-ep342144", "bilitv-ss34695", "bilitv-av48").forEach { key ->
            assertEquals(key, VideoKeys.fromUrl(VideoKeys.watchUrl(key)))
        }
    }

    @Test
    fun savedBilibiliWordsKeepTheirVideoInBackupsAndCards() {
        assertTrue(VideoKeys.isValid("bili-BV1kYsW6NE6E-p3"))
        assertTrue(VideoKeys.isValid("dQw4w9WgXcQ"))
        assertFalse(VideoKeys.isValid("bili-../../etc"))
        assertTrue(validClipRange("bilitv-av48", 1_000, 2_000))
        // The line's sound and picture come from YouTube pages only.
        assertNull(audioLine("bili-BV1kYsW6NE6E", 1_000, 2_000))
        assertEquals(
            "<a href=\"https://m.bilibili.com/video/BV1kYsW6NE6E?p=3&amp;t=83\">Bilibili</a>",
            ankiSourceLink("bili-BV1kYsW6NE6E-p3", 83_000),
        )
    }

    @Test
    fun avLinksAndTheOverseasDesktopPathNameTheSameVideo() {
        assertEquals("BV17x411w7KC", bvidOfAid(170001))
        assertEquals("BV1L9Uoa9EUx", bvidOfAid(111298867365120))
        assertEquals("bili-BV17x411w7KC", VideoKeys.fromUrl("https://m.bilibili.com/video/av170001"))
        assertEquals("bili-BV1kYsW6NE6E", VideoKeys.fromUrl("https://www.bilibili.com/en/video/BV1kYsW6NE6E"))
        assertNull(VideoKeys.fromUrl("https://m.bilibili.com/space/video/BV1kYsW6NE6E"))
        assertNull(bvidOfAid(0))
    }
}
