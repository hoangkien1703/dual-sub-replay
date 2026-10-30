package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/** The repository whose GitHub releases the app updates from (owner request 2026-10-20). */
internal const val UPDATE_REPOSITORY = "hoangkien1703/dual-sub-replay"
internal const val UPDATE_LATEST_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"
internal const val UPDATE_PREVIEW_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/tags/preview"
internal const val UPDATE_RELEASES_PAGE_URL = "https://github.com/$UPDATE_REPOSITORY/releases"
internal const val UPDATE_PREVIEW_PAGE_URL = "$UPDATE_RELEASES_PAGE_URL/tag/preview"

/**
 * The latest release's small description of itself (owner decision 2026-10-30). GitHub serves it from
 * its download hosts, which, unlike the API, have no 60-an-hour limit per shared mobile address.
 */
internal const val UPDATE_INFO_URL = "$UPDATE_RELEASES_PAGE_URL/latest/download/update-info.json"
internal const val OFFICIAL_APK_NAME = "DualSub-Replay.apk"
internal const val OFFICIAL_APK_CHECKSUM_NAME = "$OFFICIAL_APK_NAME.sha256"
internal const val OFFICIAL_APPLICATION_ID = "com.kienhoang.dualsubreplay"

/** No release APK is near this; a bigger download is refused. */
internal const val MAX_UPDATE_BYTES = 150L * 1024 * 1024

/** GitHub's API, release pages and the hosts its release downloads redirect to. */
private val UPDATE_HOSTS =
    setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

internal fun isAllowedUpdateHost(host: String): Boolean = host.lowercase() in UPDATE_HOSTS

/** Where this copy of the app finds newer versions of itself. */
internal sealed interface UpdateChannel {
    /** The signed app from GitHub releases. */
    data object Official : UpdateChannel

    /** A PR preview build: newer builds of the same PR on the rolling `preview` release. */
    data class Preview(
        val pullRequest: Int,
        val build: Int,
    ) : UpdateChannel

    /** A locally built copy, signed with another key: it cannot update itself. */
    data object None : UpdateChannel
}

private val PREVIEW_VERSION = Regex("""-pr(\d+)-build(\d+)$""")

internal fun updateChannelFor(
    applicationId: String,
    debuggable: Boolean,
    versionName: String,
): UpdateChannel {
    if (applicationId == OFFICIAL_APPLICATION_ID && !debuggable) return UpdateChannel.Official
    val preview = PREVIEW_VERSION.find(versionName)
    if (applicationId == "$OFFICIAL_APPLICATION_ID.preview" && preview != null) {
        return UpdateChannel.Preview(preview.groupValues[1].toInt(), preview.groupValues[2].toInt())
    }
    return UpdateChannel.None
}

/** A newer version, as GitHub lists it. */
internal data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    /** Lowercase hex, from GitHub's asset digest; null when the release lists none. */
    val sha256: String?,
    /** The official release's `.sha256` file, used when the asset has no digest. */
    val checksumUrl: String?,
    val sizeBytes: Long,
    /** The release page, with what changed. */
    val pageUrl: String,
    /** The installed version is older than the latest security fix (a `security:critical` release). */
    val critical: Boolean = false,
)

/** The latest release as its `update-info.json` describes it. */
internal data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    /** The newest security release's version code; any older app should update at once. 0 when none. */
    val minimumSafeVersionCode: Long,
    val sha256: String,
    val sizeBytes: Long,
)

private val STABLE_VERSION = Regex("""^\d{1,4}\.\d{1,4}\.\d{1,4}$""")

/** The file's description, or null when it is another schema or anything is missing or malformed. */
internal fun parseUpdateInfo(json: String): UpdateInfo? {
    val info = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (info.optInt("schema") != 1 || info.optString("apk") != OFFICIAL_APK_NAME) return null
    val version = info.optString("version").takeIf(STABLE_VERSION::matches) ?: return null
    val code = info.optLong("version_code").takeIf { it > 0 } ?: return null
    val sha256 = info.optString("sha256").lowercase().takeIf(SHA256_HEX::matches) ?: return null
    return UpdateInfo(
        versionName = version,
        versionCode = code,
        minimumSafeVersionCode = info.optLong("minimum_safe_version_code").coerceAtLeast(0),
        sha256 = sha256,
        sizeBytes = info.optLong("size").coerceAtLeast(0),
    )
}

/** The update [info] offers to an app with [installedVersionCode]; null when it is not newer. */
internal fun updateFromInfo(
    info: UpdateInfo,
    installedVersionCode: Long,
): AppUpdate? {
    if (info.versionCode <= installedVersionCode) return null
    return AppUpdate(
        versionName = info.versionName,
        downloadUrl = "$UPDATE_RELEASES_PAGE_URL/download/v${info.versionName}/$OFFICIAL_APK_NAME",
        sha256 = info.sha256,
        checksumUrl = null,
        sizeBytes = info.sizeBytes,
        pageUrl = "$UPDATE_RELEASES_PAGE_URL/tag/v${info.versionName}",
        critical = installedVersionCode < info.minimumSafeVersionCode,
    )
}

/** Compares the numbers of two versions like `v1.7.1` or `1.7.2-pr3-build9`; the rest is ignored. */
internal fun compareVersions(
    first: String,
    second: String,
): Int {
    val a = versionNumbers(first)
    val b = versionNumbers(second)
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

private fun versionNumbers(version: String): List<Int> =
    version
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

/** The official latest release when it is newer than [installedVersion]; null when up to date. */
internal fun parseOfficialRelease(
    json: String,
    installedVersion: String,
): AppUpdate? {
    val release = JSONObject(json)
    val tag = release.getString("tag_name")
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    if (compareVersions(tag, installedVersion) <= 0) return null
    val assets = release.getJSONArray("assets")
    val apk = assets.objects().firstOrNull { it.optString("name") == OFFICIAL_APK_NAME } ?: return null
    val checksum = assets.objects().firstOrNull { it.optString("name") == OFFICIAL_APK_CHECKSUM_NAME }
    return AppUpdate(
        versionName = tag.removePrefix("v"),
        downloadUrl = apk.getString("browser_download_url"),
        sha256 = sha256Digest(apk.optString("digest")),
        checksumUrl = checksum?.optString("browser_download_url")?.takeIf(String::isNotBlank),
        sizeBytes = apk.optLong("size"),
        pageUrl = release.optString("html_url").ifBlank { UPDATE_RELEASES_PAGE_URL },
    )
}

private val PREVIEW_ASSET = Regex("""^DualSub-Replay-PR(\d+)-build(\d+)-preview\.apk$""")

/** The newest preview build of the same pull request when it is newer than [channel]'s build. */
internal fun parsePreviewRelease(
    json: String,
    channel: UpdateChannel.Preview,
): AppUpdate? {
    val release = JSONObject(json)
    val newest =
        release
            .getJSONArray("assets")
            .objects()
            .mapNotNull { asset ->
                val match = PREVIEW_ASSET.find(asset.optString("name")) ?: return@mapNotNull null
                if (match.groupValues[1].toInt() != channel.pullRequest) return@mapNotNull null
                match.groupValues[2].toInt() to asset
            }.maxByOrNull { it.first } ?: return null
    if (newest.first <= channel.build) return null
    val asset = newest.second
    return AppUpdate(
        versionName = "PR ${channel.pullRequest} build ${newest.first}",
        downloadUrl = asset.getString("browser_download_url"),
        sha256 = sha256Digest(asset.optString("digest")),
        checksumUrl = null,
        sizeBytes = asset.optLong("size"),
        pageUrl = release.optString("html_url").ifBlank { UPDATE_PREVIEW_PAGE_URL },
    )
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** The hex of GitHub's `sha256:<hex>` asset digest. */
internal fun sha256Digest(digest: String): String? =
    digest
        .trim()
        .lowercase()
        .removePrefix("sha256:")
        .takeIf { digest.trim().lowercase().startsWith("sha256:") && SHA256_HEX.matches(it) }

/** The checksum in a `sha256sum` line such as `<hex>  DualSub-Replay.apk`. */
internal fun parseSha256File(text: String): String? =
    text
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.lowercase()
        ?.takeIf(SHA256_HEX::matches)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
