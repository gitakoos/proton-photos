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

package eu.akoos.photos.data.mlsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Coverage for the order a sign-out stops ML sync in ([MlSyncSignOut.stopFor]): the account's Drive client is
 * closed to new calls first, then the run is cancelled, and the sign-out goes on only once the run has ended,
 * the hand-back it runs on its way out included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlSyncSignOutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun signOut(): MlSyncSignOut {
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.close(any()) } answers { events += "client closed" }
        return MlSyncSignOut(context, provider)
    }

    @Test
    fun `a sign-out closes the client, then waits for the cancelled run to end`() = runBlocking {
        val signOut = signOut()
        val started = CompletableDeferred<Unit>()
        val run = launch(Dispatchers.Default) {
            signOut.track {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(200)
                        events += "run handed back"
                    }
                }
            }
        }
        started.await()

        val ended = signOut.stopFor(UserId("user"))
        events += "sign-out goes on"

        // The wait ends when the tracked run, its hand-back included, has finished. The job's own completion
        // follows a moment later, so it is joined rather than asserted.
        assertTrue(ended)
        assertEquals(listOf("client closed", "run handed back", "sign-out goes on"), events.toList())
        run.join()
    }

    @Test
    fun `with no run going a sign-out only closes the client`() = runBlocking {
        assertTrue(signOut().stopFor(UserId("user")))
        assertEquals(listOf("client closed"), events.toList())
    }

    @Test
    fun `a run that ended on its own is no longer waited for`() = runBlocking {
        val signOut = signOut()
        assertEquals(7, signOut.track { 7 })
        assertTrue(signOut.stopFor(UserId("user")))
    }
}
