package com.kienhoang.dualsubreplay.data

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Lends AnkiDroid a card's sound and picture files (see `cardAudioAuthority`). AnkiDroid names the
 * copy it keeps after the type this provider reports. Android's own table says a `.m4a` file is
 * `audio/mpeg`, so with the plain [FileProvider] every sentence clip became an MPEG-4 file named
 * `.mp3`, which players that trust the name don't play (lion737, #81, 2026-11-12). Clips are
 * reported as `audio/mp4`, which AnkiDroid turns back into `.m4a`.
 */
class CardMediaProvider : FileProvider() {
    override fun getType(uri: Uri): String? = cardMediaType(uri.lastPathSegment) ?: super.getType(uri)
}

/** The type reported for the card file [name], or null to leave it to Android's table. */
internal fun cardMediaType(name: String?): String? = if (name?.endsWith(".m4a", ignoreCase = true) == true) CARD_CLIP_TYPE else null

internal const val CARD_CLIP_TYPE = "audio/mp4"
