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

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex, two digits per byte: what `"%02x".format` gives, without a formatter per byte. Reconcile
 *  turns a hash into hex for every photo of the library on every pass. */
fun ByteArray.toLowerHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt()
        out[i * 2] = HEX_DIGITS[(b shr 4) and 0xF]
        out[i * 2 + 1] = HEX_DIGITS[b and 0xF]
    }
    return String(out)
}
