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

package eu.akoos.photos.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoListingDisplayRowsTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoListingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoListingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(linkId: String, captureTime: Long, video: Boolean = false) = PhotoListingEntity(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        userId = "user1",
        captureTime = captureTime,
        displayName = "$linkId.jpg",
        mimeType = if (video) "video/mp4" else "image/jpeg",
        sizeBytes = 1024L + captureTime,
        thumbnailUrl = if (captureTime % 2 == 0L) "file:///thumbs/$linkId.jpg" else null,
        revisionId = "rev-$linkId",
        contentHash = if (captureTime % 3 == 0L) null else "hash-$linkId",
        tagsCsv = if (video) "2,0" else "",
        contentKeyPacket = "packet-$linkId",
        encNodeKey = "-----BEGIN PGP MESSAGE-----$linkId",
        encNodePassphrase = "passphrase-$linkId",
        encXAttr = "xattr-$linkId",
        durationMs = if (video) 4_000L else null,
    )

    @Test
    fun `display rows map to the same photos as the full rows, in the same order`() = runTest {
        dao.insertAll((1..40).map { entity("p$it", captureTime = (it % 7) * 100L + it, video = it % 5 == 0) })
        val ids = (1..40).filter { it % 4 != 0 }.map { "p$it" } + "not-in-db"

        // The full rows, newest first, as the screens were handed them before.
        val full = dao.getByLinkIds(ids).sortedByDescending { it.captureTime }.map { it.toDomain() }
        val display = dao.observeDisplayRowsByLinkIds(ids).first().map { it.toDomain() }

        assertEquals(30, display.size)
        assertEquals(full, display)
    }
}
