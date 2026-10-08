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

package eu.akoos.photos.data.upload.compression

import eu.akoos.photos.domain.entity.compression.CompressionSkipReason

/**
 * Keep-or-discard rules for a finished transcode: it must be at least [MIN_SAVING_FRACTION] smaller
 * than the source, or the original uploads. From Android 12, VBR encodes up to 1080p are held to a
 * quality floor and can come out larger than asked, so a miss gets one constant-bitrate retry.
 */
object TranscodeAcceptance {

    const val MIN_SAVING_FRACTION = 0.05

    enum class Verdict { ACCEPT, SAVING_TOO_SMALL, NOT_SMALLER, EMPTY }

    fun judge(outputBytes: Long, sourceBytes: Long): Verdict = when {
        outputBytes <= 0L -> Verdict.EMPTY
        sourceBytes <= 0L || outputBytes >= sourceBytes -> Verdict.NOT_SMALLER
        (sourceBytes - outputBytes).toDouble() / sourceBytes < MIN_SAVING_FRACTION -> Verdict.SAVING_TOO_SMALL
        else -> Verdict.ACCEPT
    }

    fun shouldRetryWithCbr(verdict: Verdict): Boolean =
        verdict == Verdict.NOT_SMALLER || verdict == Verdict.SAVING_TOO_SMALL

    fun reasonFor(verdict: Verdict): CompressionSkipReason? = when (verdict) {
        Verdict.ACCEPT -> null
        Verdict.SAVING_TOO_SMALL -> CompressionSkipReason.SAVING_TOO_SMALL
        Verdict.NOT_SMALLER -> CompressionSkipReason.NOT_SMALLER
        Verdict.EMPTY -> CompressionSkipReason.ENCODER_ERROR
    }
}
