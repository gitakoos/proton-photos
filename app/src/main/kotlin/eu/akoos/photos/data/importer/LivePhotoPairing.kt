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

package eu.akoos.photos.data.importer

import java.util.Locale

/**
 * Finds the Live Photos in an export. Apple and Ente write one as two files, a still and a short video,
 * with the same name in the same folder (`IMG_1234.HEIC` and `IMG_1234.MOV`). Drive keeps such a pair as
 * one photo with a related video, so the import uploads the video behind the still instead of as a photo
 * of its own.
 *
 * Only a certain pair is paired: exactly one still and one video under one name, taken together as far as
 * their dates tell. Anything less certain stays two separate items.
 */
object LivePhotoPairing {

    /** How far apart a pair's still and video may be dated and still be one photo. */
    const val DATE_SLACK_MS = 3_000L

    private val STILL = Regex("\\.(heic|heif|jpe?g)$", RegexOption.IGNORE_CASE)
    private val MOTION = Regex("\\.(mov|mp4)$", RegexOption.IGNORE_CASE)

    /** One entry of an export: its path inside the archive and the capture date its sidecar gave, if any. */
    data class Entry(val name: String, val dateMs: Long?)

    /** The still of a Live Photo by the entry name of its video: `video entry -> still entry`. */
    fun stillByVideo(entries: List<Entry>): Map<String, String> {
        val byStem = HashMap<String, Pair<MutableList<Entry>, MutableList<Entry>>>()
        for (entry in entries) {
            val isStill = STILL.containsMatchIn(entry.name)
            val isMotion = !isStill && MOTION.containsMatchIn(entry.name)
            if (!isStill && !isMotion) continue
            val stem = entry.name.substringBeforeLast('.').lowercase(Locale.ROOT)
            val group = byStem.getOrPut(stem) { mutableListOf<Entry>() to mutableListOf<Entry>() }
            if (isStill) group.first += entry else group.second += entry
        }
        val pairs = HashMap<String, String>()
        for ((stills, motions) in byStem.values) {
            if (stills.size != 1 || motions.size != 1) continue
            if (!sameMoment(stills[0].dateMs, motions[0].dateMs)) continue
            pairs[motions[0].name] = stills[0].name
        }
        return pairs
    }

    /** Without a date on both, the name is all there is and it decides. */
    internal fun sameMoment(still: Long?, motion: Long?): Boolean {
        if (still == null || motion == null) return true
        return kotlin.math.abs(still - motion) <= DATE_SLACK_MS
    }
}
