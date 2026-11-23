package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.BuildConfig

/**
 * The Google Play build (owner picks 2026-11-21, docs/specs/2026-11-21-google-play-build.md): the same
 * app, package and signing key as the GitHub release, built as the `play` build type. Google Play's
 * rules forbid some of what the GitHub build does, so the Play build leaves it out:
 *
 * - ad and sponsor skipping (Settings → Ads & sponsors),
 * - playing a video with the screen off or the app in the background, with its media notification,
 * - checking for, downloading and installing its own updates (Play updates the app),
 * - the Bilibili beta sites.
 *
 * Each part of the app asks the small function next to its own code, with this value as the default,
 * so unit tests can check both builds. The left-out code stays in the bundle but never runs, and
 * src/play/AndroidManifest.xml removes the permissions and components it would need.
 */
internal const val IS_PLAY_BUILD: Boolean = BuildConfig.PLAY_STORE

/** The app's Play Store page: Check for updates opens it in the Play build. */
internal const val PLAY_STORE_PAGE_URL = "https://play.google.com/store/apps/details?id=$OFFICIAL_APPLICATION_ID"
