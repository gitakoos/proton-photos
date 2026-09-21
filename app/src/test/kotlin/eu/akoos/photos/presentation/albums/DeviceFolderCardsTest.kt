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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the device-folder cards and, above all, the two properties the hide rests on: a folder
 * the vault holds is reachable from the hidden area, so the hide is reversible, and the grid card and
 * the vault card for one folder can never carry the same photo. Runs WITHOUT a device — the grouping
 * and the hidden split are plain list work.
 */
class DeviceFolderCardsTest {

    private fun item(uri: String, bucket: String?, taken: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = taken,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = bucket,
    )

    private val camera1 = item("uri/c1", "Camera", 300)
    private val camera2 = item("uri/c2", "Camera", 200)
    private val camera3 = item("uri/c3", "Camera", 100)
    private val shot1 = item("uri/s1", "Screenshots", 250)

    // ── building the cards ───────────────────────────────────────────────────────────────────────

    @Test
    fun `each bucket becomes one card carrying its count`() {
        val cards = DeviceFolderCards.build(listOf(camera1, camera2, shot1), emptySet(), emptyMap())
        assertEquals(listOf("Camera" to 2, "Screenshots" to 1), cards.map { it.name to it.itemCount })
    }

    @Test
    fun `the newest photo is the cover`() {
        val cards = DeviceFolderCards.build(listOf(camera3, camera1, camera2), emptySet(), emptyMap())
        assertEquals(camera1.uri, cards.single().coverUri)
    }

    @Test
    fun `an item belonging to no bucket is not a folder`() {
        val cards = DeviceFolderCards.build(listOf(camera1, item("uri/loose", null, 400)), emptySet(), emptyMap())
        assertEquals(listOf("Camera"), cards.map { it.name })
    }

    @Test
    fun `a photo in the vault is neither counted nor shown as a cover`() {
        // It cannot be opened from the folder any more, so counting it would promise a photo the
        // folder no longer offers.
        val cards = DeviceFolderCards.build(listOf(camera1, camera2), setOf(camera1.uri), emptyMap())
        assertEquals(1, cards.single().itemCount)
        assertEquals(camera2.uri, cards.single().coverUri)
    }

    @Test
    fun `a pinned cover outranks the newest photo`() {
        val cards = DeviceFolderCards.build(
            listOf(camera1, camera2),
            emptySet(),
            mapOf("Camera" to camera2.uri),
        )
        assertEquals(camera2.uri, cards.single().coverUri)
    }

    @Test
    fun `a pinned cover the folder no longer holds falls back instead of blanking the card`() {
        val cards = DeviceFolderCards.build(
            listOf(camera1, camera2),
            emptySet(),
            mapOf("Camera" to "uri/deleted"),
        )
        assertEquals(camera1.uri, cards.single().coverUri)
    }

    @Test
    fun `the fullest folder leads`() {
        val cards = DeviceFolderCards.build(listOf(shot1, camera1, camera2), emptySet(), emptyMap())
        assertEquals(listOf("Camera", "Screenshots"), cards.map { it.name })
    }

    // ── which folders reach the grid ─────────────────────────────────────────────────────────────

    private val cards = DeviceFolderCards.build(listOf(camera1, camera2, shot1), emptySet(), emptyMap())

    /**
     * The grid's whole answer for one device listing: group it, then keep what still holds a photo.
     *
     * [onDevice] is what MediaStore carries. A vaulted photo is a private file with no row of its
     * own, so a hide takes its photos OUT of this list — which is why no test here hands the same
     * photo to both sides.
     */
    private fun grid(onDevice: List<LocalMediaItem>) =
        DeviceFolderCards.visible(DeviceFolderCards.build(onDevice, emptySet(), emptyMap()))

    /** The vault file a hidden photo becomes, under the private name and capture time [store] gives
     *  it. Never a uri MediaStore can hand out. */
    private fun vaultUriOf(item: LocalMediaItem) =
        "file:///vault/${item.displayName.substringBeforeLast('.')}__${item.dateTaken}.jpg"

    @Test
    fun `a folder whose photos are all on the device has a card`() {
        assertEquals(
            listOf("Camera" to 2, "Screenshots" to 1),
            grid(listOf(camera1, camera2, shot1)).map { it.name to it.itemCount },
        )
    }

    @Test
    fun `a folder whose photos are all in the vault has no card`() {
        // Nothing takes it off by name: its photos left MediaStore when they were vaulted, so the
        // folder never reaches the grouping at all and stays off the grid by construction.
        assertEquals(listOf("Camera"), grid(listOf(camera1, camera2)).map { it.name })
        assertTrue(DeviceFolderCards.visible(emptyList()).isEmpty())
    }

    @Test
    fun `a hidden folder that has gained photos has a card counting only those`() {
        // The defect this closes: a folder keeps its name once hidden, so shots taken since land in a
        // bucket the vault also has records for. They are on the device and openable in any gallery
        // app, so the folder holding them belongs on the grid — counting them alone.
        val hiddenName = "Screenshots"
        val shotSince = listOf(item("uri/s2", hiddenName, 900), item("uri/s3", hiddenName, 800))
        val card = grid(listOf(camera1) + shotSince).single { it.name == hiddenName }
        assertEquals(2, card.itemCount)
        assertEquals("uri/s2", card.coverUri)
        // The vault keeps its own card for the same folder, holding the photo that IS put away.
        val vaultCard = DeviceFolderCards
            .hidden(setOf(hiddenName), mapOf(hiddenName to listOf(vaultUriOf(shot1))))
            .single()
        assertEquals(1, vaultCard.itemCount)
        assertEquals(vaultUriOf(shot1), vaultCard.coverUri)
    }

    @Test
    fun `a folder emptying into the vault keeps its card until the last photo goes`() {
        // The in-flight window of a hide: the folder's name is stored before the first copy, and each
        // original leaves MediaStore as its copy is committed. The card shrinks with the folder and
        // goes only once the device has nothing left, so a hide the user stops leaves the rest of the
        // folder reachable — and hideable again.
        val all = listOf(camera1, camera2, camera3)
        val counts = all.indices.map { done ->
            grid(all.drop(done)).singleOrNull { it.name == "Camera" }?.itemCount ?: 0
        }
        assertEquals(listOf(3, 2, 1), counts)
        assertTrue(grid(emptyList()).isEmpty())
    }

    @Test
    fun `a photo MediaStore has not dropped yet is still off the card`() {
        // The other end of that window: the vault copy is published the moment the delete confirms,
        // while a listing taken before the scan catches up can still carry the original. The grid
        // drops every uri the vault index names, so the count never runs ahead of the truth.
        val stale = listOf(camera1, camera2)
        val cards = DeviceFolderCards.build(stale, setOf(camera1.uri), emptyMap())
        assertEquals(1, DeviceFolderCards.visible(cards).single().itemCount)
    }

    @Test
    fun `a stored name matching no folder takes no live card down with it`() {
        // Renaming or deleting a folder on the device leaves its name behind in the set; it must not
        // take an unrelated card off the grid.
        assertEquals(cards, DeviceFolderCards.visible(cards))
        assertEquals(listOf("Trip 2026"), DeviceFolderCards.hidden(setOf("Trip 2026")).map { it.name })
    }

    @Test
    fun `the two cards for one folder can never hold the same photo`() {
        // A photo is a MediaStore row or a vault file, never both, and each side reads only its own:
        // the grid groups the device listing, the vault list is driven off the published index. Run
        // over every split of one folder's photos.
        val name = "Camera"
        val all = listOf(camera1, camera2, camera3)
        for (vaultedCount in 0..all.size) {
            val put = all.take(vaultedCount)
            val vaultedUris = put.map(::vaultUriOf).toSet()
            val records = put.map { "${vaultUriOf(it)}|DCIM/$name" }.toSet()
            val onGrid = DeviceFolderCards.visible(
                DeviceFolderCards.build(all.drop(vaultedCount), vaultedUris, emptyMap()),
            )
            val inVault = DeviceFolderCards.hidden(
                hiddenNames = setOf(name),
                vaultedByFolder = HiddenFolderRecords.vaultedByBucket(records, vaultedUris),
            ).single()
            // Every photo is on exactly one side, and neither cover is ever the other side's photo.
            assertEquals(all.size, onGrid.sumOf { it.itemCount } + inVault.itemCount)
            assertTrue(onGrid.mapNotNull { it.coverUri }.none { it in vaultedUris })
            assertTrue(inVault.coverUri.let { it == null || it in vaultedUris })
        }
    }

    // ── the vault side of the hidden list ────────────────────────────────────────────────────────

    @Test
    fun `a folder whose photos are all in the vault still has a card`() {
        // The trap this closes: with every photo vaulted no MediaStore row carries the bucket name,
        // so a list built from live folders alone would lose the folder and the last way to reveal it.
        val hidden = DeviceFolderCards.hidden(
            hiddenNames = setOf("Camera"),
            vaultedByFolder = mapOf("Camera" to listOf("file:///vault/a__300.jpg", "file:///vault/b__200.jpg")),
        )
        assertEquals(listOf("Camera"), hidden.map { it.name })
        assertEquals(2, hidden.single().itemCount)
        assertEquals("file:///vault/a__300.jpg", hidden.single().coverUri)
    }

    @Test
    fun `the card counts what the vault holds, not what the folder still shows`() {
        // A photo the folder still lists after the hide is one that was never hidden — a shot taken
        // since. Counting it would promise the vault holds a photo it does not.
        val hidden = DeviceFolderCards.hidden(
            hiddenNames = setOf("Camera"),
            vaultedByFolder = mapOf("Camera" to listOf("file:///vault/a__50.jpg")),
        )
        assertEquals(1, hidden.single().itemCount)
        assertEquals("file:///vault/a__50.jpg", hidden.single().coverUri)
    }

    @Test
    fun `a photo the device and the vault both hold is counted once`() {
        // The window a hide leaves open: the vault copy is published the moment the delete confirms,
        // while the device goes on listing the original until its own scan catches up. The vault
        // index carries the photo once, so counting from it alone is already right.
        val duringHide = DeviceFolderCards.hidden(
            hiddenNames = setOf("Camera"),
            vaultedByFolder = mapOf("Camera" to listOf("file:///vault/c1__300.jpg", "file:///vault/c2__200.jpg")),
        )
        assertEquals(2, duringHide.single().itemCount)
    }

    @Test
    fun `a name with nothing behind it is still revealable`() {
        val hidden = DeviceFolderCards.hidden(setOf("Trip 2026"), emptyMap())
        assertEquals(listOf("Trip 2026"), hidden.map { it.name })
        assertEquals(0, hidden.single().itemCount)
        assertEquals(null, hidden.single().coverUri)
    }

    @Test
    fun `the fullest hidden folder leads and equal ones keep a fixed order`() {
        val hidden = DeviceFolderCards.hidden(
            hiddenNames = setOf("Camera", "Screenshots", "Trip 2026"),
            vaultedByFolder = mapOf(
                "Trip 2026" to listOf("file:///vault/t__10.jpg", "file:///vault/u__20.jpg"),
                "Camera" to listOf("file:///vault/c__30.jpg"),
            ),
        )
        assertEquals(listOf("Trip 2026", "Camera", "Screenshots"), hidden.map { it.name })
    }

    @Test
    fun `the grid keeps its order after a folder is hidden`() {
        val video = item("uri/v1", "Videos", 500)
        val all = listOf(camera1, camera2, camera3, shot1, video)
        assertEquals(listOf("Camera", "Screenshots", "Videos"), grid(all).map { it.name })
        // Screenshots is hidden, so its one photo is now a vault file rather than a MediaStore row.
        assertEquals(listOf("Camera", "Videos"), grid(all - shot1).map { it.name })
    }
}
