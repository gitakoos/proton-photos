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
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.FaceDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The people list over a big library: 6,400 people holding 120,000 faces, half of whose stored covers are
 * off the feed, so half the people take the per-person fallback. Asserts only what the list holds; the time
 * of each stage of the build is printed for a measurement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservePeopleScaleDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        FaceDiagnostics.clear()
    }

    @After
    fun tearDown() {
        db.close()
        FaceDiagnostics.clear()
    }

    @Test
    fun `a big library builds its people with the covers it should`() = runBlocking {
        val people = 6_400
        val faces = 120_000
        // Face i sits on its own photo and belongs to person i % people + 1; each person's stored cover is its
        // first face, and every even person's cover photo is off the feed.
        val rows = ArrayList<FaceEntity>(faces)
        for (i in 0 until faces) {
            rows.add(
                FaceEntity(
                    id = "ph$i#0", userId = acct, photoKey = "ph$i",
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = ByteArray(16), personId = (i % people + 1).toLong(), score = 0.9f,
                ),
            )
        }
        db.withTransaction {
            for (chunk in rows.chunked(5_000)) db.faceDao().upsert(chunk)
            for (p in 1..people) {
                db.personDao().upsert(PersonEntity(id = p.toLong(), userId = acct, coverFaceId = "ph${p - 1}#0", faceCount = p))
            }
        }
        val feed = ArrayList<GalleryItem>(faces)
        for (i in 0 until faces) {
            val offFeed = i < people && (i + 1) % 2 == 0
            if (!offFeed) {
                feed.add(
                    GalleryItem.LocalOnly(
                        LocalMediaItem(uri = "ph$i", dateTaken = 1L, displayName = "p", mimeType = "image/jpeg", sizeBytes = 1L, bucketName = null),
                    ),
                )
            }
        }
        val useCase = ObservePeopleUseCase(db.personDao(), db.faceDao())

        repeat(2) { round ->
            val startNs = System.nanoTime()
            val summaries = useCase(UserId(acct), flowOf(feed)).first()
            val tookMs = (System.nanoTime() - startNs) / 1_000_000L
            val line = FaceDiagnostics.snapshot(now = 0L).lineSequence().first { it.startsWith("peopleBuilds=") }
            println("people scale round $round: ${tookMs}ms end to end, $line")

            assertEquals(people, summaries.size)
            assertEquals((people downTo 1).map { it.toLong() }, summaries.map { it.personId })
            for (s in summaries) {
                val p = s.personId.toInt()
                val wanted = if (p % 2 == 0) "ph${p - 1 + people}" else "ph${p - 1}"
                assertEquals("person $p", wanted, s.coverPhotoKey)
            }
            assertTrue(line, line.contains("fallbacks=${people / 2} "))
        }

        val first = useCase(UserId(acct), flowOf(ArrayList(feed))).first()
        val second = useCase(UserId(acct), flowOf(feed)).first()
        val equalsStart = System.nanoTime()
        val same = first == second
        println("people scale list equality ${(System.nanoTime() - equalsStart) / 1_000_000L}ms")
        assertTrue(same)
    }
}
