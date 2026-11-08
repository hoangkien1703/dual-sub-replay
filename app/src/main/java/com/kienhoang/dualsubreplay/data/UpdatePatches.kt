package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * A small download that turns the installed APK into the new one (owner pick 2026-11-08,
 * docs/specs/2026-11-08-smaller-app-updates.md): about 3 MB instead of the whole 40 MB, because the
 * native libraries and data files keep their bytes between versions. `tools/apk_patch.py` makes it.
 * The rebuilt APK must still have the release's SHA-256, so a patch can never make anything else.
 */
internal data class UpdatePatch(
    val downloadUrl: String,
    /** Lowercase hex of the patch file itself. */
    val sha256: String,
    val sizeBytes: Long,
)

/** One `patches` entry of `update-info.json`: the patch from the release with [fromVersionCode]. */
internal data class UpdateInfoPatch(
    val fromVersionCode: Long,
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
)

private val PATCH_NAME = Regex("""^update-from-v\d{1,4}\.\d{1,4}\.\d{1,4}\.patch$""")
private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")

/** The well-formed `patches` entries; anything odd is left out, and the whole APK is downloaded instead. */
internal fun parseUpdateInfoPatches(patches: JSONArray?): List<UpdateInfoPatch> =
    (0 until (patches?.length() ?: 0)).mapNotNull { index ->
        val entry = patches?.optJSONObject(index) ?: return@mapNotNull null
        UpdateInfoPatch(
            fromVersionCode = entry.optLong("from_version_code").takeIf { it > 0 } ?: return@mapNotNull null,
            name = entry.optString("name").takeIf(PATCH_NAME::matches) ?: return@mapNotNull null,
            sha256 = entry.optString("sha256").lowercase().takeIf(HEX_SHA256::matches) ?: return@mapNotNull null,
            sizeBytes = entry.optLong("size").takeIf { it in 1..MAX_UPDATE_BYTES } ?: return@mapNotNull null,
        )
    }

/** The patch of release [versionName] that starts from [installedVersionCode], if it has one. */
internal fun officialPatchFor(
    versionName: String,
    patches: List<UpdateInfoPatch>,
    installedVersionCode: Long,
): UpdatePatch? =
    patches.firstOrNull { it.fromVersionCode == installedVersionCode }?.let {
        UpdatePatch("$UPDATE_RELEASES_PAGE_URL/download/v$versionName/${it.name}", it.sha256, it.sizeBytes)
    }

private val PREVIEW_PATCH = Regex("""^DualSub-Replay-PR(\d+)-build(\d+)-from-build(\d+)\.patch$""")

/** The preview release's patch from build [fromBuild] to build [toBuild] of pull request [pullRequest]. */
internal fun previewPatchFor(
    assets: List<JSONObject>,
    pullRequest: Int,
    fromBuild: Int,
    toBuild: Int,
): UpdatePatch? =
    assets.firstNotNullOfOrNull { asset ->
        val match = PREVIEW_PATCH.find(asset.optString("name")) ?: return@firstNotNullOfOrNull null
        val (pr, to, from) = match.destructured
        if (pr.toInt() != pullRequest || to.toInt() != toBuild || from.toInt() != fromBuild) return@firstNotNullOfOrNull null
        val sha256 = sha256Digest(asset.optString("digest")) ?: return@firstNotNullOfOrNull null
        val size = asset.optLong("size").takeIf { it in 1..MAX_UPDATE_BYTES } ?: return@firstNotNullOfOrNull null
        UpdatePatch(
            asset.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return@firstNotNullOfOrNull null,
            sha256,
            size,
        )
    }

/** The patch does not fit the installed APK, or is damaged; the whole APK is downloaded instead. */
internal class UpdatePatchException(
    message: String,
) : IOException(message)

private val PATCH_MAGIC = "DSRPATCH".toByteArray(Charsets.US_ASCII)
private const val PATCH_VERSION = 1
private const val OP_END = 0
private const val OP_COPY = 1
private const val OP_DATA = 2

/** `tools/apk_patch.py` never writes more new bytes than this in one operation. */
private const val MAX_DATA_BYTES = 1 shl 20

/**
 * Rebuilds the new APK into [output] from the installed [old] APK and the downloaded [patch], and
 * returns the new APK's SHA-256 in lowercase hex. Throws [UpdatePatchException] when the patch starts
 * from another APK, is damaged, or makes something other than the APK it names.
 */
internal fun applyUpdatePatch(
    old: File,
    patch: File,
    output: File,
): String {
    DataInputStream(BufferedInputStream(patch.inputStream())).use { input ->
        val header = ByteArray(PATCH_MAGIC.size)
        readOrFail(input, header)
        if (!header.contentEquals(PATCH_MAGIC) || input.read() != PATCH_VERSION) throw UpdatePatchException("Not an update patch")
        val oldSize = input.readLong()
        val oldHash = ByteArray(32).also { readOrFail(input, it) }
        val newSize = input.readLong()
        val newHash = ByteArray(32).also { readOrFail(input, it) }
        if (newSize !in 0..MAX_UPDATE_BYTES) throw UpdatePatchException("The patch makes too big a file")
        if (old.length() != oldSize || !sha256(old).contentEquals(oldHash)) {
            throw UpdatePatchException("The patch starts from another APK")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val written =
            RandomAccessFile(old, "r").use { source ->
                BufferedOutputStream(FileOutputStream(output)).use { sink ->
                    runOperations(DataInputStream(InflaterInputStream(input)), source, oldSize, newSize) { bytes, count ->
                        digest.update(bytes, 0, count)
                        sink.write(bytes, 0, count)
                    }
                }
            }
        if (written != newSize || !digest.digest().contentEquals(newHash)) {
            throw UpdatePatchException("The patched APK is not the one the patch names")
        }
        return newHash.joinToString("") { "%02x".format(it) }
    }
}

/** Plays the patch's operations, handing each piece of the new APK to [write]; returns its length. */
private fun runOperations(
    ops: DataInputStream,
    source: RandomAccessFile,
    oldSize: Long,
    newSize: Long,
    write: (ByteArray, Int) -> Unit,
): Long {
    val buffer = ByteArray(64 * 1024)
    var written = 0L
    while (true) {
        val length: Long
        when (ops.read()) {
            OP_END -> return written
            OP_COPY -> {
                val offset = ops.readLong()
                length = ops.readLong()
                if (offset < 0 || length < 0 || offset > oldSize - length) throw UpdatePatchException("The patch copies past the old APK")
                if (length > newSize - written) throw UpdatePatchException("The patch makes a bigger APK than it says")
                source.seek(offset)
                copy(length, buffer, { size -> source.readFully(buffer, 0, size) }, write)
            }
            OP_DATA -> {
                length = ops.readInt().toLong()
                if (length !in 0..MAX_DATA_BYTES) throw UpdatePatchException("Damaged patch")
                if (length > newSize - written) throw UpdatePatchException("The patch makes a bigger APK than it says")
                copy(length, buffer, { size -> readOrFail(ops, buffer, size) }, write)
            }
            else -> throw UpdatePatchException("Damaged patch")
        }
        written += length
    }
}

private inline fun copy(
    length: Long,
    buffer: ByteArray,
    read: (Int) -> Unit,
    write: (ByteArray, Int) -> Unit,
) {
    var left = length
    while (left > 0) {
        val size = minOf(left, buffer.size.toLong()).toInt()
        read(size)
        write(buffer, size)
        left -= size
    }
}

private fun readOrFail(
    input: DataInputStream,
    into: ByteArray,
    size: Int = into.size,
) {
    try {
        input.readFully(into, 0, size)
    } catch (_: EOFException) {
        throw UpdatePatchException("The patch ends too early")
    }
}

private fun sha256(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest()
}
