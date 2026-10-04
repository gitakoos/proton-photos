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

package eu.akoos.photos.data.label

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.akoos.photos.data.mlsync.MlSyncSignOut
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import eu.akoos.photos.util.WorkManagerTestSetup
import eu.akoos.photos.worker.LabelSyncWorker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The label sync job against the real WorkManager: [WorkLabelWriteGate] is locked for as long as the job is
 * queued or running, and a sign-out cancels it ([MlSyncSignOut.stopFor]) before the account's labels are wiped,
 * which also lifts the lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelSyncSignOutAndGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Never runs: it is delayed by a day, so the job stays queued. A work constraint would start WorkManager's
     *  constraint trackers, whose battery read throws in this JVM and then fails whichever test runs next. */
    class IdleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

    @Before
    fun setUp() {
        WorkManagerTestSetup.initialize(context)
        WorkManager.getInstance(context).cancelAllWork()
    }

    private fun enqueueLabelJob() {
        val work = OneTimeWorkRequestBuilder<IdleWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(LabelSyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
    }

    private fun jobStates(): List<WorkInfo.State> = runBlocking {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(LabelSyncWorker.UNIQUE_WORK_NAME).first()
    }.map { it.state }

    private fun signOut(): MlSyncSignOut {
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.close(any()) } returns Unit
        return MlSyncSignOut(context, provider)
    }

    @Test
    fun `the gate is open with no job and locked while one is queued`() = runBlocking {
        val gate = WorkLabelWriteGate(context)
        assertFalse(gate.isLocked())

        enqueueLabelJob()

        assertTrue(gate.isLocked())
    }

    @Test
    fun `a sign-out cancels the queued label job and the gate opens`() = runBlocking {
        val gate = WorkLabelWriteGate(context)
        enqueueLabelJob()
        assertTrue(gate.isLocked())

        signOut().stopFor(UserId("user"))

        assertEquals(listOf(WorkInfo.State.CANCELLED), jobStates())
        assertFalse(gate.isLocked())
    }

    @Test
    fun `a sign-out with no label job running leaves nothing queued`() = runBlocking {
        assertTrue(signOut().stopFor(UserId("user")))

        assertTrue(jobStates().isEmpty())
    }
}
