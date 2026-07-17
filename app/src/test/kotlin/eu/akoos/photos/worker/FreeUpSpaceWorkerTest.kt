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
 * Pure-value coverage for the two gates the automatic free-up sweep runs behind, extracted from
 * [FreeUpSpaceWorker.isUsableInterval] and [FreeUpSpaceWorker.sweepConstraints].
 *
 * The interval gate is the dangerous one: it decides whether a sweep request states a real minimum
 * age at all. An interval of zero puts the cutoff at "now", which matches every backed-up photo, so
 * a gate that admits it reclaims the device copy of an entire library in one sweep. The values fed
 * to it are [FreeUpInterval.ms], so the accepted cases read from that enum and this test fails if an
 * interval of zero is ever offered.
 *
 * The constraint assertions are a tripwire: they fail loudly if a network constraint is re-added.
 * The whole reclaim path is local - [eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase] takes only a
 * Context and a SyncStateRepository, whose implementation takes only a Room DAO - so a network
 * constraint fetches nothing and only stops the sweep running offline.
 *
 * No Android, no Robolectric, no WorkManager runtime: plain JVM assertions on the inputs.
 */
class FreeUpSpaceWorkerTest {

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
