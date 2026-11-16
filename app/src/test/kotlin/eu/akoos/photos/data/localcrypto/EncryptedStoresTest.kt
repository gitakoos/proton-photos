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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The start-up restore reaches every encrypted settings file, opened or not by the rest of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedStoresTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every encrypted settings file is opened and registered for the restore, the transfer history too`() {
        val stores = encryptedStores(context)

        assertEquals(LocalEncryptionCoordinator.STORE_NAMES.toSet(), stores.keys)
        for ((name, store) in stores) assertSame(name, store, LocalStores.named(name))
    }

    @Test
    fun `a Hidden password removed after a copy was set aside is known to be newer than it, whatever the clock says`() {
        val locked = LocalCrypto.lockedStores(context)
        locked.setAside("settings", byteArrayOf(1))
        val older = locked.pending("settings").single()
        LocalCrypto.markPasswordRemoved(context)
        // Set aside after the removal, under a clock set back a day.
        LockedStoreFiles(older.parentFile!!, clock = { System.currentTimeMillis() - 86_400_000L }).setAside("settings", byteArrayOf(2))
        val newer = locked.pending("settings").single { it != older }

        assertTrue(LocalCrypto.passwordRemovedAfter(context, older))
        assertFalse(LocalCrypto.passwordRemovedAfter(context, newer))
    }
}
