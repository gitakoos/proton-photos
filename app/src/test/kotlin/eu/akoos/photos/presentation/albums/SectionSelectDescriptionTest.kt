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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what an album section header's select button is announced as: the span of dates that header
 * gathers, so a screen reader never says "month" over a day or a year. Plain JVM assertions, no
 * Android.
 */
class SectionSelectDescriptionTest {

    @Test
    fun `each grouping names its own span`() {
        assertEquals(R.string.cd_select_day, sectionSelectDescriptionRes(AlbumPhotoGrouping.Day))
        assertEquals(R.string.cd_select_month, sectionSelectDescriptionRes(AlbumPhotoGrouping.Month))
        assertEquals(R.string.cd_select_year, sectionSelectDescriptionRes(AlbumPhotoGrouping.Year))
    }

    @Test
    fun `an album that never picked a grouping keeps the month label`() {
        assertEquals(R.string.cd_select_month, sectionSelectDescriptionRes(AlbumPhotoGrouping.Default))
    }
}
