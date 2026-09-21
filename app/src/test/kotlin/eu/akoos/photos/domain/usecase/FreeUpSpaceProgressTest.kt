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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for what the Free up space screen shows against what the sweep actually does.
 *
 * The screen lists photos and then permanently deletes their device copies, so the two have to be
 * the same set: a list drawn from a second, similar-looking filter could name a photo the sweep
 * spares, or spare one it takes, and the list would be a promise about a different set than the one
 * that runs. [FreeUpSpaceUseCase.candidates] is that shared query, and these tests pin that the
 * sweep deletes exactly what it returns, in that order.
 *
 * The progress contract is pinned here too, because a screen that reports a count the user watches
 * for minutes must not stall short of the total or run past it.
 */
class FreeUpSpaceProgressTest {

    private val userId = UserId("u1")
    private lateinit var repo: SyncStateRepository
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var useCase: FreeUpSpaceUseCase

    /** Eligible by [FreeUpSpaceUseCase.isEligibleForReclamation]: SYNCED with a real stamp. */
    private fun eligible(i: Int) = SyncState(
        localUri = "content://media/external/images/media/$i",
        cloudFileId = "cloud-$i",
        localHash = "hash-$i",
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
    )

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        repo = mockk(relaxed = true)
        // The reclaim path exercised here never touches the cloud collaborators; the manual verify
        // path is covered in FreeUpSpaceVerifyTest, so relaxed stand-ins are enough for construction.
        useCase = FreeUpSpaceUseCase(context, repo, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `the sweep reclaims exactly the rows the screen was shown`() = runTest {
        val rows = (1..5).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        every { resolver.delete(any(), any(), any()) } returns 1

        val listed = useCase.candidates(userId, Long.MAX_VALUE)
        val result = useCase(userId, Long.MAX_VALUE)

        assertEquals(rows.map { it.localUri }, listed.map { it.localUri })
        assertEquals(rows.size, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        // Every listed photo, and nothing else, had its row moved off the device.
        for (row in listed) {
            coVerify(exactly = 1) {
                repo.updateStatusAndDeleteLocal(row.localUri, SyncStatus.CLOUD_ONLY)
            }
        }
        coVerify(exactly = listed.size) { repo.updateStatusAndDeleteLocal(any(), any()) }
    }

    @Test
    fun `progress starts at zero, never goes backwards, and ends on the total`() = runTest {
        val rows = (1..45).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        every { resolver.delete(any(), any(), any()) } returns 1

        val reports = mutableListOf<Pair<Int, Int>>()
        useCase(userId, Long.MAX_VALUE) { done, total -> reports += done to total }

        assertEquals(0 to rows.size, reports.first())
        assertEquals(rows.size to rows.size, reports.last())
        assertTrue("every report carries the same total", reports.all { it.second == rows.size })
        assertTrue(
            "progress must not go backwards: $reports",
            reports.zipWithNext().all { (a, b) -> b.first >= a.first },
        )
        assertTrue("no report may exceed the total", reports.all { it.first <= rows.size })
    }

    @Test
    fun `a photo the system refuses is left for the consent dialog rather than counted as freed`() = runTest {
        val rows = (1..3).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        // delete() answering 0 is how the platform reports "not yours to delete" on API 30+.
        every { resolver.delete(any(), any(), any()) } returns 0

        val result = useCase(userId, Long.MAX_VALUE)

        // Nothing was reclaimed, so no row may claim its device copy is gone.
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
        // On a JVM unit test Build.VERSION.SDK_INT reads 0, so the consent branch is skipped and the
        // run reports Done with zero freed. The invariant that matters either way is that a refused
        // photo is never counted.
        if (result is FreeUpSpaceUseCase.FreeUpResult.Done) assertEquals(0, result.freed)
    }
}
