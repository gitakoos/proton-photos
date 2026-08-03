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

/**
 * How much of the device a finished media scan actually PROVES, for every uri-keyed side store that
 * prunes itself against the scan's result — the local-tag cache ([LocalTagPrune]) and the recorded
 * capture dates (SettingsKeys.DOWNLOAD_DATE_OVERRIDES). Each store keeps a row per file and would
 * otherwise grow for as long as the app is installed, and a scan already holds every uri it saw,
 * which makes "this row's file is gone" answerable for free.
 *
 * The catch is that a scan covers less of the device than its result suggests, and every gap looks
 * exactly like a deletion:
 *
 *  - a collection whose query fails contributes nothing, so every row of that collection reads as
 *    gone. Reachable, not hypothetical: image and video access are separate permissions from
 *    Android 13, so a user who grants photos but not videos fails the video query on every scan;
 *  - a trashed file still exists and stays restorable for weeks, yet a default MediaStore query
 *    omits it;
 *  - a vaulted item lives in app-private storage under a file:// uri, which no MediaStore query
 *    ever returns;
 *  - partial photo access hands back only the files picked in the system dialog while the
 *    permission itself still reads as granted.
 *
 * So absence from the live set counts as proof of deletion only under BOTH conditions below, and a
 * uri that fails either is unproven rather than dead:
 *
 *  1. the live set holds something. An empty one is the shape of a scan that failed outright or ran
 *     before its data arrived, and taking it at face value would empty a whole store in one pass;
 *  2. the uri sits under a collection root the scan enumerated successfully. This is what keeps a
 *     videos-denied device from losing every video row, and what leaves file:// vault rows alone.
 *
 * Both still leave each store bounded, because what scales with the library is the ordinary rows,
 * and those sit under the roots a working scan does enumerate. A store may add its OWN reasons to
 * keep a row past this point; none may skip these.
 */
object MediaScanCoverage {

    /**
     * The subset of [uris] this scan proves are deleted. Empty when nothing qualifies, so the caller
     * can skip the write entirely.
     *
     * [scannedRoots] carries the collection uris whose query SUCCEEDED, e.g.
     * `content://media/external/images/media`. A root whose query threw, returned no cursor, or was
     * never attempted must be left out: its uris are unproven rather than dead.
     */
    fun provenDeleted(
        uris: Collection<String>,
        liveUris: Set<String>,
        scannedRoots: Collection<String>,
    ): List<String> {
        if (uris.isEmpty() || liveUris.isEmpty() || scannedRoots.isEmpty()) return emptyList()
        // Item uris are the root with the MediaStore id appended, so the trailing separator is what
        // makes this a containment test rather than a name-prefix one.
        val prefixes = scannedRoots.map { "$it/" }
        return uris.filter { uri -> uri !in liveUris && prefixes.any { uri.startsWith(it) } }
    }
}
