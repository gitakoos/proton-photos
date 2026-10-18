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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.model.FaceBoxNorm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The People list resolves the stored covers in a few bounded reads: past the size of one statement, a
 * stored cover on the feed is still the cover, a cover off the feed or gone falls back to the person's next
 * face on the feed, a person with no face on the feed is dropped, and the order stays most-photographed first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservePeopleCoversDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun face(
        photo: String,
        personId: Long,
        box: FloatArray = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
    ) = FaceEntity(
        id = "$photo#0", userId = acct, photoKey = photo,
        left = box[0], top = box[1], right = box[2], bottom = box[3], landmarks = "",
        embedding = ByteArray(4), personId = personId, score = 0.9f,
    )

    private fun local(uri: String, width: Int = 0, height: Int = 0) = LocalMediaItem(
        uri = uri, dateTaken = 1L, displayName = uri, mimeType = "image/jpeg", sizeBytes = 1L, bucketName = null,
        width = width, height = height,
    )

    private fun item(uri: String, width: Int = 0, height: Int = 0) = GalleryItem.LocalOnly(local(uri, width, height))

    private fun synced(linkId: String, uri: String, width: Int = 0, height: Int = 0) = GalleryItem.Synced(
        CloudPhoto(
            linkId = linkId, shareId = "s", volumeId = "v", captureTime = 1L, displayName = uri,
            mimeType = "image/jpeg", sizeBytes = 1L, thumbnailUrl = null, revisionId = "r",
        ),
        local(uri, width, height),
    )

    private suspend fun person(id: Long, cover: String) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, coverFaceId = cover, faceCount = 2))

    private suspend fun summaries(feed: List<GalleryItem>) =
        ObservePeopleUseCase(db.personDao(), db.faceDao())(UserId(acct), flowOf(feed)).first()

    @Test
    fun `a face scanned under a device uri keeps its person once the photo is backed up`() = runTest {
        db.faceDao().upsert(face("content://media/7", 1L))
        person(1L, "content://media/7#0")

        val people = summaries(listOf(synced("link-7", "content://media/7")))

        assertEquals(listOf(1L), people.map { it.personId })
        assertEquals("content://media/7", people.single().coverPhotoKey)
        assertEquals(FaceBoxNorm(0.1f, 0.2f, 0.3f, 0.4f), people.single().faceBox)
    }

    @Test
    fun `a fraction box comes through as it is stored`() = runTest {
        db.faceDao().upsert(face("a", 1L, floatArrayOf(0.25f, 0.1f, 0.75f, 0.6f)))
        person(1L, "a#0")

        val box = summaries(listOf(item("a", width = 4000, height = 3000))).single().faceBox

        assertEquals(FaceBoxNorm(0.25f, 0.1f, 0.75f, 0.6f), box)
    }

    @Test
    fun `a pixel box is scaled against its photo, under its uri once backed up too`() = runTest {
        db.faceDao().upsert(face("a", 1L, floatArrayOf(160f, 120f, 800f, 600f)))
        db.faceDao().upsert(face("content://media/9", 2L, floatArrayOf(160f, 120f, 800f, 600f)))
        person(1L, "a#0")
        person(2L, "content://media/9#0")
        // 3200 x 2400 decodes at half size, 1600 x 1200, the space the old box was stored in.
        val feed = listOf(item("a", width = 3200, height = 2400), synced("link-9", "content://media/9", 3200, 2400))

        val people = summaries(feed).associateBy { it.personId }

        val expected = FaceBoxNorm(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(expected, people.getValue(1L).faceBox)
        assertEquals(expected, people.getValue(2L).faceBox)
    }

    @Test
    fun `a pixel box with no known photo size has no box`() = runTest {
        db.faceDao().upsert(face("link-1", 1L, floatArrayOf(160f, 120f, 800f, 600f)))
        person(1L, "link-1#0")

        val box = summaries(listOf(GalleryItem.CloudOnly(synced("link-1", "x").cloud))).single().faceBox

        assertEquals(null, box)
    }

    @Test
    fun `covers past one statement resolve as one read per person would`() = runTest {
        val people = 1_200
        val feed = ArrayList<GalleryItem>()
        for (p in 1..people) {
            val id = p.toLong()
            db.faceDao().upsert(face("p$p-a", id))
            db.faceDao().upsert(face("p$p-b", id))
            // A stored cover gone from the face table on every seventh person; on every fifth it sits on a
            // photo off the feed.
            val cover = if (p % 7 == 0) "missing#0" else "p$p-a#0"
            db.personDao().upsert(PersonEntity(id = id, userId = acct, coverFaceId = cover, faceCount = p))
            // Every person's second photo is on the feed; the first only when it is not the off-feed case, and
            // a person on a multiple of 11 has no photo on the feed at all.
            if (p % 11 != 0) {
                if (p % 5 != 0) feed.add(item("p$p-a"))
                feed.add(item("p$p-b"))
            }
        }

        val summaries = ObservePeopleUseCase(db.personDao(), db.faceDao())(UserId(acct), flowOf(feed)).first()

        val expected = (people downTo 1).filter { it % 11 != 0 }
        assertEquals(expected.map { it.toLong() }, summaries.map { it.personId })
        val feedKeys = feed.mapTo(HashSet()) { it.stableId }
        for (s in summaries) {
            val p = s.personId.toInt()
            // The fallback is the person's first face on the feed, as the per-person read gives it.
            val wanted = if (p % 7 == 0 || p % 5 == 0) {
                db.faceDao().facesForPerson(acct, s.personId).first { it.photoKey in feedKeys }.photoKey
            } else {
                "p$p-a"
            }
            assertEquals("person $p", wanted, s.coverPhotoKey)
        }
    }

    @Test
    fun `the bounded read returns every face asked for once and leaves out unknown ids`() = runTest {
        val ids = (0 until 1_100).map { "f$it#0" }
        for (i in 0 until 1_100) db.faceDao().upsert(face("f$i", 1L))

        val rows = db.faceDao().facesByIds(acct, ids + ids.take(10) + listOf("nope#0"))

        assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        assertEquals(1_100, rows.size)
    }
}
