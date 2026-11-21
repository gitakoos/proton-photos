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

package eu.akoos.photos.data.ml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSideLoadTest {
    @Test
    fun debugBuildUsesTheSideLoadFolder() {
        val root = File("ext")
        assertEquals(File(root, "ocr"), sideLoadDirFor(true, root, "ocr"))
    }

    @Test
    fun releaseBuildNeverSideLoads() {
        assertNull(sideLoadDirFor(false, File("ext"), "ocr"))
    }

    @Test
    fun missingExternalStorageMeansNoSideLoad() {
        assertNull(sideLoadDirFor(true, null, "ocr"))
    }
}
