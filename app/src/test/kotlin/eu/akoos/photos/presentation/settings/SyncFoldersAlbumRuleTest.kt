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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.FolderAlbumBackfill
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Sync folders screen shows and switches the same Drive album rule the upload pipeline and the
 * Excluded folders screen read. With "Back up everything" on and its album default started, every folder
 * is an album except the ones switched off, so the screen must read that rule rather than only the
 * switched-on set, and switching a folder off there must record it as switched off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncFoldersAlbumRuleTest {

    private val context = mockk<Context>()
    private val media = mockk<LocalMediaRepository>()
    private val backfill = mockk<FolderAlbumBackfill>(relaxed = true)
    private lateinit var store: InMemoryPreferencesDataStore

    private fun item(uri: String, bucket: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "$uri.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        bucketName = bucket,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { media.observeLocalMedia() } returns flowOf(
            listOf(item("c1", "Camera"), item("c2", "Camera"), item("s1", "Screenshots"), item("w1", "WhatsApp")),
        )
    }

    @After
    fun tearDown() {
        InMemoryPreferencesDataStore.uninstall()
        Dispatchers.resetMain()
    }

    private suspend fun screen(prefs: Preferences): SyncFoldersViewModel {
        store = InMemoryPreferencesDataStore(prefs)
        InMemoryPreferencesDataStore.install(context, store)
        val vm = SyncFoldersViewModel(context, media, backfill)
        vm.uiState.first { !it.isLoading }
        return vm
    }

    private fun everythingDefault(optedOut: Set<String> = emptySet()) = mutablePreferencesOf(
        SettingsKeys.BACKUP_EVERYTHING to true,
        SettingsKeys.ALBUM_ALL_BY_DEFAULT to true,
        SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES to optedOut,
    )

    private fun SyncFoldersViewModel.mirrored(): Map<String, Boolean> =
        uiState.value.folders.associate { it.name to it.isMirrored }

    @Test
    fun `under the everything default every folder shows as an album but the switched off ones`() = runTest {
        val vm = screen(everythingDefault(optedOut = setOf("Screenshots")))

        assertEquals(mapOf("Camera" to true, "Screenshots" to false, "WhatsApp" to true), vm.mirrored())
    }

    @Test
    fun `without the default only the switched on folders show as albums`() = runTest {
        val vm = screen(mutablePreferencesOf(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES to setOf("WhatsApp")))

        assertEquals(mapOf("Camera" to false, "Screenshots" to false, "WhatsApp" to true), vm.mirrored())
    }

    @Test
    fun `switching off a default album records it as switched off`() = runTest {
        val vm = screen(everythingDefault())

        vm.toggleMirror("Camera")

        assertFalse(vm.mirrored().getValue("Camera"))
        assertEquals(setOf("Camera"), store.current[SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES])
        assertEquals(emptySet<String>(), store.current[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES])
        verify(exactly = 0) { backfill.onAlbumTurnedOn(any()) }
    }

    @Test
    fun `switching a switched off folder back on records it and joins its photos`() = runTest {
        val vm = screen(everythingDefault(optedOut = setOf("Screenshots")))

        vm.toggleMirror("Screenshots")

        assertTrue(vm.mirrored().getValue("Screenshots"))
        assertEquals(setOf("Screenshots"), store.current[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES])
        assertEquals(emptySet<String>(), store.current[SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES])
        verify(exactly = 1) { backfill.onAlbumTurnedOn("Screenshots") }
    }
}
