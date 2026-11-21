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

package eu.akoos.photos.presentation.common

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A destination change takes the Undo bar away, except the one change that closes the screen which
 * offered it: deleting the last photo in a viewer opened from Search or an album closes the viewer, and
 * the bar has to stay on the screen it returns to.
 */
class UndoBarNavigationTest {

    private val delete = UndoAction.Delete(cloudLinkIds = listOf("link-1"))

    private fun controller() = UndoController(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    @Test
    fun `a navigation drops the pending action`() {
        val undo = controller()
        undo.offer(delete)

        undo.onNavigated()

        assertNull(undo.pending.value)
    }

    @Test
    fun `the screen close that follows the action keeps it`() {
        val undo = controller()
        undo.offer(delete)

        undo.keepThroughScreenClose()
        undo.onNavigated()

        assertEquals(delete, undo.pending.value)
    }

    @Test
    fun `only the one navigation after the close keeps it`() {
        val undo = controller()
        undo.offer(delete)
        undo.keepThroughScreenClose()
        undo.onNavigated()

        undo.onNavigated()

        assertNull(undo.pending.value)
    }

    @Test
    fun `asking to keep with nothing pending does not keep a later offer through a navigation`() {
        val undo = controller()
        undo.keepThroughScreenClose()
        undo.offer(delete)

        undo.onNavigated()

        assertNull(undo.pending.value)
    }

    @Test
    fun `a dismissed action is not brought back by an earlier keep`() {
        val undo = controller()
        undo.offer(delete)
        undo.keepThroughScreenClose()
        undo.dismiss()
        val next = UndoAction.Delete(cloudLinkIds = listOf("link-2"))
        undo.offer(next)

        undo.onNavigated()

        assertNull(undo.pending.value)
    }
}
