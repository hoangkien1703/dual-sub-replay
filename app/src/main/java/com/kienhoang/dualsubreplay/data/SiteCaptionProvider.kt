package com.kienhoang.dualsubreplay.data

/** Sends a Bilibili video to [bilibili] and a YouTube video to [youtube] (Bilibili beta, 2026-11-17). */
internal class SiteCaptionProvider(
    private val youtube: CaptionProvider,
    private val bilibili: CaptionProvider,
) : CaptionProvider {
    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String?,
        preferredTrack: String?,
    ): CaptionTrackResult {
        val provider = if (VideoKeys.isBilibili(videoId)) bilibili else youtube
        return provider.fetch(videoId, preferredLanguages, learningLanguage, preferredTrack)
    }
}
