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

package eu.akoos.photos.presentation

import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.presentation.albums.albumLocalUrisByLinkId
import eu.akoos.photos.presentation.gallery.syncedCloudIdsOnDevice
import eu.akoos.photos.presentation.hidden.looseDeviceCopies
import eu.akoos.photos.presentation.settings.pendingUploadUris
import eu.akoos.photos.presentation.viewer.syncedLocalUrisByCloudId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The screens that read the shared lean copy of the sync rows answer exactly as they do from the full
 * rows. Each `fullRow` function below is a screen's reading of every column of every row; each test
 * checks it against the screen's reading of the lean columns of the same table, down to the order of
 * a list and the row a shared cloud id resolves to.
 */
class SyncSnapshotReadersTest {

    @Test
    fun `the gallery's on-device set is the one the full rows give`() {
        val fromLean = syncedCloudIdsOnDevice(SyncStateSnapshot.Rows(lean(table)))

        assertEquals(fullRowOnDeviceIds(table), fromLean)
        assertEquals(setOf("c-1", "c-2", "c-twin", "c-mixed"), fromLean)
    }

    @Test
    fun `with no blank synced uri the gallery takes the snapshot's own synced set`() {
        val noBlankSynced = table.filterNot { it.status == SyncStatus.SYNCED && it.localUri.isBlank() }
        val sync = SyncStateSnapshot.Rows(lean(noBlankSynced))

        val fromLean = syncedCloudIdsOnDevice(sync)

        assertEquals(fullRowOnDeviceIds(noBlankSynced), fromLean)
        assertSame("shared with the snapshot's other readers", sync.syncedCloudIds, fromLean)
    }

    @Test
    fun `the vaulted set the gallery and the album picker read is the one the full rows give`() {
        val fromLean = SyncStateSnapshot.Rows(lean(table)).hiddenCloudIds

        assertEquals(fullRowGalleryHiddenIds(table), fromLean)
        assertEquals(fullRowPickerHiddenIds(table), fromLean)
        assertEquals(setOf("c-vaulted"), fromLean)
    }

    @Test
    fun `the viewer's uri lookup is the one the full rows give, in the same order`() {
        val fromLean = syncedLocalUrisByCloudId(lean(table))

        assertEquals(fullRowViewerUris(table).toList(), fromLean.toList())
        assertEquals("the later of two rows on one cloud id stands", "uri://twin-b", fromLean["c-twin"])
        assertEquals("a blank uri names no file", "uri://mixed", fromLean["c-mixed"])
        assertFalse("a freed-up file's stale uri stays out", "c-freed" in fromLean)
    }

    @Test
    fun `the album's uri lookup is the one the full rows give, in the same order`() {
        val fromLean = albumLocalUrisByLinkId(lean(table))

        assertEquals(fullRowAlbumUris(table).toList(), fromLean.toList())
        assertEquals("the later of two rows on one cloud id stands", "uri://twin-b", fromLean["c-twin"])
        assertFalse("a freed-up file's stale uri stays out", "c-freed" in fromLean)
        assertFalse("a vaulted photo is not on the device", "c-vaulted" in fromLean)
    }

    @Test
    fun `the vault's loose-copy set is the one the full rows give`() {
        val rows = lean(table)
        val hiddenIdSets = listOf(
            emptySet<String>(),
            setOf("c-gone"),
            setOf("c-1", "c-mixed", "c-blank", "c-vaulted", "c-freed", "c-demoted", "c-gone"),
        )

        for (ids in hiddenIdSets) {
            assertEquals("hidden ids $ids", fullRowLooseCopies(ids, table), looseDeviceCopies(ids, rows))
        }
        assertEquals(
            setOf("c-1", "c-mixed", "c-blank"),
            looseDeviceCopies(setOf("c-1", "c-mixed", "c-blank", "c-vaulted", "c-freed", "c-demoted", "c-gone"), rows),
        )
    }

    @Test
    fun `the Activity pending list is the one the full rows give, in the table's order`() {
        val fromLean = pendingUploadUris(lean(table))

        assertEquals(fullRowPendingUris(table), fromLean)
        assertEquals(listOf("uri://queued-b", "uri://queued-a"), fromLean)
    }

    @Test
    fun `an empty table reads as nothing on every screen`() {
        val none = emptyList<SyncState>()
        val sync = SyncStateSnapshot.Rows(emptyList())

        assertEquals(fullRowOnDeviceIds(none), syncedCloudIdsOnDevice(sync))
        assertEquals(fullRowGalleryHiddenIds(none), sync.hiddenCloudIds)
        assertEquals(fullRowViewerUris(none), syncedLocalUrisByCloudId(sync.rows))
        assertEquals(fullRowAlbumUris(none), albumLocalUrisByLinkId(sync.rows))
        assertEquals(fullRowLooseCopies(setOf("c-1"), none), looseDeviceCopies(setOf("c-1"), sync.rows))
        assertEquals(fullRowPendingUris(none), pendingUploadUris(sync.rows))
    }

    // The screens' readings of the full rows.

    /** Gallery: the cloud ids with a SYNCED copy on the device, which upgrade a cloud cell's badge. */
    private fun fullRowOnDeviceIds(states: List<SyncState>): Set<String> =
        states.asSequence()
            .filter { it.status == SyncStatus.SYNCED }
            .filter { it.cloudFileId != null && it.localUri.isNotBlank() }
            .map { it.cloudFileId!! }
            .toSet()

    /** Gallery: the cloud ids of vaulted photos, drawn with the crossed-out eye. */
    private fun fullRowGalleryHiddenIds(states: List<SyncState>): Set<String> =
        states.asSequence()
            .filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
            .map { it.cloudFileId!! }
            .toSet()

    /** Album picker: the cloud ids of vaulted photos, kept out of the picker. */
    private fun fullRowPickerHiddenIds(states: List<SyncState>): Set<String> =
        states.filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
            .mapNotNull { it.cloudFileId }
            .toSet()

    /** Viewer: cloud id to device uri, which upgrades a cloud photo's badge after a download. */
    private fun fullRowViewerUris(states: List<SyncState>): Map<String, String> =
        states.asSequence()
            .filter { it.status == SyncStatus.SYNCED }
            .filter { it.cloudFileId != null && it.localUri.isNotBlank() }
            .associate { it.cloudFileId!! to it.localUri }

    /** Album detail: cloud id to device uri for the album's members on the device. */
    private fun fullRowAlbumUris(states: List<SyncState>): Map<String, String> =
        states.filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
            .associate { it.cloudFileId!! to it.localUri }

    /** Hidden vault: the hidden cloud photos whose device copy is still in the phone's gallery. */
    private fun fullRowLooseCopies(ids: Set<String>, states: List<SyncState>): Set<String> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            states.asSequence()
                .filter { it.status == SyncStatus.SYNCED }
                .mapNotNull { it.cloudFileId }
                .filter { it in ids }
                .toSet()
        }

    /** Activity: the device uris waiting to back up. */
    private fun fullRowPendingUris(states: List<SyncState>): List<String> =
        states.filter { it.status == SyncStatus.LOCAL_ONLY && it.queued }
            .mapNotNull { it.localUri.takeIf { u -> u.isNotBlank() } }

    /** The columns the DAO's lean read projects out of each row, in the same order. */
    private fun lean(states: List<SyncState>): List<SyncStateLite> =
        states.map { SyncStateLite(it.localUri, it.cloudFileId, it.localHash, it.status, it.queued) }

    private fun row(uri: String, cloudId: String?, status: SyncStatus, queued: Boolean = false) = SyncState(
        localUri = uri,
        cloudFileId = cloudId,
        localHash = "sha-$uri",
        // Columns the lean copy leaves out, set so a reader that needed them would show it.
        cloudHash = "cloud-hash-$uri",
        status = status,
        lastSyncAttemptMs = 11L,
        lastSyncSuccessMs = 12L,
        backedUpAtMs = 13L,
        sizeBytes = 4_096L,
        queued = queued,
        queueSource = if (queued) "MANUAL" else null,
    )

    /** One account's table, in the order a read returns it. */
    private val table = listOf(
        row("uri://synced-1", "c-1", SyncStatus.SYNCED),
        // A backed-up photo whose queue flag is still set is not waiting to upload.
        row("uri://synced-2", "c-2", SyncStatus.SYNCED, queued = true),
        // Two device files paired to one Drive copy.
        row("uri://twin-a", "c-twin", SyncStatus.SYNCED),
        row("uri://twin-b", "c-twin", SyncStatus.SYNCED),
        // SYNCED rows with a blank uri: one alone on its cloud id, one after a real row on its id.
        row("", "c-blank", SyncStatus.SYNCED),
        row("uri://mixed", "c-mixed", SyncStatus.SYNCED),
        row(" ", "c-mixed", SyncStatus.SYNCED),
        row("uri://synced-unpaired", null, SyncStatus.SYNCED),
        // A freed-up device file keeps its stale uri on a CLOUD_ONLY row.
        row("uri://freed", "c-freed", SyncStatus.CLOUD_ONLY),
        row("uri://vaulted", "c-vaulted", SyncStatus.HIDDEN),
        row("uri://vaulted-unpaired", null, SyncStatus.HIDDEN),
        row("uri://queued-b", null, SyncStatus.LOCAL_ONLY, queued = true),
        row("uri://idle", null, SyncStatus.LOCAL_ONLY),
        // A device-only row still carrying its pairing, idle and queued.
        row("uri://demoted", "c-demoted", SyncStatus.LOCAL_ONLY),
        row("uri://queued-a", "c-requeued", SyncStatus.LOCAL_ONLY, queued = true),
        row("  ", null, SyncStatus.LOCAL_ONLY, queued = true),
        row("uri://uploading", null, SyncStatus.UPLOADING, queued = true),
        row("uri://modified", "c-modified", SyncStatus.LOCAL_MODIFIED),
        row("uri://conflict", "c-conflict", SyncStatus.CONFLICT),
    )
}
