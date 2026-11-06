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

import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.dao.FaceCurationTimeDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The shared "this isn't them" bookkeeping, run against mocked DAOs. */
class NotThisPersonUseCaseTest {

    private val faceDao = mockk<FaceDao>(relaxed = true)
    private val personDao = mockk<PersonDao>(relaxed = true)
    private val manualDao = mockk<PersonManualPhotoDao>(relaxed = true)
    private val notPersonDao = mockk<NotPersonDao>(relaxed = true)
    private val coverDao = mockk<PersonCoverDao>(relaxed = true)
    private val curationDao = mockk<FaceCurationTimeDao>(relaxed = true)
    private val summaryDao = mockk<ClusterSummaryDao>(relaxed = true)
    private val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
    private val accounts = mockk<AccountManager>(relaxed = true)
    private lateinit var useCase: NotThisPersonUseCase

    @Before
    fun setUp() {
        coEvery { accounts.getPrimaryUserId() } returns kotlinx.coroutines.flow.flowOf(UserId("acct"))
        useCase = NotThisPersonUseCase(accounts, faceDao, personDao, manualDao, notPersonDao, coverDao, curationDao, summaryDao, scheduler)
    }

    @Test
    fun namedPersonRecordsRejectionsUnassignsAndRefreshes() = runTest {
        coEvery { personDao.personById(7L) } returns PersonEntity(id = 7L, userId = "acct", displayName = "Anna")
        coEvery { faceDao.distinctPhotoKeysForPerson("acct", 7L) } returns listOf("a", "b")
        coEvery { coverDao.photoKeyForName("acct", "Anna") } returns null
        coEvery { faceDao.topFaceForPerson("acct", 7L) } returns "face-b"
        coEvery { manualDao.photoKeysForNameList("acct", "Anna") } returns emptyList()

        useCase("acct", 7L, listOf("f1", "f2"), listOf("a"))

        val rows = slot<List<NotPersonEntity>>()
        coVerify { notPersonDao.add(capture(rows)) }
        assertEquals(listOf("f1", "f2"), rows.captured.map { it.faceId })
        assertEquals(setOf("Anna"), rows.captured.map { it.personName }.toSet())
        coVerify { faceDao.unassignFaces(listOf("f1", "f2")) }
        coVerify { personDao.updateCoverAndCount(7L, "face-b", 2) }
        coVerify { summaryDao.deleteByPerson(7L) }
        verify { scheduler.requestRecluster(UserId("acct")) }
    }

    @Test
    fun namelessPersonRecordsNoRejection() = runTest {
        coEvery { personDao.personById(9L) } returns PersonEntity(id = 9L, userId = "acct", displayName = null)

        useCase("acct", 9L, listOf("f1"), listOf("a"))

        coVerify(exactly = 0) { notPersonDao.add(any()) }
        coVerify { faceDao.unassignFaces(listOf("f1")) }
        verify { scheduler.requestRecluster(UserId("acct")) }
    }
}
