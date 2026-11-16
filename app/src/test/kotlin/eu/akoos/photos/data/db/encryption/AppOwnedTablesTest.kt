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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every table of the newest exported schema is either the app's own (restored after a key loss) or Proton core. */
class AppOwnedTablesTest {

    private fun newestSchema(): File {
        val dir = listOf("schemas", "app/schemas")
            .map { File(it, "eu.akoos.photos.data.db.AppDatabase") }
            .first { it.isDirectory }
        return dir.listFiles()!!.filter { it.extension == "json" }.maxBy { it.nameWithoutExtension.toInt() }
    }

    private fun tablesOf(schema: File): Set<String> =
        Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"").findAll(schema.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `each table is on exactly one side`() {
        val tables = tablesOf(newestSchema())
        assertTrue(tables.isNotEmpty())
        val sides = AppOwnedTables.NAMES.toSet() + AppOwnedTables.PROTON_CORE.toSet()
        assertEquals("tables on no side", emptySet<String>(), tables - sides)
        assertEquals("listed tables the schema lacks", emptySet<String>(), sides - tables)
        assertEquals(emptySet<String>(), AppOwnedTables.NAMES.toSet() intersect AppOwnedTables.PROTON_CORE.toSet())
    }
}
