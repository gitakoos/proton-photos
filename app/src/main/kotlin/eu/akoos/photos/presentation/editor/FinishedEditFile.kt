/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.repository.SyncStateRepository
import me.proton.core.domain.entity.UserId
import java.io.File

/** The size and SHA-1 an edit's upload carries, both read from the finished file. */
internal class FinishedEditFile(val sizeBytes: Long, val sha1: String)

/**
 * Runs [writes] on [file] (the EXIF put back, the upload strip), then measures and hashes it. The upload
 * sends exactly the length recorded here, so a size taken before a write would cut the written bytes off.
 * The hash must stay SHA-1 (not SHA-256): Drive pins the digest algorithm, and Drive web rejects a
 * differently derived ContentHash ("Cannot build photo payload...").
 */
internal inline fun finishEditFile(file: File, writes: () -> Unit): FinishedEditFile {
    writes()
    return FinishedEditFile(
        sizeBytes = file.length(),
        sha1 = eu.akoos.photos.data.localcrypto.PlainContent.sha1Hex(file),
    )
}

/** The type a saved edit's file holds, as its upload names it. */
internal enum class SavedImageType(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
}

/** The type [header], the first bytes of a file the editor saved, shows: null for anything else. Pure. */
internal fun savedImageType(header: ByteArray): SavedImageType? = when {
    header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
        SavedImageType.JPEG
    header.size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> SavedImageType.PNG
    header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> SavedImageType.WEBP
    else -> null
}

/** [savedImageType] of [file]'s first bytes. */
internal fun savedImageType(file: File): SavedImageType? {
    val header = ByteArray(12)
    var read = 0
    file.inputStream().use { input ->
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
    }
    return savedImageType(header.copyOf(read))
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * Writes [backedUp], the SYNCED row of a synced edit's device copy, once its upload is in, and records
 * [deviceSha1], the device copy's bytes the upload was made from, as what was backed up: the same record a
 * backup of the file makes, so free-up measures the device file against those bytes.
 */
internal suspend fun recordSyncedEditCopy(
    repo: SyncStateRepository,
    userId: UserId,
    deviceSha1: String,
    backedUp: SyncState,
) {
    repo.upsert(backedUp, userId)
    // After the upsert, which clears the recorded device bytes of a row whose hash or link changed.
    repo.setOriginalHash(backedUp.localUri, deviceSha1)
}
