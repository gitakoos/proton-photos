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

package eu.akoos.photos.util

import java.security.MessageDigest

private val SAFE_ID = Regex("^[A-Za-z0-9_=-]{1,200}$")

/**
 * [id] as a file-name component that can never leave the directory it is joined to. Server link, share
 * and volume ids are base64url-like, so every real id passes unchanged and keeps its existing cache
 * file. Anything else (a path separator, `..`, other characters, empty, over-long) maps to the SHA-256
 * hex of the id, which is deterministic and holds only `[0-9a-f]`.
 */
fun safeFileId(id: String): String {
    if (SAFE_ID.matches(id)) return id
    return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).toLowerHex()
}
