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
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cluster-summary bookkeeping a merge and a stray orphan must leave behind: a merge drops both the
 * absorbed source's summary and the target's now-stale one (so nothing pins a new face to a dead person
 * and the self-check recomputes), and the orphan sweep removes a summary whose person is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceCurationConsistencyDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase
    private lateinit var assign: AssignPersonNameUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        assign = AssignPersonNameUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(),
            db.notPersonDao(), db.personCoverDao(), db.clusterSummaryDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun face(id: String, photoKey: String, personId: Long) = db.faceDao().upsert(
        FaceEntity(
            id = id, userId = acct, photoKey = photoKey, left = 0f, top = 0f, right = 1f, bottom = 1f,
            landmarks = "", embedding = ByteArray(0), personId = personId, score = 0.9f,
        ),
    )

    private suspend fun summary(personId: Long) =
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(personId, acct, ByteArray(0), 1, 1))

    @Test
    fun `merging drops the source orphan and the stale target summary`() = runTest {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, displayName = "Alice", faceCount = 1))
        db.personDao().upsert(PersonEntity(id = 2L, userId = acct, displayName = null, faceCount = 1))
        face("kT#0", "kT", personId = 1L)
        face("kS#0", "kS", personId = 2L)
        summary(1L); summary(2L)

        val survivor = assign.invoke(acct, fromPersonId = 2L, rawName = "Alice")

        assertEquals(1L, survivor)
        // Both summaries are gone: the source's orphan and the target's now-stale one.
        assertEquals(emptyList<Long>(), db.clusterSummaryDao().getAllForUser(acct).map { it.personId })
        // The source's face moved onto the survivor, and the empty source person is gone.
        assertEquals(setOf("kS#0", "kT#0"), db.faceDao().facesForPerson(acct, 1L).map { it.id }.toSet())
        assertNull(db.personDao().personById(2L))
    }

    @Test
    fun `the orphan sweep removes a summary whose person is gone and keeps the rest`() = runTest {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, displayName = "Bob", faceCount = 1))
        summary(1L)   // valid
        summary(99L)  // orphan: no person 99

        db.clusterSummaryDao().deleteOrphansForUser(acct)

        assertEquals(listOf(1L), db.clusterSummaryDao().getAllForUser(acct).map { it.personId })
    }
}
