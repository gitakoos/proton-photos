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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudForQuickStartTest {

    private fun cloud(id: String) = CloudPhoto(id, "s", "v", 0L, "$id.jpg", "image/jpeg", 1L, null, "r")
    private fun local(uri: String) = LocalMediaItem(uri, 0L, "$uri.jpg", "image/jpeg", 1L, "Camera")
    private fun row(uri: String, cloudId: String?, status: SyncStatus) = SyncStateLite(uri, cloudId, "h", status, false)

    @Test
    fun `a Drive photo paired to an older device file waits for the whole scan`() {
        val shown = cloudForQuickStart(
            cloud = listOf(cloud("newPair"), cloud("oldPair"), cloud("driveOnly"), cloud("freed")),
            newest = listOf(local("content://1")),
            sync = SyncStateSnapshot.Rows(
                listOf(
                    row("content://1", "newPair", SyncStatus.SYNCED),
                    row("content://9", "oldPair", SyncStatus.SYNCED),
                    row("content://8", "freed", SyncStatus.CLOUD_ONLY),
                ),
            ),
        )
        assertEquals(listOf("newPair", "driveOnly", "freed"), shown.map { it.linkId })
    }

    @Test
    fun `with nothing paired elsewhere every Drive photo shows`() {
        val all = listOf(cloud("a"), cloud("b"))
        assertEquals(all, cloudForQuickStart(all, emptyList(), SyncStateSnapshot.Rows(emptyList())))
    }
}
