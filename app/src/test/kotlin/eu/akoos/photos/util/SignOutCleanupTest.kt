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

package eu.akoos.photos.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import eu.akoos.photos.worker.CloudCompressWorker
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What every sign-out route stops and empties, and the marker that lets a cut-short Settings sign-out finish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignOutCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `stopping the account's work also stops the re-compression of its Drive photos`() {
        val workManager = mockk<WorkManager>(relaxed = true)

        SignOutCleanup.stopBackgroundWork(context, workManager)

        verify { workManager.cancelUniqueWork(CloudCompressWorker.UNIQUE_NAME) }
    }

    @Test
    fun `the diagnostics buffers and their saved copy are emptied`() {
        SyncDiagnostics.log("before sign-out")
        val saved = File(context.filesDir, "diagnostics").apply { mkdirs() }
        File(saved, "log.txt").writeText("x")

        SignOutCleanup.clearDiagnostics(context)

        assertFalse(SyncDiagnostics.dump().contains("before sign-out"))
        assertFalse(saved.exists())
    }

    @Test
    fun `the decrypted cache wipe takes the plaintext staging folder`() {
        val staging = File(context.cacheDir, "plain_staging").apply { mkdirs() }
        File(staging, "upl.jpg").writeText("x")

        SignOutCleanup.clearDecryptedCaches(context)

        assertFalse(staging.exists())
    }

    @Test
    fun `a Settings sign-out marker is kept per account until it is cleared`() = runBlocking {
        SignOutCleanup.markUserSignOutPending(context, "a")
        SignOutCleanup.markUserSignOutPending(context, "b")
        assertEquals(setOf("a", "b"), SignOutCleanup.userSignOutsPending(context))

        SignOutCleanup.clearUserSignOutPending(context, "a")
        assertEquals(setOf("b"), SignOutCleanup.userSignOutsPending(context))
        SignOutCleanup.clearUserSignOutPending(context, "b")
        assertTrue(SignOutCleanup.userSignOutsPending(context).isEmpty())
    }

    @Test
    fun `one Settings sign-out of an account runs at a time`() {
        assertTrue(SignOutCleanup.claimUserSignOut("c"))
        assertFalse(SignOutCleanup.claimUserSignOut("c"))
        assertEquals(setOf("c"), SignOutCleanup.userSignOutsRunningNow())
        SignOutCleanup.releaseUserSignOut("c")
        assertTrue(SignOutCleanup.claimUserSignOut("c"))
        SignOutCleanup.releaseUserSignOut("c")
    }
}
