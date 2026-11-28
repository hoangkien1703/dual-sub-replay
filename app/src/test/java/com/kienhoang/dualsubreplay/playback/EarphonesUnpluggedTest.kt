package com.kienhoang.dualsubreplay.playback

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarphonesUnpluggedTest {
    @Test
    fun onlyTheSoundMovingToTheSpeakerPausesTheVideo() {
        assertTrue(isEarphonesUnplugged(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        assertFalse(isEarphonesUnplugged(AudioManager.ACTION_HEADSET_PLUG))
        assertFalse(isEarphonesUnplugged(null))
    }
}
