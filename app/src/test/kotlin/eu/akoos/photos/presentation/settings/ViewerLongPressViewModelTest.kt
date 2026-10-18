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

package eu.akoos.photos.presentation.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer's long-press choice: it selects text until the user picks the menu, and the pick is
 * stored under the key the viewer reads, with no account involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerLongPressViewModelTest {

    private lateinit var context: Context

    /** Every view model a case built, cleared afterwards so none keeps reading the shared DataStore
     *  into the next test class. */
    private val built = ArrayList<ViewerLongPressViewModel>()

    private fun viewModel() = ViewerLongPressViewModel(context).also { built += it }

    /** Waits until the stored choice reads [expected]. Each check is a fresh read: a read that starts
     *  while the write is still in flight can miss it, which is no concern of a screen that keeps
     *  observing, but would leave a one-off wait hanging. */
    private suspend fun awaitStored(expected: Boolean) = withTimeout(5_000) {
        while (context.settingsDataStore.data.first()[SettingsKeys.VIEWER_LONG_PRESS_OPENS_MENU] != expected) delay(20)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    @After
    fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        Dispatchers.resetMain()
    }

    @Test
    fun `a fresh install keeps the long press on select text`() = runBlocking {
        assertNull(context.settingsDataStore.data.first()[SettingsKeys.VIEWER_LONG_PRESS_OPENS_MENU])
        // The state starts false before the store has been read, so what the empty store maps to is seen
        // only once the collection has had time to take its first emission.
        val seen = CopyOnWriteArrayList<Boolean>()
        val collecting = launch(Dispatchers.Default) { viewModel().opensMenu.collect { seen += it } }
        delay(500)
        collecting.cancel()
        assertFalse(seen.last())
    }

    @Test
    fun `picking open the menu is stored and read back, and picking select text undoes it`() = runBlocking {
        val viewModel = viewModel()

        viewModel.set(opensMenu = true)
        awaitStored(true)
        assertTrue(withTimeout(5_000) { viewModel.opensMenu.first { it } })

        viewModel.set(opensMenu = false)
        awaitStored(false)
        assertFalse(withTimeout(5_000) { viewModel.opensMenu.first { !it } })
    }
}
