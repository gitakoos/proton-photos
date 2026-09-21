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

package eu.akoos.photos.data.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceReaperTest {

    private val deviceLive = "content://media/external/images/media/100"
    private val deviceTrashed = "content://media/external/images/media/200"
    private val deviceGone = "content://media/external/images/media/300"
    private val cloudLive = "linkAAA"
    private val cloudTrashed = "linkBBB"
    private val cloudGone = "linkCCC"
    private val vault = "file:///data/user/0/eu.akoos.photos/files/vault/xyz.jpg"

    private fun reap(
        keys: Collection<String>,
        cloudTrustworthy: Boolean = true,
        deviceTrustworthy: Boolean = true,
    ) = FaceReaper.facesToReap(
        faceKeys = keys,
        liveCloudLinkIds = setOf(cloudLive),
        cloudTrashLinkIds = setOf(cloudTrashed),
        cloudTrustworthy = cloudTrustworthy,
        liveDeviceUris = setOf(deviceLive),
        deviceTrashUris = setOf(deviceTrashed),
        deviceTrustworthy = deviceTrustworthy,
    )

    @Test fun `gone keys are reaped, live and trashed are kept`() {
        val out = reap(listOf(deviceLive, deviceTrashed, deviceGone, cloudLive, cloudTrashed, cloudGone))
        assertEquals(setOf(deviceGone, cloudGone), out)
    }

    @Test fun `a trashed photo keeps its faces (restorable)`() {
        assertTrue(reap(listOf(deviceTrashed)).isEmpty())
        assertTrue(reap(listOf(cloudTrashed)).isEmpty())
    }

    @Test fun `a vault file uri is never reaped`() {
        assertTrue(reap(listOf(vault)).isEmpty())
        // Even alongside gone keys, the vault path is left untouched.
        assertEquals(setOf(deviceGone), reap(listOf(vault, deviceGone)))
    }

    @Test fun `an untrustworthy device scan never reaps a device face`() {
        assertTrue(reap(listOf(deviceGone), deviceTrustworthy = false).isEmpty())
    }

    @Test fun `an untrustworthy cloud fetch never reaps a cloud face`() {
        assertTrue(reap(listOf(cloudGone), cloudTrustworthy = false).isEmpty())
    }

    @Test fun `keyspace trust is independent`() {
        // Device untrustworthy, cloud trustworthy: only the cloud face is reaped.
        assertEquals(
            setOf(cloudGone),
            reap(listOf(deviceGone, cloudGone), cloudTrustworthy = true, deviceTrustworthy = false),
        )
    }

    @Test fun `empty input reaps nothing`() {
        assertTrue(reap(emptyList()).isEmpty())
    }
}
