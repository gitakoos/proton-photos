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

package eu.akoos.photos.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the plans of the face reads that take a list of ids or photo keys: with no statistics gathered,
 * SQLite would drive a `userId = ?` lookup from the account index, which holds one value, and so read every
 * face of the account for each slice. Each statement mirrors its [FaceDao] query, with three listed keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceIdListPlanDbTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun plan(sql: String): String {
        val args = Array<Any>(sql.count { it == '?' }) { "x$it" }
        val out = StringBuilder()
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            while (c.moveToNext()) out.append(c.getString(c.getColumnIndexOrThrow("detail"))).append(" | ")
        }
        return out.toString()
    }

    private fun assertUses(index: String, sql: String) {
        val plan = plan(sql)
        assertTrue(plan, plan.contains(index))
        assertFalse(plan, plan.contains("index_face_userId"))
    }

    @Test
    fun `the plain account lookup is the trap these reads avoid`() {
        assertTrue(plan("SELECT id FROM face WHERE userId = ? AND id IN (?, ?, ?)").contains("index_face_userId"))
    }

    @Test
    fun `reads by face id run on the primary key`() {
        assertUses(PK, "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE id IN (?, ?, ?) AND +userId = ?")
        assertUses(PK, "SELECT id, embedding FROM face WHERE id IN (?, ?, ?) AND +userId = ?")
        assertUses(PK, "SELECT photoKey FROM face WHERE id IN (?, ?, ?) AND +userId = ?")
    }

    @Test
    fun `reads and writes by photo key run on the photo index`() {
        assertUses(PHOTO, "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE photoKey IN (?, ?, ?) AND +userId = ? " +
            "AND rejected = 0 ORDER BY photoKey, score DESC")
        assertUses(PHOTO, "SELECT photoKey FROM face WHERE photoKey IN (?, ?, ?) AND +userId = ?")
        assertUses(PHOTO, "SELECT id, photoKey, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom FROM face WHERE photoKey IN (?, ?, ?) AND +userId = ?")
        assertUses(PHOTO, "UPDATE face SET manualName = ?, rejected = 0 WHERE photoKey IN (?, ?, ?) AND +userId = ?")
        assertUses(PHOTO, "DELETE FROM face WHERE photoKey IN (?, ?, ?) AND +userId = ?")
        assertUses(PHOTO, "SELECT DISTINCT photoKey FROM face WHERE photoKey IN (?, ?, ?) AND +userId = ? " +
            "AND +personId = ? AND rejected = 0")
    }

    @Test
    fun `reads of one person run on the person or photo index`() {
        assertUses("index_face_", "SELECT id FROM face WHERE +userId = ? AND personId = ? AND photoKey IN (?, ?, ?)")
        assertUses("index_face_", "UPDATE face SET rejected = 1, personId = NULL WHERE +userId = ? AND personId = ? " +
            "AND photoKey IN (?, ?, ?)")
        assertUses("index_face_personId", "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, " +
            "`right` AS boxRight, `bottom` AS boxBottom, score FROM face WHERE +userId = ? AND personId = ? AND rejected = 0")
    }

    private companion object {
        const val PK = "sqlite_autoindex_face_1"
        const val PHOTO = "index_face_photoKey"
    }
}
