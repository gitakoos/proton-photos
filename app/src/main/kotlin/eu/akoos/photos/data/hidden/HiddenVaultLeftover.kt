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

/**
 * The decision a half-finished hide needs, expressed without Android so it can be reasoned about and
 * tested on its own.
 *
 * Hiding a device photo is three separate durable steps — copy the bytes into the vault, record the
 * entry, remove the MediaStore original — and a process death can land between any two of them. The
 * bytes are the only irreplaceable part, so every rule below is written to keep them: an unknown is
 * always resolved towards publishing a vault entry, never towards deleting one. The worst outcome of
 * a wrong publish is one duplicate the user can unhide; the worst outcome of a wrong delete is a
 * photo that no longer exists anywhere.
 */
enum class HiddenLeftoverAction {
    /** Blob and record agree — nothing to repair. */
    NONE,

    /** The MediaStore original is still there, so the hide never took effect. Drop the vault copy and
     *  everything recorded for it, leaving the photo exactly where the user can already see it. */
    DISCARD,

    /** The original is gone, so the delete did take effect. Publish the record so the photo shows in
     *  the vault and can be restored, instead of sitting there invisible. */
    CONFIRM,

    /** A vault file nothing refers to. Publish it too — its name still carries a capture time, and an
     *  unreferenced blob is otherwise invisible and would go with the vault at sign-out. */
    ADOPT,

    /** A record whose bytes are gone. Nothing is recoverable, so clear what is left of it. */
    FORGET,
}

/**
 * One vault file (or one unfinished hide) and everything known about it.
 *
 * @param privateUri the `file://` uri of the vault copy, which is the key every record is stored under
 * @param blobPresent the copy is on disk
 * @param recorded the vault index already lists [privateUri], so the photo is visible and restorable
 * @param journalled a hide wrote its intent for [privateUri] and never reached its confirm step
 * @param sourcePresent the MediaStore original the journal names still resolves; null when there is
 *   no journal entry, or when its source could not be checked
 */
data class HiddenLeftover(
    val privateUri: String,
    val blobPresent: Boolean,
    val recorded: Boolean,
    val journalled: Boolean,
    val sourcePresent: Boolean?,
)

object HiddenVaultLeftovers {

    /** Separator the flattened `"key|value"` preference sets use throughout. */
    private const val SEPARATOR = '|'

    /** Flattens one journal entry, holding the vault copy against the original it came from. */
    fun journalEntry(privateUri: String, sourceUri: String): String = "$privateUri$SEPARATOR$sourceUri"

    /**
     * privateUri → sourceUri for a stored journal set. An entry with no separator keeps a blank
     * source, which reads as "cannot be checked" and therefore resolves towards keeping the bytes.
     * A blank key is dropped, since it names no vault file.
     */
    fun parseJournal(entries: Set<String>): Map<String, String> =
        entries.mapNotNull { entry ->
            val key = entry.substringBefore(SEPARATOR)
            if (key.isBlank()) null else key to entry.substringAfter(SEPARATOR, "")
        }.toMap()

    /**
     * How many photos emptying the vault costs, from the vault files on disk ([blobUris]) and the
     * vault entries the index lists ([recordedVaultUris]).
     *
     * Sign-out deletes the vault directory whole, so a file counts whether or not anything refers to
     * it. The index alone answers a different question and answers it too low: a hide interrupted
     * between removing the original and recording the entry leaves a file nothing names, and that
     * file is the only copy of the photo left in existence. The union is the safe direction — an
     * index entry whose bytes are already gone puts one photo too many in the warning, while a file
     * left out of it costs the user the photo.
     *
     * [recordedVaultUris] is the index filtered down to vault files: an entry naming a photo still
     * on the device or on Drive is a display filter that sign-out merely drops.
     */
    fun vaultedCount(blobUris: Set<String>, recordedVaultUris: Set<String>): Int =
        vaultedUris(blobUris, recordedVaultUris).size

    /**
     * The same photos [vaultedCount] counts, named rather than tallied, for a caller that has to say
     * something about which of them they are.
     *
     * The sign-out confirmation splits them by whether a Drive copy survives the wipe, and only a uri
     * can be matched against the vault's cloud-id records. A file nothing names falls on the
     * device-only side of that split by construction, which is the safe direction: it is the case
     * where the warning must not promise the photo is recoverable.
     */
    fun vaultedUris(blobUris: Set<String>, recordedVaultUris: Set<String>): Set<String> =
        blobUris + recordedVaultUris

    /**
     * What has to happen to [leftover].
     *
     * Order matters: a missing blob settles the case before the source is consulted, because bytes
     * that are gone cannot be published whatever the original did. Past that, only a source proven
     * to still exist authorises destroying a copy.
     */
    fun decide(leftover: HiddenLeftover): HiddenLeftoverAction = when {
        leftover.journalled && !leftover.blobPresent -> HiddenLeftoverAction.FORGET
        leftover.journalled && leftover.sourcePresent == true -> HiddenLeftoverAction.DISCARD
        leftover.journalled -> HiddenLeftoverAction.CONFIRM
        leftover.blobPresent && !leftover.recorded -> HiddenLeftoverAction.ADOPT
        else -> HiddenLeftoverAction.NONE
    }

    /**
     * Every vault file and every unfinished hide, paired with what is known about each.
     *
     * The candidates are the union of the two, so a blob with no record and a record with no blob are
     * both seen. A recorded uri that is neither on disk nor journalled is not a leftover of a hide and
     * is left to the vault's own reads.
     *
     * @param sourcePresence sourceUri → exists, holding only the sources that could be checked; a
     *   source missing from the map stays unknown.
     */
    fun leftovers(
        blobUris: Set<String>,
        recordedUris: Set<String>,
        journal: Map<String, String>,
        sourcePresence: Map<String, Boolean>,
    ): List<HiddenLeftover> =
        (blobUris + journal.keys).map { uri ->
            val source = journal[uri]
            HiddenLeftover(
                privateUri = uri,
                blobPresent = uri in blobUris,
                recorded = uri in recordedUris,
                journalled = source != null,
                sourcePresent = source?.let { sourcePresence[it] },
            )
        }
}
