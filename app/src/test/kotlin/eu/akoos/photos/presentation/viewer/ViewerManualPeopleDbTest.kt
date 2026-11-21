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

package eu.akoos.photos.presentation.viewer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.usecase.HiddenPersonToken
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
 * The people a photo was added to by hand, as the details panel's People row reads them: every name the
 * photo is attached to resolves to the account's person of that name, and nothing else is listed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerManualPeopleDbTest {

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

    private suspend fun person(name: String?, user: String = USER, photos: Int = 1, isOther: Boolean = false): Long =
        db.personDao().insert(PersonEntity(userId = user, displayName = name, faceCount = photos, isOther = isOther))

    private suspend fun attach(name: String, photoKey: String = PHOTO, user: String = USER) =
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(user, name, photoKey)))

    private suspend fun read(user: String = USER, photoKey: String = PHOTO) =
        manualPeopleOnPhoto(db.personDao(), db.personManualPhotoDao(), user, photoKey)

    @Test
    fun `a photo added to several people lists each of them, with no face`() = runTest {
        val anna = person("Anna", photos = 4)
        val bela = person("Béla", photos = 9)
        attach("Anna")
        attach("Béla")

        val people = read()

        assertEquals(setOf(anna, bela), people.map { it.personId }.toSet())
        assertEquals(setOf("Anna", "Béla"), people.mapNotNull { it.name }.toSet())
        assertTrue(people.all { it.faceId.isEmpty() && !it.isOther })
        assertTrue(people.all { DetailsPeopleRow.clampedFaceBox(it.faceBox) == null })
        assertEquals(9, people.single { it.personId == bela }.photoCount)
    }

    @Test
    fun `a photo added to nobody lists no one`() = runTest {
        person("Anna")
        attach("Anna", photoKey = "other-photo")

        assertTrue(read().isEmpty())
    }

    @Test
    fun `a name differing only in case still finds its person`() = runTest {
        val anna = person("Anna")
        attach("anna")

        assertEquals(listOf(anna), read().map { it.personId })
    }

    @Test
    fun `a name no person holds, a hidden group and the Unsorted bucket are left out`() = runTest {
        val token = HiddenPersonToken.make()
        person(token)
        person("Unsorted", isOther = true)
        attach(token)
        attach("Nobody")
        attach("Unsorted")

        assertTrue(read().isEmpty())
    }

    @Test
    fun `another account's people stay out, and a guest reads its own`() = runTest {
        person("Anna", user = "other")
        attach("Anna", user = "other")
        val guestAnna = person("Anna", user = PhotoLocationEntity.LOCAL_USER)
        attach("Anna", user = PhotoLocationEntity.LOCAL_USER)

        assertTrue(read().isEmpty())
        assertEquals(listOf(guestAnna), read(user = PhotoLocationEntity.LOCAL_USER).map { it.personId })
    }

    private companion object {
        const val USER = "user-1"
        const val PHOTO = "photo-1"
    }
}
