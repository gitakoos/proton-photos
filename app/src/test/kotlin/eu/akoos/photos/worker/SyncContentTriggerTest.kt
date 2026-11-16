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
package eu.akoos.photos.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.akoos.photos.util.WorkManagerTestSetup
import eu.akoos.photos.worker.SyncWorker.Companion.ArmedTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The content trigger must never stop a backup in progress: arming it again (activity start, settings change,
 * the end of every run) keeps a live trigger, and a running request from an older build, which was itself the
 * backup, gets the new trigger queued after it instead of being replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncContentTriggerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestSetup.initialize(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER).result.get()
        workManager.pruneWork().result.get()
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER).result.get()
    }

    // ─── policy ───────────────────────────────────────────────────────────────

    @Test
    fun `nothing armed arms a fresh trigger`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.contentTriggerPolicy(emptyList()))
    }

    @Test
    fun `only finished entries arm a fresh trigger`() {
        val finished = listOf(
            ArmedTrigger(WorkInfo.State.SUCCEEDED, true),
            ArmedTrigger(WorkInfo.State.CANCELLED, false),
            ArmedTrigger(WorkInfo.State.FAILED, false),
        )
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.contentTriggerPolicy(finished))
    }

    @Test
    fun `a waiting or running trigger worker is kept`() {
        assertEquals(ExistingWorkPolicy.KEEP, SyncWorker.contentTriggerPolicy(listOf(ArmedTrigger(WorkInfo.State.ENQUEUED, true))))
        assertEquals(ExistingWorkPolicy.KEEP, SyncWorker.contentTriggerPolicy(listOf(ArmedTrigger(WorkInfo.State.RUNNING, true))))
    }

    @Test
    fun `a running backup from an older build gets the trigger queued after it`() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            SyncWorker.contentTriggerPolicy(listOf(ArmedTrigger(WorkInfo.State.RUNNING, false))),
        )
    }

    @Test
    fun `a trigger already queued after an older running backup is kept`() {
        val armed = listOf(ArmedTrigger(WorkInfo.State.RUNNING, false), ArmedTrigger(WorkInfo.State.BLOCKED, true))
        assertEquals(ExistingWorkPolicy.KEEP, SyncWorker.contentTriggerPolicy(armed))
    }

    @Test
    fun `a waiting request from an older build is replaced`() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            SyncWorker.contentTriggerPolicy(listOf(ArmedTrigger(WorkInfo.State.ENQUEUED, false))),
        )
    }

    // ─── WorkManager ──────────────────────────────────────────────────────────

    private fun armed(): List<WorkInfo> = runBlocking {
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.NAME_CONTENT_OBSERVER).first()
    }

    @Test
    fun `the trigger is a trigger worker and arming it again keeps the same request`() = runBlocking {
        SyncWorker.scheduleContentObserver(context)
        val first = armed().single { !it.state.isFinished }
        assertTrue(SyncWorker.TAG_CONTENT_TRIGGER in first.tags)

        SyncWorker.scheduleContentObserver(context)
        SyncWorker.scheduleContentObserver(context)

        val open = armed().filterNot { it.state.isFinished }
        assertEquals(listOf(first.id), open.map { it.id })
    }

    @Test
    fun `the trigger arming its successor replaces itself`() = runBlocking {
        SyncWorker.scheduleContentObserver(context)
        val first = armed().single { !it.state.isFinished }

        SyncWorker.armContentTrigger(context, selfRearm = true)

        val open = armed().filterNot { it.state.isFinished }
        assertEquals(1, open.size)
        assertTrue(open.single().id != first.id)
        assertTrue(SyncWorker.TAG_CONTENT_TRIGGER in open.single().tags)
    }
}
