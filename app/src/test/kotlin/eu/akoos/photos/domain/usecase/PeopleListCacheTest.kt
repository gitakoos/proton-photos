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

import eu.akoos.photos.domain.model.PersonSummary
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleListCacheTest {

    private fun person(id: Long) = PersonSummary(personId = id, displayName = null, coverPhotoKey = "ph$id", faceBox = null, faceCount = 1)

    @Test
    fun `a first visit shows only what the build gives`() = runBlocking {
        val cache = PeopleListCache()
        val built = listOf(person(1), person(2))
        assertEquals(listOf(built), cache.remembered(UserId("a"), flowOf(built)).toList())
    }

    @Test
    fun `a later visit shows the last list at once, then the new build`() = runBlocking {
        val cache = PeopleListCache()
        val first = listOf(person(1), person(2))
        val second = listOf(person(1))
        cache.remembered(UserId("a"), flowOf(first)).toList()
        assertEquals(listOf(first, second), cache.remembered(UserId("a"), flowOf(second)).toList())
        // Still building: the kept list stands meanwhile.
        assertEquals(listOf(second), cache.remembered(UserId("a"), emptyFlow()).toList())
    }

    @Test
    fun `each account and the guest keep their own list`() = runBlocking {
        val cache = PeopleListCache()
        val own = listOf(person(1))
        cache.remembered(UserId("a"), flowOf(own)).toList()
        assertEquals(emptyList<List<PersonSummary>>(), cache.remembered(UserId("b"), emptyFlow()).toList())
        assertEquals(emptyList<List<PersonSummary>>(), cache.remembered(null, emptyFlow()).toList())
        val guest = listOf(person(9))
        cache.remembered(null, flowOf(guest)).toList()
        assertEquals(listOf(guest), cache.remembered(null, emptyFlow()).toList())
        assertEquals(listOf(own), cache.remembered(UserId("a"), emptyFlow()).toList())
    }
}
