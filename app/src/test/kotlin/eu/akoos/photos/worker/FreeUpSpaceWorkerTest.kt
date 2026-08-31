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

import androidx.work.NetworkType
import eu.akoos.photos.presentation.settings.FreeUpInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the three gates the automatic free-up sweep runs behind, extracted from
 * [FreeUpSpaceWorker.isUsableInterval], [FreeUpSpaceWorker.isCloudStateFreshEnough] and
 * [FreeUpSpaceWorker.sweepConstraints].
 *
 * The interval gate is the dangerous one: it decides whether a sweep request states a real minimum
 * age at all. An interval of zero puts the cutoff at "now", which matches every backed-up photo, so
 * a gate that admits it reclaims the device copy of an entire library in one sweep. The values fed
 * to it are [FreeUpInterval.ms], so the accepted cases read from that enum and this test fails if an
 * interval of zero is ever offered.
 *
 * The constraint assertions are a tripwire: they fail loudly if a network constraint is re-added.
 * The scheduled reclaim path is local - [eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase.invoke]
 * selects backed-up rows from Room and deletes their device copies with no network call - so a
 * network constraint fetches nothing and only stops the sweep running offline. (The manual button
 * adds a cloud verification step, but the scheduled worker never runs it.)
 *
 * No Android, no Robolectric, no WorkManager runtime: plain JVM assertions on the inputs.
 */
class FreeUpSpaceWorkerTest {

    /** A fixed "now" so the freshness arithmetic reads identically on every run and machine. */
    private val now = 1_750_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun `a sweep request carrying no interval at all is refused`() {
        assertFalse(
            "the no-interval sentinel must never sweep",
            FreeUpSpaceWorker.isUsableInterval(FreeUpSpaceWorker.NO_INTERVAL_MS),
        )
    }

    @Test
    fun `a zero interval is refused`() {
        assertFalse(
            "a zero interval puts the cutoff at \"now\" and reclaims every backed-up photo",
            FreeUpSpaceWorker.isUsableInterval(0L),
        )
    }

    @Test
    fun `a negative interval is refused`() {
        for (intervalMs in listOf(-1L, -86_400_000L, Long.MIN_VALUE)) {
            assertFalse(
                "interval $intervalMs must never sweep",
                FreeUpSpaceWorker.isUsableInterval(intervalMs),
            )
        }
    }

    @Test
    fun `every interval the picker offers is accepted`() {
        for (interval in FreeUpInterval.entries) {
            assertTrue(
                "interval ${interval.name} must be usable",
                FreeUpSpaceWorker.isUsableInterval(interval.ms),
            )
        }
    }

    @Test
    fun `the gate is strict about zero and admits the millisecond above it`() {
        assertTrue(FreeUpSpaceWorker.isUsableInterval(1L))
        assertTrue(FreeUpSpaceWorker.isUsableInterval(Long.MAX_VALUE))
    }

    @Test
    fun `an install that has never verified the cloud does not sweep`() {
        assertFalse(
            "a missing timestamp must not sweep: a fresh install has verified nothing",
            FreeUpSpaceWorker.isCloudStateFreshEnough(null, now),
        )
        assertFalse(FreeUpSpaceWorker.isCloudStateFreshEnough(0L, now))
        assertFalse(FreeUpSpaceWorker.isCloudStateFreshEnough(-1L, now))
    }

    @Test
    fun `a timestamp in the future does not sweep`() {
        assertFalse(
            "a clock moved backwards must not read as infinitely fresh",
            FreeUpSpaceWorker.isCloudStateFreshEnough(now + 1L, now),
        )
        assertFalse(FreeUpSpaceWorker.isCloudStateFreshEnough(now + dayMs * 30, now))
    }

    @Test
    fun `a recently verified cloud sweeps`() {
        assertTrue(FreeUpSpaceWorker.isCloudStateFreshEnough(now, now))
        assertTrue(FreeUpSpaceWorker.isCloudStateFreshEnough(now - dayMs, now))
        assertTrue(FreeUpSpaceWorker.isCloudStateFreshEnough(now - dayMs * 6, now))
    }

    @Test
    fun `the window boundary is exclusive`() {
        val window = FreeUpSpaceWorker.CLOUD_FRESHNESS_WINDOW_MS
        assertTrue(FreeUpSpaceWorker.isCloudStateFreshEnough(now - window + 1L, now))
        assertFalse(
            "exactly at the window the picture of the cloud is already too old",
            FreeUpSpaceWorker.isCloudStateFreshEnough(now - window, now),
        )
    }

    @Test
    fun `an app left untouched for weeks does not sweep`() {
        assertFalse(
            "the unopened-app case is the whole reason this gate exists",
            FreeUpSpaceWorker.isCloudStateFreshEnough(now - dayMs * 30, now),
        )
    }

    @Test
    fun `the sweep requires no network`() {
        assertEquals(
            NetworkType.NOT_REQUIRED,
            FreeUpSpaceWorker.sweepConstraints().requiredNetworkType,
        )
    }

    @Test
    fun `the sweep requires battery not low`() {
        assertTrue(FreeUpSpaceWorker.sweepConstraints().requiresBatteryNotLow())
    }
}
