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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a photo carries into the vault and what a reveal puts back.
 *
 * A hide changes the photo's uri twice, so every store keyed by that uri stops describing it unless the
 * hide copies the answer forward — and none of these answers can be worked out again from the bytes.
 * What the round trip has to be is simple to state and easy to lose: the photo that comes out is the
 * photo that went in, down to its heart, its categories, the folder cover it was pinned as and the
 * albums it was queued to join.
 */
class HiddenVaultCarryTest {

    private val vaultUri = "file:///data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"
    private val otherVaultUri = "file:///data/user/0/eu.akoos.photos/files/hidden/c1d2__1783507135000.png"
    private val sourceUri = "content://media/external/images/media/12345"
    private val restoredUri = "content://media/external/images/media/99999"

    private fun everything() = CarriedPhoto(
        sourceUri = sourceUri,
        favorite = true,
        userTagsCsv = "3,7",
        coverOfFolder = "Camera",
        albumLinkIds = listOf("album-a", "album-b"),
    )

    // ── a photo that carries nothing ─────────────────────────────────────────────────────────────

    @Test
    fun `a photo with none of these set is worth no record at all`() {
        // The ordinary photo. Recording an entry for it would put one row per hidden photo in a
        // preference that only ever needs the few carrying something.
        val plain = CarriedPhoto(sourceUri = sourceUri)

        assertFalse(plain.isWorthCarrying)
    }

    @Test
    fun `any one of them on its own is worth a record`() {
        val base = CarriedPhoto(sourceUri = sourceUri)

        assertTrue(base.copy(favorite = true).isWorthCarrying)
        assertTrue(base.copy(userTagsCsv = "3").isWorthCarrying)
        assertTrue(base.copy(coverOfFolder = "Camera").isWorthCarrying)
        assertTrue(base.copy(albumLinkIds = listOf("album-a")).isWorthCarrying)
    }

    @Test
    fun `a photo carrying nothing still round-trips through the record unchanged`() {
        val plain = CarriedPhoto(sourceUri = sourceUri)

        assertEquals(vaultUri to plain, HiddenVaultCarry.decode(HiddenVaultCarry.encode(vaultUri, plain)))
    }

    // ── a photo that carries all of them ─────────────────────────────────────────────────────────

    @Test
    fun `a photo with all of them set round-trips through the record whole`() {
        val decoded = HiddenVaultCarry.decode(HiddenVaultCarry.encode(vaultUri, everything()))

        assertEquals(vaultUri to everything(), decoded)
    }

    @Test
    fun `the record is found by the vault uri it belongs to and by no other`() {
        val entries = setOf(
            HiddenVaultCarry.encode(vaultUri, everything()),
            HiddenVaultCarry.encode(otherVaultUri, CarriedPhoto(sourceUri = "content://other", favorite = true)),
        )

        assertEquals(everything(), HiddenVaultCarry.carriedBy(entries, vaultUri))
        assertNull(HiddenVaultCarry.carriedBy(entries, "$vaultUri.jpg"))
        assertNull(HiddenVaultCarry.carriedBy(emptySet(), vaultUri))
        assertNull(HiddenVaultCarry.carriedBy(null, vaultUri))
    }

    @Test
    fun `a record written by nothing this app runs is read as no record`() {
        assertNull(HiddenVaultCarry.decode(vaultUri))
        assertNull(HiddenVaultCarry.decode("$vaultUri|$sourceUri|1"))
        assertNull(HiddenVaultCarry.carriedBy(setOf("$vaultUri|nonsense"), vaultUri))
    }

    // ── a value holding the separator the records are built on ───────────────────────────────────

    @Test
    fun `a folder name holding the separator survives the round trip`() {
        // A folder named this way is exactly what would otherwise split the record into the wrong
        // fields and pin a cover onto a folder that does not exist.
        val piped = everything().copy(coverOfFolder = "Trip|2026", sourceUri = "content://media/a|b")

        assertEquals(piped, HiddenVaultCarry.decode(HiddenVaultCarry.encode(vaultUri, piped))?.second)
    }

    @Test
    fun `a value holding the list separator or the escape character survives too`() {
        val awkward = everything().copy(
            coverOfFolder = "Trip, 2026 (100%)",
            albumLinkIds = listOf("album|a", "album,b", "album%c"),
        )

        assertEquals(awkward, HiddenVaultCarry.decode(HiddenVaultCarry.encode(vaultUri, awkward))?.second)
    }

    @Test
    fun `an escaped value cannot be mistaken for structure`() {
        // The encoded entry has to hold exactly the six fields one record has, however many separators
        // the values themselves contain.
        val piped = everything().copy(coverOfFolder = "Trip|2026")

        assertEquals(6, HiddenVaultCarry.encode(vaultUri, piped).split('|').size)
    }

    // ── the heart ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a favourite comes back on the uri the photo returned as`() {
        val favorites = setOf(sourceUri, "content://media/external/images/media/777")

        val after = HiddenVaultCarry.favoriteIdsAfterRestore(favorites, everything(), vaultUri, restoredUri)

        assertEquals(setOf(restoredUri, "content://media/external/images/media/777"), after)
    }

    @Test
    fun `a photo that was no favourite gains no heart, and the dead uri leaves either way`() {
        // The set has no prune of its own, so an entry for a uri that names nothing would outlive the
        // photo and every hide of it.
        val notFavorite = everything().copy(favorite = false)

        assertEquals(
            emptySet<String>(),
            HiddenVaultCarry.favoriteIdsAfterRestore(setOf(sourceUri), notFavorite, vaultUri, restoredUri),
        )
    }

    @Test
    fun `a heart given while the photo sat in the vault follows it back out`() {
        // The vault screen hearts the photo under the vault uri, since that is the only uri it has
        // while it is in there. A reveal that dropped it would lose a choice the user made.
        val notFavorite = everything().copy(favorite = false)

        val after = HiddenVaultCarry.favoriteIdsAfterRestore(setOf(vaultUri), notFavorite, vaultUri, restoredUri)

        assertEquals(setOf(restoredUri), after)
    }

    @Test
    fun `a photo the hide recorded nothing for still brings its vault heart out`() {
        val after = HiddenVaultCarry.favoriteIdsAfterRestore(setOf(vaultUri), null, vaultUri, restoredUri)

        assertEquals(setOf(restoredUri), after)
    }

    @Test
    fun `a photo the hide recorded nothing for and that was never hearted gains no heart`() {
        val others = setOf("content://media/external/images/media/777")

        assertEquals(others, HiddenVaultCarry.favoriteIdsAfterRestore(others, null, vaultUri, restoredUri))
    }

    @Test
    fun `the vault uri never survives the reveal`() {
        // It names a file the reveal has just deleted, and nothing prunes the set, so an entry left on
        // it would name that missing file for the life of the install.
        val favorites = setOf(vaultUri, sourceUri, otherVaultUri)

        val after = HiddenVaultCarry.favoriteIdsAfterRestore(favorites, everything(), vaultUri, restoredUri)

        assertEquals(setOf(restoredUri, otherVaultUri), after)
    }

    // ── the pinned folder cover ──────────────────────────────────────────────────────────────────

    @Test
    fun `a folder whose cover the media scan already pruned gets it back`() {
        val after = HiddenVaultCarry.coversAfterRestore(emptySet(), everything(), restoredUri)

        assertEquals(setOf(FolderCoverMap.encode("Camera", restoredUri)), after)
    }

    @Test
    fun `a folder still pinned to the photo that left is moved onto the one that came back`() {
        val covers = setOf(FolderCoverMap.encode("Camera", sourceUri), FolderCoverMap.encode("Trips", "content://x"))

        val after = HiddenVaultCarry.coversAfterRestore(covers, everything(), restoredUri)

        assertEquals(
            setOf(FolderCoverMap.encode("Camera", restoredUri), FolderCoverMap.encode("Trips", "content://x")),
            after,
        )
    }

    @Test
    fun `a folder the user has since pinned to another photo keeps that newer choice`() {
        val covers = setOf(FolderCoverMap.encode("Camera", "content://media/external/images/media/555"))

        assertNull(HiddenVaultCarry.coversAfterRestore(covers, everything(), restoredUri))
    }

    @Test
    fun `a photo that was nobody's cover changes no folder`() {
        val notACover = everything().copy(coverOfFolder = "")

        assertNull(HiddenVaultCarry.coversAfterRestore(emptySet(), notACover, restoredUri))
    }

    @Test
    fun `a folder named with the separator is pinned as itself`() {
        val piped = everything().copy(coverOfFolder = "Trip|2026")

        val after = HiddenVaultCarry.coversAfterRestore(emptySet(), piped, restoredUri)

        assertEquals(mapOf("Trip|2026" to restoredUri), FolderCoverMap.parse(after))
    }

    // ── a photo destroyed inside the vault ───────────────────────────────────────────────────────

    @Test
    fun `a record does not survive the photo being deleted from the vault`() {
        // Deleting a vaulted photo drops its bytes and every record naming it. A carried record that
        // outlived that would put the heart and the categories of a photo the user destroyed onto
        // whatever uri came along next.
        val entries = setOf(
            HiddenVaultCarry.encode(vaultUri, everything()),
            HiddenVaultCarry.encode(otherVaultUri, CarriedPhoto(sourceUri = "content://other", favorite = true)),
        )

        val after = HiddenVaultRecords.dropped(entries, setOf(vaultUri))

        assertNull(HiddenVaultCarry.carriedBy(after, vaultUri))
        assertEquals(1, after.size)
    }

    @Test
    fun `a delete takes only the records of the photo it names`() {
        val prefix = "$vaultUri.jpg"
        val entries = setOf(
            HiddenVaultCarry.encode(vaultUri, everything()),
            HiddenVaultCarry.encode(prefix, everything()),
        )

        val after = HiddenVaultRecords.dropped(entries, setOf(vaultUri))

        assertNull(HiddenVaultCarry.carriedBy(after, vaultUri))
        assertEquals(everything(), HiddenVaultCarry.carriedBy(after, prefix))
    }

    @Test
    fun `a rename inside the vault carries the record onto the file's new uri`() {
        // The vault uri IS the key, and a rename moves the file. A record left behind would be a photo
        // that loses its heart and its categories for having been given a name.
        val renamed = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1783507135000.jpg"
        val records = VaultRecords(
            index = setOf(vaultUri),
            names = emptySet(),
            folders = emptySet(),
            cloudIds = emptySet(),
            carried = setOf(HiddenVaultCarry.encode(vaultUri, everything())),
        )

        val after = HiddenVaultRecords.renamed(records, vaultUri, renamed, "Beach trip.jpg")

        assertEquals(everything(), HiddenVaultCarry.carriedBy(after.carried, renamed))
        assertNull(HiddenVaultCarry.carriedBy(after.carried, vaultUri))
    }
}
