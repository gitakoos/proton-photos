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
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.settings.namedPeopleCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The named-only people stream gives the surfaces that keep named people only exactly what the full stream gave
 * them once filtered the same way: the same people, in the same order, ties, blank names and the Unsorted bucket
 * included, and the single-person stream gives the row the full stream held for that id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NamedPeopleStreamDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
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

    private suspend fun person(id: Long, name: String?, photos: Int, isOther: Boolean = false, owner: String = acct) {
        db.personDao().upsert(
            PersonEntity(id = id, userId = owner, displayName = name, coverFaceId = "p$id#0", faceCount = photos, isOther = isOther),
        )
        db.faceDao().upsert(
            FaceEntity(
                id = "p$id#0", userId = owner, photoKey = "p$id",
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = ByteArray(4), personId = id, score = 0.9f,
            ),
        )
    }

    private fun item(uri: String) = GalleryItem.LocalOnly(
        LocalMediaItem(uri = uri, dateTaken = 1L, displayName = uri, mimeType = "image/jpeg", sizeBytes = 1L, bucketName = null),
    )

    /** Names of every kind, three people tied on their photo count, the Unsorted bucket (one unnamed, one
     *  carrying a name), a named person whose photo left the feed, and a namesake on another account. */
    private suspend fun seed(): List<GalleryItem> {
        person(8, "Cleo", 1)
        person(5, "Ben", 5)
        person(2, null, 9)
        person(4, "   ", 5)
        person(1, "Anna", 5)
        person(3, "", 7)
        person(6, null, 20, isOther = true)
        person(7, "Zed", 3, isOther = true)
        person(9, "\t", 2)
        person(11, "Dora", 5)
        person(12, "Gone", 4)
        person(10, "Anna", 5, owner = "other")
        return (1L..11L).map { item("p$it") }
    }

    @Test
    fun `the named rows are the full rows filtered by name, in the same order`() = runBlocking {
        seed()
        val all = db.personDao().observePeopleForUser(acct).first()
        val named = db.personDao().observeNamedPeopleForUser(acct).first()

        assertEquals(
            all.filter { !it.displayName.isNullOrBlank() }.map { it.id },
            named.filter { !it.displayName.isNullOrBlank() }.map { it.id },
        )
        assertEquals(
            all.filter { !it.isOther && !it.displayName.isNullOrBlank() }.map { it.id },
            named.filter { !it.isOther && !it.displayName.isNullOrBlank() }.map { it.id },
        )
        assertEquals(namedPeopleCount(all), namedPeopleCount(named))
        assertTrue(named.none { it.displayName.isNullOrEmpty() || it.userId != acct })
    }

    @Test
    fun `the named summaries are the full summaries filtered by name`() = runBlocking {
        val feed = seed()
        val people = ObservePeopleUseCase(db.personDao(), db.faceDao())

        val full = people(user, flowOf(feed)).first().filter { !it.displayName.isNullOrBlank() }
        val named = people(user, flowOf(feed), namedOnly = true).first().filter { !it.displayName.isNullOrBlank() }

        assertEquals(full, named)
        assertTrue(named.none { it.personId == 12L })
    }

    @Test
    fun `one person's stream is the row the full stream held for that id`() = runBlocking {
        seed()
        val all = db.personDao().observePeopleForUser(acct).first()

        for (id in listOf(1L, 2L, 6L, 7L)) {
            assertEquals(all.first { it.id == id }, db.personDao().observePersonForUser(acct, id).first())
        }
        assertNull(db.personDao().observePersonForUser(acct, 10L).first())
        assertNull(db.personDao().observePersonForUser(acct, 99L).first())
    }
}
