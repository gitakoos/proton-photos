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
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer's on-demand face search must never re-detect a photo whose faces the user already curated:
 * a removed face or one left ungrouped hides from the "people in this photo" bar, yet the photo holds face
 * rows, so a forced scan would overwrite them. Only a photo with no face row at all may be searched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerFaceSearchGuardDbTest {

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

    private fun face(user: String, photoKey: String, personId: Long?, rejected: Boolean = false) = FaceEntity(
        id = "$photoKey#0", userId = user, photoKey = photoKey,
        left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
        embedding = ByteArray(0), personId = personId, score = 0.9f, rejected = rejected,
    )

    @Test
    fun `a removed face blocks the search`() = runTest {
        db.faceDao().upsert(face(USER, PHOTO, personId = null, rejected = true))

        assertTrue(db.faceDao().groupedFacesForPhoto(USER, PHOTO).isEmpty())
        assertFalse(viewerMayDetectFaces(db.faceDao(), USER, PHOTO))
    }

    @Test
    fun `an ungrouped face blocks the search`() = runTest {
        db.faceDao().upsert(face(USER, PHOTO, personId = null))

        assertTrue(db.faceDao().groupedFacesForPhoto(USER, PHOTO).isEmpty())
        assertFalse(viewerMayDetectFaces(db.faceDao(), USER, PHOTO))
    }

    @Test
    fun `a grouped face blocks the search`() = runTest {
        db.personDao().upsert(PersonEntity(id = 3L, userId = USER, displayName = "Alice", faceCount = 1))
        db.faceDao().upsert(face(USER, PHOTO, personId = 3L))

        assertFalse(viewerMayDetectFaces(db.faceDao(), USER, PHOTO))
    }

    @Test
    fun `a scanned photo with no faces may be searched`() = runTest {
        db.faceScanDao().upsert(listOf(FaceScanEntity(USER, PHOTO)))

        assertTrue(viewerMayDetectFaces(db.faceDao(), USER, PHOTO))
    }

    @Test
    fun `another account's face does not block the search`() = runTest {
        db.faceDao().upsert(face("other", PHOTO, personId = null, rejected = true))

        assertTrue(viewerMayDetectFaces(db.faceDao(), USER, PHOTO))
        assertFalse(viewerMayDetectFaces(db.faceDao(), "other", PHOTO))
    }

    @Test
    fun `a guest's removed face blocks the guest's search`() = runTest {
        db.faceDao().upsert(face(GUEST, PHOTO, personId = null, rejected = true))

        assertFalse(viewerMayDetectFaces(db.faceDao(), GUEST, PHOTO))
    }

    private companion object {
        const val USER = "acct"
        const val GUEST = "local"
        const val PHOTO = "content://media/external/images/media/7"
    }
}
