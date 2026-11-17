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
