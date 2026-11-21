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

package eu.akoos.photos.presentation.news

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsUrlTest {
    @Test
    fun httpsAndMailtoAreOpenable() {
        assertTrue(isOpenableNewsUrl("https://photosforproton.eu/news"))
        assertTrue(isOpenableNewsUrl("HTTPS://photosforproton.eu"))
        assertTrue(isOpenableNewsUrl("mailto:someone@example.com"))
        assertTrue(isOpenableNewsUrl("MailTo:someone@example.com"))
    }

    @Test
    fun otherSchemesAreRejected() {
        listOf(
            "http://example.com",
            "intent://scan/#Intent;scheme=zxing;end",
            "tel:+3612345678",
            "javascript:alert(1)",
            "market://details?id=x",
            "file:///sdcard/a.txt",
            "example.com/page",
        ).forEach { assertFalse(it, isOpenableNewsUrl(it)) }
    }

    @Test
    fun blankIsRejected() {
        assertFalse(isOpenableNewsUrl(""))
        assertFalse(isOpenableNewsUrl("   "))
    }
}
