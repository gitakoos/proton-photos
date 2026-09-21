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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.HiddenCaptureTime

/**
 * What the vault knows about a device folder, expressed without Android so it can be reasoned about
 * and tested on its own.
 *
 * A folder whose photos are all in the vault has no MediaStore row left carrying its bucket name, so
 * nothing on the device can say the folder ever existed. The records written at hide time are the
 * only surviving evidence: the folder name in `HIDDEN_FOLDER_NAMES` and, per vaulted photo, the
 * folder it came from in `HIDDEN_URI_SOURCE_FOLDER_MAP`. Reading the vault's folder list from those
 * rather than from a device scan is what keeps a fully-vaulted folder revealable.
 */
object HiddenFolderRecords {

    /** Separator the flattened `"uri|value"` preference sets use throughout. */
    private const val SEPARATOR = '|'

    /**
     * The bucket name a recorded source folder belongs to.
     *
     * A record holds the full MediaStore RELATIVE_PATH ("DCIM/Camera", "Pictures/Trip 2026", an
     * app's "Android/media/…/WhatsApp Images"), while every per-folder preference keys on the bucket
     * display name MediaStore derives from it — the last segment. Matching the two is this one rule.
     */
    fun bucketOf(sourceFolder: String): String =
        sourceFolder.trim().trim('/').substringAfterLast('/')

    /**
     * bucket name → the vault uris that came from it, newest first.
     *
     * [sourceFolderEntries] are the raw `"vaultUri|sourceFolder"` records. The key is read up to the
     * FIRST separator and the value is everything after it, so a folder name that itself contains a
     * separator survives the round trip — a vault uri never can, since it names a file this app
     * generated.
     *
     * Only uris in [vaultedUris] (the published index) are kept. A record written by a hide that has
     * not confirmed yet is therefore invisible here, which is what stops an in-flight hide from
     * inflating a folder's count or offering a photo the user can still see as a restore target.
     *
     * The order is the capture time each vault file name records, so the folder's cover is its newest
     * photo exactly as it is on the Albums grid. A name recording no time sorts last, and the uri
     * breaks ties, so the list is stable across reads.
     */
    fun vaultedByBucket(
        sourceFolderEntries: Set<String>,
        vaultedUris: Set<String>,
    ): Map<String, List<String>> = sourceFolderEntries
        .mapNotNull { entry ->
            val uri = entry.substringBefore(SEPARATOR)
            if (uri.isBlank() || uri !in vaultedUris) return@mapNotNull null
            val bucket = bucketOf(entry.substringAfter(SEPARATOR, ""))
            if (bucket.isBlank()) null else bucket to uri
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, uris) ->
            uris.sortedWith(
                compareByDescending<String> { HiddenCaptureTime.parse(it.substringAfterLast('/')) ?: Long.MIN_VALUE }
                    .thenBy { it },
            )
        }

    /**
     * The vaulted uris the flat hidden list shows: everything in [vaultedUris] except what belongs to
     * a folder the user hid.
     *
     * A photo vaulted along with its whole folder is reached inside that folder's card, exactly as it
     * was reached inside the folder on the Albums grid, so listing it loose as well shows it twice.
     * The split needs no device: the `"vaultUri|sourceFolder"` records say which folder each vaulted
     * photo came from, and [hiddenFolderNames] says which of those folders are hidden.
     *
     * A photo the records say nothing about, and one whose recorded folder is NOT hidden, both stay in
     * the flat list — each was hidden on its own, and the flat list is the only place it can be
     * reached from.
     */
    fun looseVaultedUris(
        vaultedUris: Set<String>,
        sourceFolderEntries: Set<String>,
        hiddenFolderNames: Set<String>,
    ): Set<String> {
        if (hiddenFolderNames.isEmpty()) return vaultedUris
        val insideHiddenFolder = mutableSetOf<String>()
        for (entry in sourceFolderEntries) {
            val uri = entry.substringBefore(SEPARATOR)
            if (uri.isBlank()) continue
            val bucket = bucketOf(entry.substringAfter(SEPARATOR, ""))
            if (bucket.isNotBlank() && bucket in hiddenFolderNames) insideHiddenFolder += uri
        }
        return vaultedUris - insideHiddenFolder
    }

    /**
     * The photos a folder screen lists: what the vault holds for the folder, or what the device does.
     *
     * The two sides answer different questions, so adding them together answers neither, and
     * [showsVault] is which question was asked — the card the user opened the screen from. One folder
     * can hold a card on each side at once, so the folder's own hidden state cannot decide this: it
     * is true for both cards.
     *
     * [live] is what MediaStore still carries for the bucket, and for a hidden folder that is exactly
     * the set of photos which are NOT in the vault — a shot taken since the hide, one a stopped hide
     * never reached, one restored by hand. Listing any of them inside the hidden area would present a
     * photo every other gallery app can open as one that is put away, which is the single thing that
     * area may never do. Reading [vaulted] alone makes the promise true by construction.
     *
     * The device side drops [vaulted] for the mirror-image reason: a vaulted photo is reached in the
     * hidden area behind its lock, and this screen is behind none, so surfacing it here would undo
     * the hide the user asked for.
     *
     * A folder's name is recorded before the first copy, so a hide in flight is a folder whose vault
     * is still filling. The vault side then lists what has landed and grows with it, while the
     * progress pill accounts for the rest — an honest split, and the only one that cannot mistake a
     * photo waiting to be copied for a photo already protected.
     *
     * The chosen list comes through in the order given, so the arrangement is the caller's sort alone
     * and nothing reshuffles between emissions.
     */
    fun folderPhotos(
        live: List<GalleryItem>,
        vaulted: List<GalleryItem.LocalOnly>,
        showsVault: Boolean,
    ): List<GalleryItem> = if (showsVault) vaulted else live

    /**
     * One photo on its way into the vault: the device file the copy is taken from, and the Drive
     * copy it is paired to when it has one.
     *
     * [item] is the photo as its surface holds it, which is what the delete of the original is run
     * against; [local] is that same photo's device file, lifted out so a caller never has to ask a
     * gallery item which of its halves it is. [cloudLinkId] is the pairing the reveal transplants
     * back onto the restored file, and is null exactly for a photo that has no Drive copy.
     *
     * [sizeBytes] and [captureTimeMs] are read here rather than off [local] because a paired photo
     * can reach a surface with only its cloud half's facts known: the size is what the whole-batch
     * free-space check is measured on, and the capture time is what the vault file's own name
     * records, so both have to answer for the photo rather than for whichever half was fuller.
     */
    data class VaultTarget(
        val item: GalleryItem,
        val local: LocalMediaItem,
        val cloudLinkId: String?,
        val sizeBytes: Long,
    ) {
        val captureTimeMs: Long get() = item.captureTimeMs
    }

    /**
     * The two halves a hide acts on: [vaultable] are the device files that move into app-private
     * storage, [cloudLinkIds] the cloud copies that hide client-side.
     *
     * Both halves are part of one hide, so a caller that acts on one and drops the other has hidden
     * only part of what the user asked for.
     */
    data class HideSplit(
        val vaultable: List<VaultTarget>,
        val cloudLinkIds: List<String>,
    ) {
        val isEmpty: Boolean get() = vaultable.isEmpty() && cloudLinkIds.isEmpty()

        companion object {
            val EMPTY = HideSplit(emptyList(), emptyList())
        }
    }

    /**
     * Route [items] into the two halves a hide acts on.
     *
     * Every photo with a device file is vaulted, whether or not it is backed up. Hiding has to mean
     * the file leaves the device gallery, and a photo whose original stayed in place would go on
     * being visible to every other app on the phone — so a backed-up photo moves into the vault
     * exactly as a device-only one does, carrying its cloud linkId so the reveal re-pairs it to the
     * Drive copy instead of uploading a second one. The Drive copy itself is never touched by a hide.
     *
     * A cloud-only photo has no device file to move, so it is the one kind that hides by filter: its
     * linkId drops it from every listing this app draws while the Drive copy stays as it is.
     *
     * [cloudLinkIdByUri] is how a caller holding device rows alone learns the pairing: a device row
     * carries no cloud identity, so a backed-up photo arrives as a [GalleryItem.LocalOnly] and its
     * sync pairing is the only thing that can name the Drive copy. A caller holding merged items
     * passes nothing and each item's own type answers.
     */
    fun hideSplit(
        items: List<GalleryItem>,
        cloudLinkIdByUri: Map<String, String> = emptyMap(),
    ): HideSplit {
        val vaultable = mutableListOf<VaultTarget>()
        val cloudLinkIds = mutableListOf<String>()
        for (item in items) {
            when (item) {
                is GalleryItem.LocalOnly -> vaultable += VaultTarget(
                    item = item,
                    local = item.local,
                    cloudLinkId = cloudLinkIdByUri[item.local.uri],
                    sizeBytes = item.local.sizeBytes,
                )
                is GalleryItem.Synced -> vaultable += VaultTarget(
                    item = item,
                    local = item.local,
                    cloudLinkId = item.cloud.linkId,
                    // A merged item states the device file's size; a member reached through its album
                    // alone states only the cloud copy's, and the two hold the same photo.
                    sizeBytes = item.local.sizeBytes.takeIf { it > 0L } ?: item.cloud.sizeBytes,
                )
                is GalleryItem.CloudOnly -> cloudLinkIds += item.cloud.linkId
            }
        }
        return HideSplit(vaultable, cloudLinkIds)
    }

    /**
     * The same routing scoped to one device folder: whatever of [items] belongs to [bucketName].
     *
     * Every folder hide runs through here, so the card on the Albums grid and the row inside the
     * folder decide the same thing about the same folder. A folder with no name has nothing to hide.
     * A cloud-only photo carries no folder at all, so the caller's list is what places it.
     *
     * Nothing here has to skip what the vault already holds, and a hide resumed after a stop still
     * copies each file once: vaulting a photo deletes its MediaStore row, so an already-vaulted photo
     * is absent from every list a caller can build. The vault index keys on the app-private `file://`
     * uri the copy landed on, which is a different identity from the `content://` uri a device row
     * carries, so it could not answer this question anyway.
     */
    fun folderHideSplit(
        items: List<GalleryItem>,
        bucketName: String,
        cloudLinkIdByUri: Map<String, String> = emptyMap(),
    ): HideSplit {
        if (bucketName.isBlank()) return HideSplit.EMPTY
        val inFolder = items.filter { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.bucketName == bucketName
                is GalleryItem.Synced -> item.local.bucketName == bucketName
                is GalleryItem.CloudOnly -> true
            }
        }
        return hideSplit(inFolder, cloudLinkIdByUri)
    }
}
