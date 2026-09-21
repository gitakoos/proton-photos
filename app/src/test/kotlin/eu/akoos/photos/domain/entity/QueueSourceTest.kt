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

package eu.akoos.photos.domain.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueueSource.isAutomatic] is what the auto-backup switch acts on. Turning the switch off leaves
 * the folder selection intact so re-enabling restores it, which means the selection alone cannot say
 * whether a photo should upload and this predicate is the whole answer.
 *
 * The cases that matter are the ones that must still upload with the switch off: each is an
 * instruction about one photo, while the switch is a statement about the folder sweep. Getting any
 * of them wrong makes an explicit action silently do nothing.
 */
class QueueSourceTest {

    @Test
    fun `a folder-sweep row is automatic`() {
        assertTrue(QueueSource.isAutomatic(QueueSource.AUTO_FOLDER))
    }

    @Test
    fun `a row with no recorded source is automatic`() {
        // Legacy rows predate the column, and only reconcile ever created one, so the absent value
        // means the same thing AUTO_FOLDER does. Treating it as explicit would keep uploading the
        // oldest rows in a library with the switch off.
        assertTrue(QueueSource.isAutomatic(null))
    }

    @Test
    fun `a manual back-up-now is not automatic`() {
        assertFalse(QueueSource.isAutomatic(QueueSource.MANUAL))
    }

    @Test
    fun `an album-add is not automatic`() {
        // The photo has to reach Drive to join the album it was just added to, so the switch cannot
        // hold it: the album would sit permanently short of a member the user can see it should have.
        assertFalse(QueueSource.isAutomatic(QueueSource.ALBUM_ADD))
    }

    @Test
    fun `an editor save is not automatic`() {
        assertFalse(QueueSource.isAutomatic(QueueSource.EDITOR))
    }

    @Test
    fun `an unrecognised source is not automatic`() {
        // A source added later, or a value written by an older build, defaults to "the user meant
        // this". Uploading something the switch should have held is recoverable; silently dropping
        // an upload the user asked for is not.
        assertFalse(QueueSource.isAutomatic("SOME_FUTURE_SOURCE"))
    }
}
