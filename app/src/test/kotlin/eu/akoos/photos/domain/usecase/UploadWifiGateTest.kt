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

import eu.akoos.photos.domain.entity.QueueSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #102 rule: under Wi-Fi-only, an auto-queued photo waits for Wi-Fi, while an explicitly picked
 * upload rides mobile data. This is what stops a single manual "back up now" from dragging the whole
 * auto backlog onto cellular (the reported symptom: one photo backed up, all of the day's photos went
 * with it).
 */
class UploadWifiGateTest {

    @Test
    fun `an auto-folder photo waits for Wi-Fi off Wi-Fi`() {
        assertTrue(uploadDefersForWifiOnly(QueueSource.AUTO_FOLDER, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `a photo with no queue source waits for Wi-Fi off Wi-Fi`() {
        assertTrue(uploadDefersForWifiOnly(null, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `a manual pick rides mobile even off Wi-Fi`() {
        assertFalse(uploadDefersForWifiOnly(QueueSource.MANUAL, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `an album-add rides mobile even off Wi-Fi`() {
        assertFalse(uploadDefersForWifiOnly(QueueSource.ALBUM_ADD, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `nothing waits while on Wi-Fi`() {
        assertFalse(uploadDefersForWifiOnly(QueueSource.AUTO_FOLDER, wifiOnly = true, onWifi = true))
    }

    @Test
    fun `nothing waits when the Wi-Fi-only setting is off`() {
        assertFalse(uploadDefersForWifiOnly(QueueSource.AUTO_FOLDER, wifiOnly = false, onWifi = false))
    }
}
