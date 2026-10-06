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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The import record must survive between calls (a killed process is its reason to exist), stay small,
 *  and start afresh with each run. */
class ImportEventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `events are kept in order with a time of day in front`() {
        val dir = tmp.newFolder()
        ImportEventLog.record(dir, "stage start", nowMs = 0L)
        ImportEventLog.record(dir, "scan 10%", nowMs = 0L)
        val lines = ImportEventLog.text(dir).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], Regex("""\d\d:\d\d:\d\d stage start""").matches(lines[0]))
        assertTrue(lines[1].endsWith("scan 10%"))
    }

    @Test
    fun `only the most recent lines are kept`() {
        val dir = tmp.newFolder()
        repeat(ImportEventLog.MAX_LINES + 25) { ImportEventLog.record(dir, "event $it") }
        val lines = ImportEventLog.text(dir).lines()
        assertEquals(ImportEventLog.MAX_LINES, lines.size)
        assertTrue(lines.last().endsWith("event ${ImportEventLog.MAX_LINES + 24}"))
        assertFalse(lines.any { it.endsWith("event 0") })
    }

    @Test
    fun `a line break inside an event cannot split it into two records`() {
        val dir = tmp.newFolder()
        ImportEventLog.record(dir, "one\ntwo")
        assertEquals(1, ImportEventLog.text(dir).lines().size)
    }

    @Test
    fun `reset starts the next run clean and an unused log reads empty`() {
        val dir = tmp.newFolder()
        assertEquals("", ImportEventLog.text(dir))
        ImportEventLog.record(dir, "old run")
        ImportEventLog.reset(dir)
        assertEquals("", ImportEventLog.text(dir))
    }
}
