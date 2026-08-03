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
 * Pure helpers for the pinned device-folder covers (SettingsKeys.FOLDER_COVER_URI_MAP), persisted as
 * a set of "folderName|coverUri" strings — the flatten every other map-shaped preference here uses,
 * since DataStore has no Map type.
 *
 * A device folder is identified by its MediaStore bucket display name, matching every other
 * per-folder preference, so the key is that name and the value is the chosen photo's media uri.
 *
 * The uri is read from the RIGHT of the last separator and everything before it is the folder name,
 * because a media uri never contains the separator while a folder name may: a folder literally named
 * "Trip|2026" would otherwise resolve to "Trip" and pin a cover onto a folder that does not exist.
 *
 * Centralised so the write side (the folder's selection dock), both read sides (the folder card and
 * the folder hero) and the prune (the media scan) share one encoding and can be verified without a
 * device.
 */
object FolderCoverMap {

    private const val SEPARATOR = '|'

    /** Flatten one pinned cover into its persisted entry. */
    fun encode(folderName: String, coverUri: String): String = "$folderName$SEPARATOR$coverUri"

    /** Parse the persisted entry set into a folder-name -> cover-uri map, dropping malformed entries
     *  and any that name no folder or no uri. */
    fun parse(entries: Set<String>?): Map<String, String> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>(entries.size)
        for (entry in entries) split(entry)?.let { out[it.first] = it.second }
        return out
    }

    /** The stored set with [folderName]'s cover set to [coverUri], replacing whatever it pinned
     *  before. One cover per folder, so the previous entry goes rather than accumulating. */
    fun withCover(entries: Set<String>, folderName: String, coverUri: String): Set<String> {
        if (folderName.isEmpty() || coverUri.isEmpty()) return entries
        val kept = entries.filterTo(HashSet(entries.size + 1)) { split(it)?.first != folderName }
        kept += encode(folderName, coverUri)
        return kept
    }

    /**
     * Drop every entry pinning one of [coverUris], so the map does not keep naming photos that are
     * gone. Returns null when nothing changed, so the caller can skip a needless persist.
     *
     * Takes the uris to DROP rather than the ones to keep, because the caller decides elsewhere which
     * of them a media scan actually PROVES are deleted (see [MediaScanCoverage]) and only then
     * re-reads the persisted set to edit it. A keep-list would instead delete every entry the scan
     * could not see, which is the shape of a device that grants photos but denies videos.
     */
    fun dropUris(entries: Set<String>, coverUris: Set<String>): Set<String>? {
        if (entries.isEmpty() || coverUris.isEmpty()) return null
        val kept = entries.filterTo(HashSet()) { entry ->
            val uri = split(entry)?.second
            uri == null || uri !in coverUris
        }
        return if (kept.size == entries.size) null else kept
    }

    /** One persisted entry split into the folder it pins and the uri it pins there, or null when it
     *  carries neither. */
    private fun split(entry: String): Pair<String, String>? {
        val lastSep = entry.lastIndexOf(SEPARATOR)
        if (lastSep <= 0 || lastSep == entry.length - 1) return null
        return entry.substring(0, lastSep) to entry.substring(lastSep + 1)
    }
}
