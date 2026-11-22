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

package eu.akoos.photos.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The folder, album and view choices a sign-out sets aside, and what the next sign-in takes back: the same
 * account finds them again, any other account finds nothing, and a choice made since the sign-in stays.
 */
class AccountChoiceStashTest {

    private val owner = UserId("u1")
    private val other = UserId("u2")
    private val noVault: (String) -> Boolean = { false }

    private fun chosen() = mutablePreferencesOf(
        SettingsKeys.SYNC_FOLDER_NAMES to setOf("Camera", "Trip 2026"),
        SettingsKeys.BACKUP_EVERYTHING to false,
        SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES to setOf("Trip 2026"),
        SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES to setOf("Camera"),
        SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES to setOf("Screenshots"),
        SettingsKeys.MOSAIC_GRID to true,
        SettingsKeys.ALBUMS_DEFAULT_FILTER to 2,
    )

    private fun MutablePreferences.signOut(userId: UserId, hadNoSession: Boolean = false) =
        AccountScopedPreferences.clearIn(this, userId, keptVaultUris = emptySet(), hadNoSession = hadNoSession, isVaultUri = noVault)

    @Test
    fun `the same account signing in again gets every choice back`() {
        val prefs = chosen()
        prefs.signOut(owner)

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf(AccountChoiceStash.BACKUP, AccountChoiceStash.TIMELINE_FOLDERS, AccountChoiceStash.VIEWS), back)
        assertEquals(setOf("Camera", "Trip 2026"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertEquals(false, prefs[SettingsKeys.BACKUP_EVERYTHING])
        assertEquals(setOf("Trip 2026"), prefs[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES])
        assertEquals(setOf("Camera"), prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES])
        assertEquals(setOf("Screenshots"), prefs[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES])
        assertEquals(true, prefs[SettingsKeys.MOSAIC_GRID])
        assertEquals(2, prefs[SettingsKeys.ALBUMS_DEFAULT_FILTER])
        assertNull("a setting never chosen stays unset", prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES])
        assertNull("the set-aside copy is used once", prefs[AccountChoiceStash.keyFor(owner)])
    }

    @Test
    fun `a sign-out still empties the choices for whoever signs in next`() {
        val prefs = chosen()

        prefs.signOut(owner)

        assertNull(prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.BACKUP_EVERYTHING])
        assertNull(prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.MOSAIC_GRID])
    }

    @Test
    fun `backing up no folder comes back as no folder, not as never chosen`() {
        val prefs = mutablePreferencesOf(SettingsKeys.SYNC_FOLDER_NAMES to emptySet<String>())
        prefs.signOut(owner)

        AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(emptySet<String>(), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `another account signing in gets nothing and leaves the first account's choices set aside`() {
        val prefs = chosen()
        prefs.signOut(owner)

        val forOther = AccountChoiceStash.takeBackIn(prefs, other)

        assertEquals(emptySet<String>(), forOther)
        assertNull(prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertEquals(setOf(AccountChoiceStash.BACKUP, AccountChoiceStash.TIMELINE_FOLDERS, AccountChoiceStash.VIEWS), AccountChoiceStash.takeBackIn(prefs, owner))
    }

    @Test
    fun `another account's sign-out keeps the first account's choices apart from its own`() {
        val prefs = chosen()
        prefs.signOut(owner)
        prefs[SettingsKeys.SYNC_FOLDER_NAMES] = setOf("Download")
        prefs.signOut(other)

        AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf("Camera", "Trip 2026"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `a backup choice made since the sign-in stays whole`() {
        val prefs = chosen()
        prefs.signOut(owner)
        prefs[SettingsKeys.BACKUP_EVERYTHING] = true

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf(AccountChoiceStash.TIMELINE_FOLDERS, AccountChoiceStash.VIEWS), back)
        assertEquals(true, prefs[SettingsKeys.BACKUP_EVERYTHING])
        assertNull("no old folder joins the new choice", prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES])
        assertNull("the set-aside copy goes all the same", prefs[AccountChoiceStash.keyFor(owner)])
    }

    @Test
    fun `a view changed since the sign-in keeps the views as they are now`() {
        val prefs = chosen()
        prefs.signOut(owner)
        prefs[SettingsKeys.SEAMLESS_GRID] = true

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf(AccountChoiceStash.BACKUP, AccountChoiceStash.TIMELINE_FOLDERS), back)
        assertEquals(true, prefs[SettingsKeys.SEAMLESS_GRID])
        assertNull(prefs[SettingsKeys.MOSAIC_GRID])
        assertNull(prefs[SettingsKeys.ALBUMS_DEFAULT_FILTER])
    }

    @Test
    fun `a sign-out that wipes twice keeps every choice set aside`() {
        val prefs = chosen()
        prefs.signOut(owner)
        prefs.signOut(owner)

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf(AccountChoiceStash.BACKUP, AccountChoiceStash.TIMELINE_FOLDERS, AccountChoiceStash.VIEWS), back)
        assertEquals(setOf("Camera", "Trip 2026"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertEquals(true, prefs[SettingsKeys.MOSAIC_GRID])
    }

    @Test
    fun `a group written between two wipes replaces only that group`() {
        val prefs = chosen()
        prefs.signOut(owner)
        prefs[SettingsKeys.SEAMLESS_GRID] = true
        prefs.signOut(owner)

        AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf("Camera", "Trip 2026"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertEquals(true, prefs[SettingsKeys.SEAMLESS_GRID])
        assertNull(prefs[SettingsKeys.MOSAIC_GRID])
    }

    @Test
    fun `a second sign-out gives back what the account chose after the first`() {
        val prefs = chosen()
        prefs.signOut(owner)
        AccountChoiceStash.takeBackIn(prefs, owner)
        prefs[SettingsKeys.SYNC_FOLDER_NAMES] = setOf("Camera")
        prefs.signOut(owner)

        AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf("Camera"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `a sign-out with nothing chosen leaves nothing to take back`() {
        val prefs = chosen()
        prefs.signOut(owner)
        AccountChoiceStash.takeBackIn(prefs, owner)
        val empty = mutablePreferencesOf()
        empty.signOut(owner)

        val back = AccountChoiceStash.takeBackIn(empty, owner)

        assertEquals(emptySet<String>(), back)
        assertNull(empty[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `a sign-in that never had a session keeps the choices of whoever uses the app and sets nothing aside`() {
        val prefs = chosen()

        prefs.signOut(UserId("u-unfinished"), hadNoSession = true)

        assertEquals(setOf("Camera", "Trip 2026"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertEquals(setOf("Screenshots"), prefs[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES])
        assertEquals(true, prefs[SettingsKeys.MOSAIC_GRID])
        assertNull(prefs[AccountChoiceStash.keyFor(UserId("u-unfinished"))])
    }

    @Test
    fun `a sign-in with nothing set aside changes nothing`() {
        val prefs = mutablePreferencesOf(SettingsKeys.SYNC_FOLDER_NAMES to setOf("Camera"))

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(emptySet<String>(), back)
        assertEquals(setOf("Camera"), prefs[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `a set-aside copy that does not read writes nothing and is dropped`() {
        val prefs = mutablePreferencesOf(AccountChoiceStash.keyFor(owner) to "{not json")

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(emptySet<String>(), back)
        assertNull(prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertNull(prefs[AccountChoiceStash.keyFor(owner)])
    }

    @Test
    fun `a value of the wrong kind in the set-aside copy is skipped`() {
        val prefs = mutablePreferencesOf(
            AccountChoiceStash.keyFor(owner) to """{"sync_folder_names":["Camera",3],"backup_everything":"yes","mosaic_grid":true}""",
        )

        val back = AccountChoiceStash.takeBackIn(prefs, owner)

        assertEquals(setOf(AccountChoiceStash.VIEWS), back)
        assertNull(prefs[SettingsKeys.SYNC_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.BACKUP_EVERYTHING])
        assertEquals(true, prefs[SettingsKeys.MOSAIC_GRID])
    }
}
