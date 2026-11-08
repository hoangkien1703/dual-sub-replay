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

open class CaptionUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** YouTube lists no caption track at all for the video, not even an automatic one. */
class NoPublicCaptionsException(
    message: String,
    cause: Throwable? = null,
) : CaptionUnavailableException(message, cause)

/** [this] or one of its causes says the video has no caption track at all. */
internal fun Throwable.meansNoPublicCaptions(): Boolean =
    generateSequence(this) {
        it.cause
    }.take(8).any { it is NoPublicCaptionsException }

/**
 * True when the video has no caption track at all: the app's download found none, or the page's own
 * player, fully loaded, listed none. The download can fail for another reason on such a video, such as
 * "UNPLAYABLE" for a music video that plays fine on the page (owner's phone test, 2026-11-08).
 */
internal fun meansNoCaptionsAtAll(
    downloadError: Throwable,
    pageProblem: PageTranscriptProblem?,
): Boolean = pageProblem == PageTranscriptProblem.NO_TRACKS || downloadError.meansNoPublicCaptions()
