package com.kienhoang.dualsubreplay.data

interface CaptionProvider {
    /**
     * [preferredLanguages] is the user's explicit source choice, empty for Auto. [learningLanguage]
     * is the language the user studies; Auto falls back to it only when the video itself gives no
     * clearer sign of its spoken language.
     */
    suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String? = null,
    ): CaptionTrackResult
}

class CaptionUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
