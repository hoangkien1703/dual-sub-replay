package com.kienhoang.dualsubreplay.data

interface CaptionProvider {
    /**
     * [preferredLanguages] is the user's explicit source choice, empty for Auto. [learningLanguage]
     * is the language the user studies; Auto falls back to it only when the video itself gives no
     * clearer sign of its spoken language. [preferredTrack] is a track the user picked for this
     * video (its `vssId`); it wins whenever the video still has it.
     */
    suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String? = null,
        preferredTrack: String? = null,
    ): CaptionTrackResult
}

class CaptionUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
