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

package eu.akoos.photos.presentation.metadata

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.CaptureDateOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A date edit on a HEIC, GIF or RAW photo reaches only the MediaStore column, which the next media scan
 * re-derives from the file. Such an edit is therefore also recorded as a capture-date override, created
 * even when the file had none, while a file that carries the date itself only moves an entry it already has.
 */
class ColumnOnlyDateOverrideTest {

    private val newDate = 1_600_000_000_000L

    private fun local(uri: String, mime: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "photo",
        mimeType = mime,
        sizeBytes = 1_024L,
        bucketName = "Camera",
    )

    private fun localOnly(uri: String, mime: String) = GalleryItem.LocalOnly(local(uri, mime))

    private fun synced(uri: String, mime: String) = GalleryItem.Synced(
        CloudPhoto(
            linkId = "link-$uri",
            shareId = "share1",
            volumeId = "vol1",
            captureTime = 1L,
            displayName = "photo",
            mimeType = mime,
            sizeBytes = 1_024L,
            thumbnailUrl = null,
            revisionId = "rev1",
        ),
        local(uri, mime),
    )

    @Test
    fun `heic gif and raw edits are recorded, jpeg and mp4 are not`() {
        val items = listOf(
            localOnly("heic", "image/heic"),
            localOnly("gif", "image/gif"),
            localOnly("raw", "image/x-adobe-dng"),
            localOnly("jpeg", "image/jpeg"),
            localOnly("mp4", "video/mp4"),
        )
        val edits = items.associate { (it as GalleryItem.LocalOnly).local.uri to newDate }

        val columnOnly = columnOnlyDateEdits(items, edits) { false }

        assertEquals(setOf("heic", "gif", "raw"), columnOnly.keys)
        assertTrue(columnOnly.values.all { it == newDate })
    }

    @Test
    fun `a vaulted photo keeps its date in its name and is not recorded`() {
        val items = listOf(localOnly("vaulted", "image/heic"), localOnly("plain", "image/heic"))
        val edits = mapOf("vaulted" to newDate, "plain" to newDate)

        assertEquals(setOf("plain"), columnOnlyDateEdits(items, edits) { it == "vaulted" }.keys)
    }

    @Test
    fun `only device-only photos the edit reached are recorded`() {
        val items = listOf(synced("backed", "image/heic"), localOnly("untouched", "image/heic"))

        assertTrue(columnOnlyDateEdits(items, mapOf("backed" to newDate)) { false }.isEmpty())
    }

    @Test
    fun `a heic without an entry gains one carrying its modified time`() {
        val after = dateOverridesAfterEdit(
            current = emptySet(),
            captureMsByUri = mapOf("heic" to newDate),
            columnOnly = mapOf("heic" to newDate),
            modifiedByUri = mapOf("heic" to 42L),
        )

        assertEquals(setOf(CaptureDateOverride.encode("heic", newDate, 42L)), after)
        // The modified time is what keeps the fresh entry current through the next scan.
        val entry = CaptureDateOverride.parse(after).getValue("heic")
        assertTrue(CaptureDateOverride.isCurrent(entry, 42L))
    }

    @Test
    fun `a jpeg without an entry gets none, its file carries the date`() {
        val after = dateOverridesAfterEdit(
            current = emptySet(),
            captureMsByUri = mapOf("jpeg" to newDate),
            columnOnly = emptyMap(),
            modifiedByUri = emptyMap(),
        )

        assertTrue(after.isEmpty())
    }

    @Test
    fun `a jpeg entry moves to the new date and a heic entry is replaced`() {
        val current = setOf(
            CaptureDateOverride.encode("jpeg", 1_000L, 7L),
            CaptureDateOverride.encode("heic", 1_000L, 7L),
            CaptureDateOverride.encode("other", 5_000L, 9L),
        )

        val after = CaptureDateOverride.parse(
            dateOverridesAfterEdit(
                current = current,
                captureMsByUri = mapOf("jpeg" to newDate, "heic" to newDate),
                columnOnly = mapOf("heic" to newDate),
                modifiedByUri = mapOf("heic" to 8L),
            ),
        )

        assertEquals(CaptureDateOverride.Entry(newDate, null), after["jpeg"])
        assertEquals(CaptureDateOverride.Entry(newDate, 8L), after["heic"])
        assertEquals(CaptureDateOverride.Entry(5_000L, 9L), after["other"])
    }
}
