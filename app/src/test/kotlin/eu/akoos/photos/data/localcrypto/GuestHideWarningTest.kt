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

package eu.akoos.photos.data.localcrypto

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.hidden.HiddenStorageManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A guest without a Hidden password is told once, after a hide, that those photos need one to be recoverable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuestHideWarningTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocalCrypto.root(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        LocalCrypto.root(context).deleteRecursively()
        File(context.filesDir, "hidden").deleteRecursively()
    }

    @Test
    fun `only a guest without a password who was not warned yet is warned`() {
        assertTrue(guestHideWarningDue(signedIn = false, passwordSet = false, warned = false))
        assertFalse(guestHideWarningDue(signedIn = true, passwordSet = false, warned = false))
        assertFalse(guestHideWarningDue(signedIn = false, passwordSet = true, warned = false))
        assertFalse(guestHideWarningDue(signedIn = false, passwordSet = false, warned = true))
    }

    @Test
    fun `the warning is remembered once dismissed`() {
        assertFalse(LocalCrypto.isGuestHideWarned(context))
        LocalCrypto.markGuestHideWarned(context)
        assertTrue(LocalCrypto.isGuestHideWarned(context))
    }

    @Test
    fun `a photo sealed into the vault by a hide is announced`() = runBlocking {
        val source = File(context.cacheDir, "to_hide.jpg").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { LocalEncryptionEvents.photoHidden.first() } }

        val stored = HiddenStorageManager(context).store(Uri.fromFile(source).toString(), "to_hide.jpg", "image/jpeg")

        assertNotNull(stored)
        heard.await()
        source.delete()
        Unit
    }
}
