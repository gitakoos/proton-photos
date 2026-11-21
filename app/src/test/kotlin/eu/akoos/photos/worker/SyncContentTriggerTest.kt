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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import eu.akoos.photos.util.WorkManagerTestSetup
import eu.akoos.photos.worker.SyncWorker.Companion.ArmedTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The content trigger must never stop a backup in progress: arming it again (activity start, settings change,
 * the end of every run) keeps a live trigger, and a running request from an older build, which was itself the
 * backup, gets the new trigger queued after it instead of being replaced. The run a trigger queues has its own
 * name, so it never waits behind a run held for the battery, starts after a failed run, and never stops a
 * running one.
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
        workManager.cancelUniqueWork(SyncWorker.NAME_ONESHOT).result.get()
        workManager.cancelUniqueWork(SyncWorker.NAME_TRIGGERED).result.get()
        workManager.pruneWork().result.get()
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER).result.get()
        workManager.cancelUniqueWork(SyncWorker.NAME_ONESHOT).result.get()
        workManager.cancelUniqueWork(SyncWorker.NAME_TRIGGERED).result.get()
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

    @Test
    fun `a triggered run with nothing open starts on its own`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncWorker.triggeredRunPolicy(emptyList()))
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            SyncWorker.triggeredRunPolicy(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)),
        )
    }

    @Test
    fun `a triggered run is queued after a running one`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncWorker.triggeredRunPolicy(listOf(WorkInfo.State.RUNNING)))
    }

    @Test
    fun `a run waiting to start covers the trigger`() {
        assertEquals(ExistingWorkPolicy.KEEP, SyncWorker.triggeredRunPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(
            ExistingWorkPolicy.KEEP,
            SyncWorker.triggeredRunPolicy(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)),
        )
    }

    @Test
    fun `queueing a triggered run never takes a policy that stops running work`() {
        val states = WorkInfo.State.values().toList()
        // Every combination of up to two entries.
        val shapes = listOf(emptyList<WorkInfo.State>()) + states.map { listOf(it) } +
            states.flatMap { x -> states.map { y -> listOf(x, y) } }
        for (shape in shapes) {
            val policy = SyncWorker.triggeredRunPolicy(shape)
            assertTrue("$shape -> $policy", policy == ExistingWorkPolicy.KEEP || policy == ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    @Test
    fun `a triggered run never ends failed, so the run queued behind it still runs`() {
        assertEquals(Result.success(), SyncWorker.finalResult(Result.failure(), triggered = true))
        assertEquals(Result.retry(), SyncWorker.finalResult(Result.retry(), triggered = true))
        assertEquals(Result.success(), SyncWorker.finalResult(Result.success(), triggered = true))
        assertEquals(Result.failure(), SyncWorker.finalResult(Result.failure(), triggered = false))
    }

    // ─── WorkManager ──────────────────────────────────────────────────────────

    private fun infos(name: String): List<WorkInfo> = runBlocking {
        workManager.getWorkInfosForUniqueWorkFlow(name).first()
    }

    /** A backup run under [name] that is not running yet and will not start soon, like a run held for a
     *  battery that is not low. */
    private fun enqueueWaitingRun(name: String, batteryNotLow: Boolean = false): java.util.UUID {
        val waiting = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(batteryNotLow).build())
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, waiting).result.get()
        return waiting.id
    }

    /** Waits until [name] holds an entry other than [except], as an enqueue that is not awaited lands. */
    private fun awaitOther(name: String, except: java.util.UUID): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            infos(name).singleOrNull { it.id != except }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "nothing new under $name" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `a run queued the usual way waits behind a run held for the battery`() {
        val held = enqueueWaitingRun(SyncWorker.NAME_ONESHOT, batteryNotLow = true)

        SyncWorker.runNow(context, allowLowBattery = true)

        assertEquals(WorkInfo.State.BLOCKED, awaitOther(SyncWorker.NAME_ONESHOT, held).state)
    }

    @Test
    fun `the triggered run does not wait behind a run held for the battery and leaves it alone`() = runBlocking {
        val held = enqueueWaitingRun(SyncWorker.NAME_ONESHOT, batteryNotLow = true)

        SyncWorker.queueTriggeredRun(context)

        val triggered = infos(SyncWorker.NAME_TRIGGERED).single()
        assertNotEquals(WorkInfo.State.BLOCKED, triggered.state)
        assertFalse(triggered.constraints.requiresBatteryNotLow())
        assertTrue(SyncWorker.TAG_TRIGGERED in triggered.tags)
        assertEquals(WorkInfo.State.ENQUEUED, infos(SyncWorker.NAME_ONESHOT).single { it.id == held }.state)
    }

    @Test
    fun `a triggered run already waiting is kept, not stopped`() = runBlocking {
        val waiting = enqueueWaitingRun(SyncWorker.NAME_TRIGGERED)

        SyncWorker.queueTriggeredRun(context)
        SyncWorker.queueTriggeredRun(context)

        val open = infos(SyncWorker.NAME_TRIGGERED).filterNot { it.state.isFinished }
        assertEquals(listOf(waiting), open.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, open.single().state)
    }

    @Test
    fun `a triggered run starts after a failed one`() = runBlocking {
        val failed = OneTimeWorkRequestBuilder<FailingWorker>().build()
        workManager.enqueueUniqueWork(SyncWorker.NAME_TRIGGERED, ExistingWorkPolicy.REPLACE, failed).result.get()
        val deadline = System.currentTimeMillis() + 10_000L
        while (workManager.getWorkInfoById(failed.id).get()?.state != WorkInfo.State.FAILED) {
            check(System.currentTimeMillis() < deadline) { "the failing run did not fail" }
            // WorkManager starts the run from the main thread, which the test itself holds.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }

        SyncWorker.queueTriggeredRun(context)

        val added = infos(SyncWorker.NAME_TRIGGERED).single { it.id != failed.id }
        assertNotEquals(WorkInfo.State.BLOCKED, added.state)
        assertNotEquals(WorkInfo.State.FAILED, added.state)
    }

    @Test
    fun `the trigger fires within a minute of the first change however busy MediaStore stays`() = runBlocking {
        SyncWorker.scheduleContentObserver(context)
        val constraints = armed().single { !it.state.isFinished }.constraints
        assertEquals(SyncWorker.CONTENT_TRIGGER_UPDATE_DELAY_MS, constraints.contentTriggerUpdateDelayMillis)
        assertEquals(SyncWorker.CONTENT_TRIGGER_MAX_DELAY_MS, constraints.contentTriggerMaxDelayMillis)
        assertTrue(constraints.contentTriggerMaxDelayMillis <= 60_000L)
    }

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

/** A run that fails at once, for a failed run under the triggered name. */
class FailingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.failure()
}
