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

package eu.akoos.photos

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

/**
 * The Application's fields are injected in every process, the :crypto one included, which returns before it
 * uses any of them. A field injected as the object itself would build its whole dependency graph there (a
 * second settings DataStore on the same file among it), so every injected field is a dagger.Lazy.
 */
class AppProcessInjectionTest {

    @Test
    fun `every field the Application injects is built only when it is first used`() {
        val injected = App::class.java.declaredFields.filter { it.isAnnotationPresent(Inject::class.java) }
        assertTrue("the Application injects its dependencies", injected.isNotEmpty())
        for (field in injected) {
            assertTrue(
                "${field.name} is injected as ${field.type.name}, not as a dagger.Lazy",
                field.type == dagger.Lazy::class.java,
            )
        }
    }
}
