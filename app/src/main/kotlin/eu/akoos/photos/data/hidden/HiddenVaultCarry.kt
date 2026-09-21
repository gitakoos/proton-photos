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
 * Everything a photo owns that is keyed by its device uri, as it stands the moment it moves into the
 * vault.
 *
 * A hide changes the uri twice — the MediaStore `content://` becomes a private `file://`, and a reveal
 * mints a THIRD uri nobody can predict — so every store keyed by the uri stops describing the photo
 * unless the hide copies the answer forward. None of these can be worked out again from the bytes: a
 * heart, a category and a pinned cover are answers only a person gives, and an album the photo is
 * queued to join is an intent the photo carries rather than anything the file records.
 *
 * [sourceUri] is what the photo was hidden from, kept so a reveal can take the same stores off the uri
 * that no longer names anything as it puts the photo onto the one that does — otherwise the photo comes
 * back correct while a duplicate answer for its old uri lingers for the life of the install.
 */
data class CarriedPhoto(
    val sourceUri: String,
    val favorite: Boolean = false,
    val userTagsCsv: String = "",
    val coverOfFolder: String = "",
    val albumLinkIds: List<String> = emptyList(),
) {
    /** Whether the photo owns anything a reveal has to put back, and therefore whether a reveal has
     *  stores to rewrite at all. Every vaulted photo IS recorded, since [sourceUri] is the handle a
     *  reveal finds its sync row by; this is what keeps the ordinary photo's reveal from writing to
     *  four stores that hold nothing of it. */
    val isWorthCarrying: Boolean
        get() = favorite || userTagsCsv.isNotEmpty() || coverOfFolder.isNotEmpty() || albumLinkIds.isNotEmpty()
}

/**
 * Reads and writes the vault's carried-state record, and decides what a reveal makes of each store it
 * puts back.
 *
 * The record joins the vault's other per-uri maps, keyed the same way and pruned with them, so a photo
 * dropped from the vault takes what it carried with it and nothing survives a hide the user undid.
 *
 * The key is the vault uri and needs no escaping: a vault file is named from a private code and a
 * capture time, and a rename passes the user's text through [HiddenVaultRecords.sanitizedStem], which
 * turns the separator into an underscore. Every field AFTER the key is escaped, because a folder name
 * is the user's and may hold anything at all.
 *
 * Pure, so what a hide carries and what a reveal puts back are both verifiable without a datastore.
 */
object HiddenVaultCarry {

    private const val SEPARATOR = '|'

    /** Fields one entry holds: the vault uri, then the five the photo carries. */
    private const val FIELD_COUNT = 6

    private const val LIST_SEPARATOR = ','

    /** Flatten what [carried] owns into the entry recorded for the vault file at [vaultUri]. */
    fun encode(vaultUri: String, carried: CarriedPhoto): String = listOf(
        vaultUri,
        escape(carried.sourceUri),
        if (carried.favorite) "1" else "0",
        escape(carried.userTagsCsv),
        escape(carried.coverOfFolder),
        carried.albumLinkIds.joinToString(LIST_SEPARATOR.toString()) { escape(it) },
    ).joinToString(SEPARATOR.toString())

    /** One recorded entry split into the vault uri it belongs to and what that photo carries, or null
     *  when it holds fewer fields than one entry has — a record written by nothing this app runs. */
    fun decode(entry: String): Pair<String, CarriedPhoto>? {
        val fields = entry.split(SEPARATOR)
        if (fields.size < FIELD_COUNT) return null
        return fields[0] to CarriedPhoto(
            sourceUri = unescape(fields[1]),
            favorite = fields[2] == "1",
            userTagsCsv = unescape(fields[3]),
            coverOfFolder = unescape(fields[4]),
            albumLinkIds = fields[5].split(LIST_SEPARATOR).filter { it.isNotEmpty() }.map { unescape(it) },
        )
    }

    /** What the photo at [vaultUri] carries, or null when the vault recorded nothing for it, which is
     *  a photo hidden before the record existed. The key is matched before the entry is decoded, so a reveal
     *  walking a vault of thousands costs a prefix test per entry rather than a full unescape. */
    fun carriedBy(entries: Set<String>?, vaultUri: String): CarriedPhoto? {
        if (entries.isNullOrEmpty()) return null
        val prefix = "$vaultUri$SEPARATOR"
        for (entry in entries) {
            if (!entry.startsWith(prefix)) continue
            val decoded = decode(entry) ?: continue
            if (decoded.first == vaultUri) return decoded.second
        }
        return null
    }

    /**
     * The device-side favourite set after the photo held at [vaultUri] came back as [restoredUri].
     *
     * The photo has a heart if it carried one in or was given one while it sat in the vault, where the
     * set keys it by the vault uri. Both uris it answered under before come out in the same breath:
     * neither names a file any more, and the set has no prune of its own, so an entry left on either
     * would outlive the photo and every hide of it.
     *
     * [carried] is null for a photo the hide recorded nothing for — the ordinary photo — which leaves a
     * heart given in the vault as the whole of what there is to move.
     */
    fun favoriteIdsAfterRestore(
        favoriteIds: Set<String>,
        carried: CarriedPhoto?,
        vaultUri: String,
        restoredUri: String,
    ): Set<String> {
        val hearted = carried?.favorite == true || vaultUri in favoriteIds
        val without = favoriteIds - setOfNotNull(vaultUri, carried?.sourceUri?.takeIf { it.isNotEmpty() })
        return if (hearted) without + restoredUri else without
    }

    /**
     * The pinned-folder-cover map after the same reveal, or null when nothing about it changes, so the
     * caller can skip a needless persist.
     *
     * The cover is re-pinned only where the folder still points at the photo that left, or points at
     * nothing at all — the state a media scan leaves once it proves the hidden file is gone. A folder
     * the user has since pinned to some OTHER photo keeps that choice: it is the newer of the two, and
     * a reveal quietly overruling it would be the reveal changing something the vault was never asked
     * to hold.
     */
    fun coversAfterRestore(
        covers: Set<String>,
        carried: CarriedPhoto,
        restoredUri: String,
    ): Set<String>? {
        if (carried.coverOfFolder.isEmpty() || restoredUri.isEmpty()) return null
        val current = FolderCoverMap.parse(covers)[carried.coverOfFolder]
        if (current != null && current != carried.sourceUri) return null
        return FolderCoverMap.withCover(covers, carried.coverOfFolder, restoredUri)
    }

    /** [value] with every character the flatten reads as structure spelled out, so a folder name
     *  holding a separator survives the round trip whole. The escape character goes first, or
     *  unescaping would undo it twice. */
    private fun escape(value: String): String =
        value.replace("%", "%25")
            .replace(SEPARATOR.toString(), "%7C")
            .replace(LIST_SEPARATOR.toString(), "%2C")

    /** The inverse of [escape], taking the escape character last for the same reason. */
    private fun unescape(value: String): String =
        value.replace("%2C", LIST_SEPARATOR.toString())
            .replace("%7C", SEPARATOR.toString())
            .replace("%25", "%")
}
