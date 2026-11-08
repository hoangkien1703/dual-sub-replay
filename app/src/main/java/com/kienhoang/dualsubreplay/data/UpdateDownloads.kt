package com.kienhoang.dualsubreplay.data

import org.json.JSONObject

/**
 * What the update download in the app's cache is (owner request 2026-11-08): kept beside the APK, so a
 * download survives the app being closed, a broken one carries on, and the APK goes once it is installed.
 */
internal data class UpdateDownloadRecord(
    val update: AppUpdate,
    /** The SHA-256 the finished file must have: GitHub's digest or its `.sha256` file. */
    val sha256: String,
    /** The user tapped Update, so the dialog shows again when the app is next opened. */
    val asked: Boolean,
)

internal fun updateDownloadRecordJson(record: UpdateDownloadRecord): String =
    JSONObject()
        .put("schema", 1)
        .put("version", record.update.versionName)
        .put("url", record.update.downloadUrl)
        .put("sha256", record.sha256)
        .put("size", record.update.sizeBytes)
        .put("page", record.update.pageUrl)
        .put("critical", record.update.critical)
        .put("security_fix_code", record.update.securityFixCode)
        .put("asked", record.asked)
        .toString()

/** The record, or null when it is another schema, not GitHub's, or anything is missing or malformed. */
internal fun parseUpdateDownloadRecord(json: String): UpdateDownloadRecord? {
    val record = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (record.optInt("schema") != 1) return null
    val version = record.optString("version").takeIf(String::isNotBlank) ?: return null
    val url = record.optString("url").takeIf(::isGitHubHttpsUrl) ?: return null
    val sha256 = record.optString("sha256").lowercase().takeIf { it.length == 64 && it.all(::isHexDigit) } ?: return null
    return UpdateDownloadRecord(
        update =
            AppUpdate(
                versionName = version,
                downloadUrl = url,
                sha256 = sha256,
                checksumUrl = null,
                sizeBytes = record.optLong("size").coerceAtLeast(0),
                pageUrl = record.optString("page").takeIf(::isGitHubHttpsUrl) ?: UPDATE_RELEASES_PAGE_URL,
                critical = record.optBoolean("critical"),
                securityFixCode = record.optLong("security_fix_code").coerceAtLeast(0),
            ),
        sha256 = sha256,
        asked = record.optBoolean("asked"),
    )
}

/** The download in the cache is the file [update] offers. */
internal fun isSameUpdateDownload(
    record: UpdateDownloadRecord,
    update: AppUpdate,
    expectedSha256: String? = update.sha256,
): Boolean =
    record.update.versionName == update.versionName &&
        record.update.downloadUrl == update.downloadUrl &&
        (expectedSha256 == null || expectedSha256 == record.sha256)

/** A half download older than this is deleted when the app opens; Check for updates starts afresh. */
internal const val STALE_PART_MS = 3L * 24 * 60 * 60 * 1000

/** Where a broken download carries on: the first byte of `Content-Range: bytes <first>-<last>/<size>`, or null. */
internal fun contentRangeStart(header: String?): Long? =
    header
        ?.trim()
        ?.takeIf { it.startsWith("bytes ", ignoreCase = true) }
        ?.substring(6)
        ?.substringBefore('-')
        ?.trim()
        ?.toLongOrNull()

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

private fun isGitHubHttpsUrl(url: String): Boolean {
    if (!url.startsWith("https://")) return false
    val host = url.removePrefix("https://").substringBefore('/').substringBefore(':')
    return isAllowedUpdateHost(host)
}
