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

package eu.akoos.photos.data.repository.drive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saving photos of a shared album into the user's own library skips a photo the library already holds
 * (same content hash) rather than copying it twice. The hashes are looked up in this user's own listing,
 * 500 at a time; a photo whose listing carries no hash is never taken as held, and a lookup that fails
 * copies everything rather than nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPhotoAlreadyHeldTest {

    private val user = "user1"
    private lateinit var db: AppDatabase
    private val lookups = mutableListOf<Int>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun own(linkId: String, hash: String, owner: String = user) = db.photoListingDao().insertAll(
        listOf(
            PhotoListingEntity(
                linkId = linkId, shareId = "share1", volumeId = "vol1", userId = owner, captureTime = 1_000L,
                displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1_024L, revisionId = "rev1",
                contentHash = hash,
            ),
        ),
    )

    private suspend fun held(ids: List<String>, hashes: Map<String, String?>) =
        sharedPhotosAlreadyHeld(ids, { hashes[it] }) { chunk ->
            lookups += chunk.size
            db.photoListingDao().findExistingContentHashes(user, chunk)
        }

    @Test
    fun `a shared photo the library already holds is skipped and a new one is copied`() = runTest {
        own("mine", "hash-a")

        val skip = held(listOf("shared-a", "shared-b"), mapOf("shared-a" to "hash-a", "shared-b" to "hash-b"))

        assertEquals(setOf("shared-a"), skip)
    }

    @Test
    fun `a photo held only by another account is copied`() = runTest {
        own("theirs", "hash-a", owner = "user2")

        assertTrue(held(listOf("shared-a"), mapOf("shared-a" to "hash-a")).isEmpty())
    }

    @Test
    fun `a photo whose listing carries no hash is copied`() = runTest {
        own("mine", "hash-a")

        assertTrue(held(listOf("shared-x"), mapOf("shared-x" to null)).isEmpty())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `a failed lookup copies everything`() = runTest {
        val skip = sharedPhotosAlreadyHeld(listOf("shared-a"), { "hash-a" }) { error("database closed") }

        assertTrue(skip.isEmpty())
    }

    @Test
    fun `a large selection is looked up 500 hashes at a time`() = runTest {
        val ids = (1..1_200).map { "shared-$it" }
        ids.forEachIndexed { i, id -> own("mine-$i", "hash-$id") }

        val skip = held(ids, ids.associateWith { "hash-$it" })

        assertEquals(ids.toSet(), skip)
        assertEquals(listOf(500, 500, 200), lookups)
    }
}
