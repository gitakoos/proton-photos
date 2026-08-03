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

package eu.akoos.photos.data.hidden

import eu.akoos.photos.util.FolderCoverMap

/**
 * Everything the vault durably holds about its photos: the index of what it holds, and the four
 * per-uri maps keyed by vault uri as `"uri|value"`.
 *
 * Grouped so the effect of one change can be stated across all five at once — a vault file moves, and
 * every record naming it has to move with it or the photo loses its name, its folder, its cloud twin
 * or the heart, categories, pinned cover and album queue it carried in with it.
 */
data class VaultRecords(
    val index: Set<String>,
    val names: Set<String>,
    val folders: Set<String>,
    val cloudIds: Set<String>,
    val carried: Set<String> = emptySet(),
)

/**
 * What a change to one vault file writes into each of the vault's records, and into the stores that
 * are not the vault's yet answer under the same key.
 *
 * A vault file's uri IS its key: the index lists it, and each of the four maps is keyed by it. So
 * anything that renames the file on disk — the user renaming the photo, a date edit restamping the
 * capture time the name carries — re-keys all four, and anything that adds a second file adds a
 * second set of records rather than sharing the first.
 *
 * The heart and the pinned folder cover join them for one reason: a vaulted photo is offered both, and
 * both are stored under its uri, so a move that left them behind would strip a photo of an answer only
 * the user could give.
 *
 * The name record deserves its own note, because it is the one a rename must OVERWRITE rather than
 * carry: it is what a restore writes the file back to the device under, so leaving the old name there
 * would let a rename made in the vault vanish the moment the photo comes back out.
 *
 * Pure, so what a rename costs each record is verifiable without a datastore.
 */
object HiddenVaultRecords {

    /**
     * The stem a name the user typed reduces to: the extension they may have typed dropped, since the
     * file keeps its own and that is what every MIME lookup reads, and every character a filesystem
     * refuses replaced. A name that reduces to nothing falls back to [FALLBACK_STEM], so a vault file
     * can never end up with no name at all.
     */
    fun sanitizedStem(typed: String): String =
        typed.trim().substringBeforeLast('.', typed.trim())
            .replace(ILLEGAL_IN_FILENAME, "_")
            .ifBlank { FALLBACK_STEM }

    /** The name a vault photo is recorded — and later restored — under when the user asks for [typed]
     *  on a file whose container is [extension]. The extension is the FILE's, never the typed one. */
    fun recordedName(typed: String, extension: String): String {
        val ext = extension.trim().trimStart('.')
        return sanitizedStem(typed) + if (ext.isEmpty()) "" else ".$ext"
    }

    /**
     * [records] after the vault file at [oldUri] became [newUri].
     *
     * [displayName] is the name the photo is shown under and restored as. A rename passes the new one,
     * which REPLACES what was recorded — that record is what a reveal writes the file back to the
     * device under, so a rename that left it alone would be undone by the reveal. A move made for some
     * other reason passes null and the recorded name is carried across unchanged; an entry that
     * recorded no name gains none, since the file's own name is a private code and putting that on the
     * user's device is worse than letting the reveal name the file itself.
     *
     * The folder, the cloud twin and what the photo carried in follow the file unchanged: none of them
     * is affected by what the file is called. An entry that recorded no folder or no cloud id gains
     * none, since a rename knows nothing either of them could be derived from.
     */
    fun renamed(
        records: VaultRecords,
        oldUri: String,
        newUri: String,
        displayName: String?,
    ): VaultRecords = VaultRecords(
        index = if (oldUri in records.index) records.index - oldUri + newUri else records.index,
        names = if (displayName == null) records.names.rekeyed(oldUri, newUri)
            else records.names.filterNot { it.keyedBy(oldUri) || it.keyedBy(newUri) }.toSet() +
                "$newUri|$displayName",
        folders = records.folders.rekeyed(oldUri, newUri),
        cloudIds = records.cloudIds.rekeyed(oldUri, newUri),
        carried = records.carried.rekeyed(oldUri, newUri),
    )

    /**
     * [records] after a second vault file [copyUri] was made from the one at [sourceUri], to be shown
     * and restored as [displayName]. The source keeps every record it had.
     *
     * The copy inherits the source's folder, so it returns to the same place the source came from, and
     * inherits NO cloud id: that id names one photo on Drive, and a second vault entry claiming it
     * would have both restores transplant the same sync row, leaving one of the two photos paired to a
     * cloud file that is not its own.
     *
     * It inherits nothing the source carried in either, for the same reason read the other way round:
     * a carried record describes the ONE device photo it was taken from, down to the uri whose heart,
     * categories and album queue a reveal moves off. A copy was never that photo, and a second entry
     * claiming its uri would have the two reveals fight over the same stores.
     */
    fun copied(
        records: VaultRecords,
        sourceUri: String,
        copyUri: String,
        displayName: String,
    ): VaultRecords = VaultRecords(
        index = records.index + copyUri,
        names = records.names.filterNot { it.keyedBy(copyUri) }.toSet() + "$copyUri|$displayName",
        folders = records.folders + records.folders
            .filter { it.keyedBy(sourceUri) }
            .map { "$copyUri|${it.substringAfter('|')}" },
        cloudIds = records.cloudIds,
        carried = records.carried,
    )

    /**
     * [favoriteIds] after the vault file at [oldUri] became [newUri], or null when the photo was not
     * one of them and nothing changes.
     *
     * The heart is stored under the photo's uri and belongs to no map the vault owns, yet the viewer
     * offers it on a vaulted photo like any other: leaving it behind would take the heart off a photo
     * that never lost it, and leave an entry naming a file that is gone with nothing to ever collect it.
     */
    fun favoritesAfterMove(favoriteIds: Set<String>, oldUri: String, newUri: String): Set<String>? =
        if (oldUri !in favoriteIds) null else favoriteIds - oldUri + newUri

    /**
     * The pinned device-folder covers after the same move, or null when no folder pinned that photo.
     *
     * Keyed by folder with the photo as the VALUE, so this rewrites the side the move touched. Every
     * folder pinned to the photo follows it, since a photo can be the chosen cover of more than one.
     */
    fun coversAfterMove(covers: Set<String>, oldUri: String, newUri: String): Set<String>? {
        val pinnedTo = FolderCoverMap.parse(covers).filterValues { it == oldUri }.keys
        if (pinnedTo.isEmpty()) return null
        return pinnedTo.fold(covers) { acc, folder -> FolderCoverMap.withCover(acc, folder, newUri) }
    }

    /**
     * The vault uris whose photo still has a Drive copy, read out of the cloud-id records.
     *
     * A vaulted photo that was backed up keeps its Drive copy untouched, and that is the difference
     * between one the user can bring back from Drive and one whose only remaining bytes are the vault
     * file. Every surface drawing the green cloud over a vault photo asks this, so all of them read the
     * one record rather than each parsing it their own way.
     *
     * A record naming no uri, or holding no id after the separator, answers for nothing and is left
     * out: the badge means a Drive copy exists, so an entry that cannot name one must not raise it.
     */
    fun pairedUris(cloudIds: Set<String>): Set<String> =
        cloudIds.mapNotNullTo(mutableSetOf()) { entry ->
            val uri = entry.substringBefore('|')
            if (uri.isBlank() || entry.substringAfter('|', "").isBlank()) null else uri
        }

    /**
     * [entries] without every `"uri|value"` record keyed by one of [keys].
     *
     * The one rule for dropping a vault file's records, shared by every path that stops a file being a
     * hidden photo: a reveal that put it back on the device, a hide the user did not confirm, and a
     * photo destroyed inside the vault. Sharing it is what keeps a record added later from surviving one
     * of those three because only the other two were taught about it.
     *
     * Matched on the whole key rather than on a prefix, so a vault file whose uri merely begins like
     * another's cannot take its neighbour's records with it.
     */
    fun dropped(entries: Set<String>, keys: Set<String>): Set<String> =
        if (entries.isEmpty() || keys.isEmpty()) entries
        else entries.filterNotTo(mutableSetOf()) { it.substringBefore('|') in keys }

    private const val FALLBACK_STEM = "renamed"

    private val ILLEGAL_IN_FILENAME = Regex("[\\\\/:*?\"<>|]")

    private fun String.keyedBy(uri: String): Boolean = substringBefore('|') == uri

    private fun Set<String>.rekeyed(oldUri: String, newUri: String): Set<String> =
        mapTo(mutableSetOf()) { entry ->
            if (entry.keyedBy(oldUri)) "$newUri|${entry.substringAfter('|')}" else entry
        }
}
