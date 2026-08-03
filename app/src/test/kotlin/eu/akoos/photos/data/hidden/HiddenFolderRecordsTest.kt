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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.common.PhotoSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decisions a folder hide rests on: how a folder's photos split between the vault and the
 * client-side filter, and what the vault can still say about a folder once they are in it. Both run
 * WITHOUT a device — the records are plain strings and the routing is a filter over the gallery's
 * own types.
 */
class HiddenFolderRecordsTest {

    private fun local(uri: String, bucket: String?, dateTaken: Long = 100) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = bucket,
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    // ── which source folder a record belongs to ──────────────────────────────────────────────────

    @Test
    fun `a record holding a full path resolves to its bucket name`() {
        assertEquals("Camera", HiddenFolderRecords.bucketOf("DCIM/Camera"))
        assertEquals("WhatsApp Images", HiddenFolderRecords.bucketOf("Android/media/com.whatsapp/Media/WhatsApp Images"))
    }

    @Test
    fun `a bare bucket name and a trailing slash resolve the same`() {
        assertEquals("Camera", HiddenFolderRecords.bucketOf("Camera"))
        assertEquals("Camera", HiddenFolderRecords.bucketOf("/DCIM/Camera/"))
        assertEquals("DCIM", HiddenFolderRecords.bucketOf("DCIM/"))
    }

    // ── the vault's per-folder contents ──────────────────────────────────────────────────────────

    @Test
    fun `each vaulted photo counts towards the folder it came from`() {
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf(
                "file:///v/a__300.jpg|DCIM/Camera",
                "file:///v/b__200.jpg|DCIM/Camera",
                "file:///v/c__100.jpg|Pictures/Screenshots",
            ),
            vaultedUris = setOf("file:///v/a__300.jpg", "file:///v/b__200.jpg", "file:///v/c__100.jpg"),
        )
        assertEquals(setOf("Camera", "Screenshots"), byBucket.keys)
        assertEquals(2, byBucket.getValue("Camera").size)
    }

    @Test
    fun `the newest vaulted photo leads so it can be the cover`() {
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf(
                "file:///v/old__100.jpg|DCIM/Camera",
                "file:///v/new__900.jpg|DCIM/Camera",
                "file:///v/undated.jpg|DCIM/Camera",
            ),
            vaultedUris = setOf("file:///v/old__100.jpg", "file:///v/new__900.jpg", "file:///v/undated.jpg"),
        )
        // A name recording no capture time cannot claim to be the newest, so it sorts last.
        assertEquals(
            listOf("file:///v/new__900.jpg", "file:///v/old__100.jpg", "file:///v/undated.jpg"),
            byBucket.getValue("Camera"),
        )
    }

    @Test
    fun `a folder name containing the separator survives the round trip`() {
        // The key is read up to the FIRST separator, so everything after it is the folder — a vault
        // uri never contains one, and a folder name may.
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf("file:///v/a__1.jpg|Pictures/Trip | 2026"),
            vaultedUris = setOf("file:///v/a__1.jpg"),
        )
        assertEquals(listOf("Trip | 2026"), byBucket.keys.toList())
    }

    @Test
    fun `a photo whose source folder was never recorded belongs to no folder`() {
        // Older vault entries, and any hide whose RELATIVE_PATH could not be read, record nothing.
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf(
                "file:///v/a__1.jpg",
                "file:///v/b__1.jpg|",
                "file:///v/c__1.jpg|   ",
                "file:///v/d__1.jpg|DCIM/Camera",
            ),
            vaultedUris = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg", "file:///v/c__1.jpg", "file:///v/d__1.jpg"),
        )
        assertEquals(mapOf("Camera" to listOf("file:///v/d__1.jpg")), byBucket)
    }

    @Test
    fun `a hide that has not confirmed yet is invisible to the folder list`() {
        // The journal writes the source folder BEFORE the original is deleted. Counting it then would
        // promise the vault a photo the user can still see in the folder.
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf(
                "file:///v/done__1.jpg|DCIM/Camera",
                "file:///v/inflight__1.jpg|DCIM/Camera",
            ),
            vaultedUris = setOf("file:///v/done__1.jpg"),
        )
        assertEquals(listOf("file:///v/done__1.jpg"), byBucket.getValue("Camera"))
    }

    @Test
    fun `a blank key names no vault file`() {
        val byBucket = HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = setOf("|DCIM/Camera"),
            vaultedUris = setOf("", "file:///v/a__1.jpg"),
        )
        assertTrue(byBucket.isEmpty())
    }

    // ── which vaulted photos the flat hidden list shows ──────────────────────────────────────────

    @Test
    fun `a photo vaulted with its whole folder is not listed loose as well`() {
        // It is reached inside that folder's card, exactly as it was inside the folder on the grid.
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg", "file:///v/c__1.jpg"),
            sourceFolderEntries = setOf(
                "file:///v/a__1.jpg|DCIM/Camera",
                "file:///v/b__1.jpg|DCIM/Camera",
                "file:///v/c__1.jpg|DCIM/Camera",
            ),
            hiddenFolderNames = setOf("Camera"),
        )
        assertTrue(loose.isEmpty())
    }

    @Test
    fun `a photo whose source folder is recorded but not hidden stays in the flat list`() {
        // Hidden on its own from inside a folder the user never hid — the flat list is the only place
        // it can be reached from.
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg"),
            sourceFolderEntries = setOf(
                "file:///v/a__1.jpg|DCIM/Camera",
                "file:///v/b__1.jpg|Pictures/Screenshots",
            ),
            hiddenFolderNames = setOf("Camera"),
        )
        assertEquals(setOf("file:///v/b__1.jpg"), loose)
    }

    @Test
    fun `a photo with no source folder recorded stays in the flat list`() {
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg"),
            sourceFolderEntries = setOf("file:///v/b__1.jpg|DCIM/Camera"),
            hiddenFolderNames = setOf("Camera"),
        )
        assertEquals(setOf("file:///v/a__1.jpg"), loose)
    }

    @Test
    fun `a blank source folder cannot put a photo inside a folder`() {
        // A record with nothing after the separator names no folder, so the photo is loose whatever
        // is hidden.
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg"),
            sourceFolderEntries = setOf("file:///v/a__1.jpg|", "file:///v/b__1.jpg|   "),
            hiddenFolderNames = setOf("Camera", ""),
        )
        assertEquals(setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg"), loose)
    }

    @Test
    fun `with no folder hidden every vaulted photo is loose`() {
        val vaulted = setOf("file:///v/a__1.jpg", "file:///v/b__1.jpg")
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = vaulted,
            sourceFolderEntries = setOf("file:///v/a__1.jpg|DCIM/Camera"),
            hiddenFolderNames = emptySet(),
        )
        assertEquals(vaulted, loose)
    }

    @Test
    fun `a full path is matched by its bucket name, the way every per-folder preference keys`() {
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/a__1.jpg"),
            sourceFolderEntries = setOf("file:///v/a__1.jpg|Android/media/com.whatsapp/Media/WhatsApp Images"),
            hiddenFolderNames = setOf("WhatsApp Images"),
        )
        assertTrue(loose.isEmpty())
    }

    @Test
    fun `a record for a photo the vault no longer holds cannot hide anything`() {
        // Restoring prunes the index before the records, so a stale record must not silently drop a
        // photo that is still vaulted.
        val loose = HiddenFolderRecords.looseVaultedUris(
            vaultedUris = setOf("file:///v/b__1.jpg"),
            sourceFolderEntries = setOf("file:///v/a__1.jpg|DCIM/Camera"),
            hiddenFolderNames = setOf("Camera"),
        )
        assertEquals(setOf("file:///v/b__1.jpg"), loose)
    }

    // ── which photos a folder hide moves, and which it only filters ──────────────────────────────

    private val deviceOnly = GalleryItem.LocalOnly(local("content://media/1", "Camera"))
    private val elsewhere = GalleryItem.LocalOnly(local("content://media/2", "Screenshots"))
    private val synced = GalleryItem.Synced(cloud("link-1"), local("content://media/3", "Camera"))
    private val cloudOnly = GalleryItem.CloudOnly(cloud("link-2"))

    /** The photos a split would move, as their surfaces held them. */
    private fun vaultedItems(split: HiddenFolderRecords.HideSplit) = split.vaultable.map { it.item }

    /** The Drive copy each vaulted photo carries into the vault, in order. */
    private fun vaultedLinkIds(split: HiddenFolderRecords.HideSplit) = split.vaultable.map { it.cloudLinkId }

    @Test
    fun `every photo of this folder with a device file is vaulted`() {
        // Hiding has to take the file off the device, so a backed-up photo is vaulted exactly as a
        // device-only one is; only a cloud-only photo, which has no file at all, hides by filter.
        val split = HiddenFolderRecords.folderHideSplit(
            listOf(deviceOnly, elsewhere, synced, cloudOnly), "Camera",
        )
        assertEquals(listOf(deviceOnly, synced), vaultedItems(split))
        assertEquals(listOf(null, "link-1"), vaultedLinkIds(split))
        assertEquals(listOf("link-2"), split.cloudLinkIds)
    }

    @Test
    fun `a folder of device-only photos is all vault and nothing to filter`() {
        val second = GalleryItem.LocalOnly(local("content://media/9", "Camera"))
        val split = HiddenFolderRecords.folderHideSplit(
            listOf(deviceOnly, second, elsewhere), "Camera",
        )
        assertEquals(listOf(deviceOnly, second), vaultedItems(split))
        assertTrue(split.cloudLinkIds.isEmpty())
    }

    @Test
    fun `a fully backed-up folder is all vault, each photo carrying its Drive copy`() {
        // The linkId is what must not be dropped: it is the only thing that lets the reveal re-pair
        // the restored file instead of uploading a second copy of a photo already on Drive.
        val second = GalleryItem.Synced(cloud("link-9"), local("content://media/8", "Camera"))
        val split = HiddenFolderRecords.folderHideSplit(
            listOf(synced, second), "Camera",
        )
        assertEquals(listOf(synced, second), vaultedItems(split))
        assertEquals(listOf("link-1", "link-9"), vaultedLinkIds(split))
        assertTrue(split.cloudLinkIds.isEmpty())
        assertTrue(!split.isEmpty)
    }

    @Test
    fun `an empty folder hides nothing at all`() {
        val split = HiddenFolderRecords.folderHideSplit(emptyList(), "Camera")
        assertTrue(split.isEmpty)
    }

    @Test
    fun `a device row that is backed up is vaulted carrying its pairing`() {
        // How the Albums card reads a folder: MediaStore rows carry no cloud identity, so the sync
        // pairing is the only thing that can name the Drive copy the reveal has to re-pair to.
        val split = HiddenFolderRecords.folderHideSplit(
            items = listOf(deviceOnly, elsewhere),
            bucketName = "Camera",
            cloudLinkIdByUri = mapOf(deviceOnly.local.uri to "link-7"),
        )
        assertEquals(listOf(deviceOnly), vaultedItems(split))
        assertEquals(listOf("link-7"), vaultedLinkIds(split))
        assertTrue(split.cloudLinkIds.isEmpty())
    }

    @Test
    fun `a pairing for a photo of another folder cannot reach this hide`() {
        val split = HiddenFolderRecords.folderHideSplit(
            items = listOf(deviceOnly, elsewhere),
            bucketName = "Camera",
            cloudLinkIdByUri = mapOf(elsewhere.local.uri to "link-7"),
        )
        assertEquals(listOf(deviceOnly), vaultedItems(split))
        assertEquals(listOf(null), vaultedLinkIds(split))
        assertTrue(split.cloudLinkIds.isEmpty())
    }

    @Test
    fun `a folder with no name has nothing to hide`() {
        val split = HiddenFolderRecords.folderHideSplit(
            listOf(deviceOnly, synced, cloudOnly), "",
        )
        assertTrue(split.isEmpty)
    }

    @Test
    fun `a selection is routed the same way, with no folder to scope it`() {
        // The selection surfaces share the routing but not the folder filter, so a selection spanning
        // two folders hides exactly what was selected.
        val split = HiddenFolderRecords.hideSplit(listOf(deviceOnly, elsewhere, synced, cloudOnly))
        assertEquals(listOf(deviceOnly, elsewhere, synced), vaultedItems(split))
        assertEquals(listOf("link-2"), split.cloudLinkIds)
    }

    @Test
    fun `a vaulted photo states the size and date of the photo, not of whichever half was fuller`() {
        // An album member reached without its MediaStore row states no size and no date of its own,
        // and the free-space check and the vault file's name both have to answer for the photo.
        val standIn = GalleryItem.Synced(
            cloud("link-3"),
            LocalMediaItem(
                uri = "content://media/7",
                dateTaken = 0L,
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 0L,
                bucketName = null,
            ),
        )
        val target = HiddenFolderRecords.hideSplit(listOf(standIn)).vaultable.single()
        assertEquals(1024L, target.sizeBytes)
        assertEquals(standIn.captureTimeMs, target.captureTimeMs)
    }

    // ── what a folder screen lists, and which side of the hidden line it reads ───────────────────

    /** A photo on the device, in the folder being shown. */
    private fun liveOf(id: String, timeMs: Long) =
        GalleryItem.LocalOnly(local("content://media/$id", "Camera", timeMs))

    /** The same photo once it is in the vault: a private file under a generated name, with the
     *  capture time the hide recorded in it. */
    private fun vaultOf(id: String, timeMs: Long) =
        GalleryItem.LocalOnly(local("file:///v/${id}__$timeMs.jpg", "Camera", timeMs))

    /** Every uri in [items], for asserting that a photo is nowhere in a list. */
    private fun urisOf(items: List<GalleryItem>) = items.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }

    @Test
    fun `the device side shows what the device lists`() {
        val live = listOf(liveOf("1", 300), liveOf("2", 200))
        assertEquals(live, HiddenFolderRecords.folderPhotos(live, emptyList(), showsVault = false))
    }

    @Test
    fun `the device side never shows a photo hidden from inside the folder`() {
        // Hidden on its own from a folder the user never hid. It is reached in the hidden area; this
        // screen is behind no lock, so listing it here would undo the hide.
        val live = listOf(liveOf("1", 300))
        val vaulted = listOf(vaultOf("2", 200))
        assertEquals(live, HiddenFolderRecords.folderPhotos(live, vaulted, showsVault = false))
    }

    @Test
    fun `the vault side shows only what the vault holds`() {
        val vaulted = listOf(vaultOf("1", 300), vaultOf("2", 200))
        assertEquals(vaulted, HiddenFolderRecords.folderPhotos(emptyList(), vaulted, showsVault = true))
    }

    @Test
    fun `a photo taken after the hide never reaches the hidden area`() {
        // The whole defect: a folder keeps its name once hidden, so shots taken since land in a
        // bucket the vault also has records for. They are on the device and openable in any gallery
        // app, and presenting them as put away would be a promise the vault cannot keep.
        val shotSince = listOf(liveOf("new1", 900), liveOf("new2", 800), liveOf("new3", 700))
        val vaulted = listOf(vaultOf("1", 300), vaultOf("2", 200), vaultOf("3", 100))
        val listed = HiddenFolderRecords.folderPhotos(shotSince, vaulted, showsVault = true)
        assertEquals(vaulted, listed)
        assertTrue(urisOf(shotSince).none { it in urisOf(listed) })
    }

    @Test
    fun `a hide still filling the vault shows what has landed and nothing more`() {
        // The in-flight window: the folder's name is recorded before the first copy, so it is hidden
        // while its photos are still being vaulted one by one. The two that are still on the device
        // are exactly the two that are not protected yet.
        val stillOnDevice = listOf(liveOf("2", 200), liveOf("3", 100))
        val landed = listOf(vaultOf("1", 300))
        val listed = HiddenFolderRecords.folderPhotos(stillOnDevice, landed, showsVault = true)
        assertEquals(landed, listed)
        assertTrue(urisOf(stillOnDevice).none { it in urisOf(listed) })
    }

    @Test
    fun `a hide that has published nothing yet shows an empty folder`() {
        // The first moment of a folder hide, and a hide the user stopped before anything confirmed.
        val listed = HiddenFolderRecords.folderPhotos(
            live = listOf(liveOf("1", 300), liveOf("2", 200)),
            vaulted = emptyList(),
            showsVault = true,
        )
        assertTrue(listed.isEmpty())
    }

    @Test
    fun `a hide the user stopped part-way shows the part that landed`() {
        // What a stopped hide leaves: the copies that confirmed are in the vault, the rest are back
        // where the user can still see them on the device — and only the first group is protected.
        val untouched = listOf(liveOf("3", 100))
        val landed = listOf(vaultOf("1", 300), vaultOf("2", 200))
        assertEquals(landed, HiddenFolderRecords.folderPhotos(untouched, landed, showsVault = true))
    }

    @Test
    fun `nothing outside the vault can reach a hidden folder, whatever the device lists`() {
        // The invariant the hidden area rests on, over every shape the two sides can take.
        val live = listOf(liveOf("1", 300), liveOf("2", 200))
        val vaulted = listOf(vaultOf("3", 150), vaultOf("4", 50))
        for (liveSide in listOf(emptyList(), live.take(1), live)) {
            for (vaultSide in listOf(emptyList(), vaulted.take(1), vaulted)) {
                val listed = HiddenFolderRecords.folderPhotos(liveSide, vaultSide, showsVault = true)
                assertEquals(vaultSide, listed)
                assertTrue(listed.all { it in vaultSide })
            }
        }
    }

    @Test
    fun `the arrangement is the caller's sort alone`() {
        // The chosen side comes through in the order given, so two emissions of the same folder
        // cannot come out differently and the grid does not jump between them.
        val a = vaultOf("1", 100)
        val b = vaultOf("2", 300)
        val c = vaultOf("3", 200)
        val listed = HiddenFolderRecords.folderPhotos(emptyList(), listOf(a, b, c), showsVault = true)
        assertEquals(listOf(a, b, c), listed)
        assertEquals(listOf(b, c, a), PhotoSortOrder.ordered(listed, newestFirst = true))
    }

    @Test
    fun `a folder's two sides never appear together`() {
        // A photo is on the device or in the vault, and the screen reads one side or the other, so
        // no arrangement of the inputs can put a photo on the grid twice.
        val live = listOf(liveOf("1", 300))
        val vaulted = listOf(vaultOf("1", 300))
        for (showsVault in listOf(true, false)) {
            val listed = HiddenFolderRecords.folderPhotos(live, vaulted, showsVault = showsVault)
            assertEquals(1, listed.size)
            assertEquals(if (showsVault) vaulted else live, listed)
        }
    }

}
