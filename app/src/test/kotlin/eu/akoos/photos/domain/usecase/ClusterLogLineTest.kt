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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The copied diagnostics log is pasted into public issues, so the line written for a rebuilt cluster
 * says whether it is named and how many faces it holds, and carries nothing a person was called. The
 * line is built from a size and a flag alone, so a name has no way in.
 */
class ClusterLogLineTest {

    @Test
    fun `a named cluster is reported as named and nothing more`() {
        assertEquals("  cluster: 87 faces, named", clusterLogLine(size = 87, named = true))
    }

    @Test
    fun `an unnamed cluster is reported as unnamed`() {
        assertEquals("  cluster: 8 faces, unnamed", clusterLogLine(size = 8, named = false))
    }
}
