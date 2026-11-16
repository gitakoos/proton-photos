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

package eu.akoos.photos.data.db.encryption

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sync rows that count as the user's own work, run as SQL against the real column names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserWorkQueryTest {

    private fun count(rows: List<String>): Long {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                "CREATE TABLE sync_state (localUri TEXT PRIMARY KEY, status TEXT, queued INTEGER, lastSyncSuccessMs INTEGER)",
            )
            for (row in rows) db.execSQL("INSERT INTO sync_state VALUES ($row)")
            return db.rawQuery(AppOwnedTables.USER_WORK.getValue("sync_state"), null).use { it.moveToFirst(); it.getLong(0) }
        } finally {
            db.close()
        }
    }

    @Test
    fun `rows the vault reseeds after a rebuild are not the user's work, a photo hidden since is`() {
        val reseeded = "'file:///data/user/0/eu.akoos.photos/files/hidden/IMG_1.jpg', 'HIDDEN', 0, NULL"
        val cloudOnly = "'content://media/external/images/media/7', 'CLOUD_ONLY', 0, NULL"
        assertEquals(0L, count(listOf(reseeded, cloudOnly)))

        val hiddenSince = "'content://media/external/images/media/9', 'HIDDEN', 0, NULL"
        assertEquals(1L, count(listOf(reseeded, hiddenSince)))
        assertEquals(1L, count(listOf("'content://media/external/images/media/3', 'SYNCED', 0, 1800000000000")))
        assertEquals(1L, count(listOf("'content://media/external/images/media/4', 'LOCAL_ONLY', 1, NULL")))
    }
}
