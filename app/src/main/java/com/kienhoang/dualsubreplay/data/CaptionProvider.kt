package com.kienhoang.dualsubreplay.data

interface CaptionProvider {
    suspend fun fetch(videoId: String, preferredLanguages: List<String>): CaptionTrackResult
}

class CaptionUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
