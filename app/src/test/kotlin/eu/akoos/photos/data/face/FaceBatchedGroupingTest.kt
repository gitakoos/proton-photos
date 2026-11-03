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

package eu.akoos.photos.data.face

import android.content.Context
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.domain.usecase.ClusterFacesUseCase
import eu.akoos.photos.domain.usecase.IncrementalBatch
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.HealthVerdict
import eu.akoos.photos.util.NetworkObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * A library too big for the whole-library rebuild is grouped in batches. Two things the batched run must do
 * on its own: every five batches it saves how far it has looked, so a run the process loses part-way resumes
 * there instead of reading every ungrouped face again; and a run that finds no new faces still puts names
 * that arrived since (a restore, an import) on their groups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceBatchedGroupingTest {

    @get:Rule
    val files = TemporaryFolder()

    private val user = UserId("acct")
    private val faceDao = mockk<FaceDao>(relaxed = true)
    private val cluster = mockk<ClusterFacesUseCase>(relaxed = true)
    private lateinit var scheduler: FaceIndexingScheduler

    /** How the next assignNewFaces calls behave: the full batches they report before a short one ends the
     *  sweep, and the call that throws, if any. Counted per run. */
    private var fullBatches = 0
    private var failOnCall = 0
    private var calls = 0
    private var rowsSeen = 0L
    private val firstMinRowOfRun = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files.root
        every { context.applicationContext } returns context
        InMemoryPreferencesDataStore.install(context, InMemoryPreferencesDataStore())
        val network = mockk<NetworkObserver>(relaxed = true)
        every { network.isUnmetered } returns MutableStateFlow(false)
        val health = mockk<DeviceHealthPolicy>(relaxed = true)
        every { health.mlVerdict() } returns HealthVerdict(heavyMlAllowed = true, backgroundWorkAllowed = true, reason = "ok")

        // Past the whole-rebuild limit, with a steady backlog of ungrouped faces.
        coEvery { faceDao.countForUser("acct") } returns 40_000
        coEvery { faceDao.unclusteredCount("acct") } returns 1_000
        coEvery { faceDao.unclusteredCountSince("acct", any()) } returns 1_000
        coEvery { cluster.nameGroupsFromConfirmedFaces(any(), any()) } returns 0
        coEvery { cluster.assignNewFaces(any(), any(), any(), any()) } coAnswers {
            val batch = secondArg<IncrementalBatch>()
            calls++
            if (calls == 1) firstMinRowOfRun += batch.minRow
            if (calls == failOnCall) throw IllegalStateException("process lost")
            rowsSeen += 100
            batch.maxRow = rowsSeen
            batch.scanned = if (calls <= fullBatches) batch.limit else 1
            true
        }

        scheduler = FaceIndexingScheduler(
            context = context,
            faceDao = faceDao,
            faceScanDao = mockk(relaxed = true),
            personDao = mockk(relaxed = true),
            personManualPhotoDao = mockk(relaxed = true),
            notPersonDao = mockk(relaxed = true),
            photoListingDao = mockk(relaxed = true),
            thumbnailScheduler = mockk(relaxed = true),
            photoDownloadService = mockk(relaxed = true),
            networkObserver = network,
            galleryItemsProvider = Provider { mockk(relaxed = true) },
            clusterFacesUseCase = cluster,
            autoMergePeople = mockk(relaxed = true),
            migrateGuestFaceData = mockk(relaxed = true),
            rekeyPhotoFaces = mockk(relaxed = true),
            deviceHealth = health,
            mlWalkGate = mockk(relaxed = true),
            appDatabase = mockk(relaxed = true),
            accountManager = Provider { mockk(relaxed = true) },
        )
    }

    @After
    fun tearDown() = InMemoryPreferencesDataStore.uninstall()

    private fun runGrouping(full: Int, failOn: Int = 0): Boolean {
        fullBatches = full
        failOnCall = failOn
        calls = 0
        return runBlocking { scheduler.regroupNow(user) }
    }

    @Test
    fun `a run lost part-way resumes from the last mark, saved every five batches`() {
        // Eight batches complete, the ninth is lost: the mark was saved after the fifth (row 500), and not
        // after each batch since (which would read row 800).
        assertFalse(runGrouping(full = 20, failOn = 9))

        runGrouping(full = 0)

        assertEquals(listOf(0L, 500L), firstMinRowOfRun)
    }

    @Test
    fun `a run lost before its fifth batch starts the next one from the beginning`() {
        assertFalse(runGrouping(full = 20, failOn = 5))

        runGrouping(full = 0)

        assertEquals(listOf(0L, 0L), firstMinRowOfRun)
    }

    @Test
    fun `a run with no new faces still names the groups`() {
        assertTrue(runGrouping(full = 1))
        coVerify(exactly = 1) { cluster.nameGroupsFromConfirmedFaces(user, any()) }
        val placedBefore = firstMinRowOfRun.size

        // The backlog is what the last run left ungrouped on purpose: nothing new to place.
        assertTrue(runGrouping(full = 1))

        assertEquals(placedBefore, firstMinRowOfRun.size)
        coVerify(exactly = 2) { cluster.nameGroupsFromConfirmedFaces(user, any()) }
    }
}
