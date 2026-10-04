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

package eu.akoos.photos.data.mlsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LabelDriveNamingTest {

    private fun file(name: String, claimedMs: Long?, serverMs: Long, uid: String = name) = MlDriveFile(
        nodeUid = uid,
        name = name,
        sizeBytes = 10L,
        activeRevisionUid = "rev-$uid",
        serverModifiedAtMs = serverMs,
        serverCreatedAtMs = serverMs,
        claimedModifiedAtMs = claimedMs,
    )

    @Test
    fun `a file name carries the install id and parses back to it`() {
        val name = LabelDriveNaming.fileName(DEVICE_A)

        assertEquals("labels-$DEVICE_A.pfplb", name)
        assertEquals(DEVICE_A, LabelDriveNaming.parse(name))
    }

    @Test
    fun `a name that is not an install's label file does not parse`() {
        assertNull(LabelDriveNaming.parse("labels-not-a-device.pfplb"))
        assertNull(LabelDriveNaming.parse("labels-$DEVICE_A.pfpml"))
        assertNull(LabelDriveNaming.parse("faces-$DEVICE_A.pfpml"))
        assertNull(LabelDriveNaming.parse("notes.txt"))
    }

    @Test
    fun `naming a file for something that is not an install id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { LabelDriveNaming.fileName("device") }
    }

    @Test
    fun `the labels folder only accepts its own file names as an install's`() {
        assertEquals(DEVICE_A, DriveDataFolder.LABELS.deviceIdOf(LabelDriveNaming.fileName(DEVICE_A)))
        assertNull(DriveDataFolder.LABELS.deviceIdOf(MlDriveNaming.fileName(MlDriveFileKind.FACES, DEVICE_A)))
        assertEquals(DEVICE_A, DriveDataFolder.ML.deviceIdOf(MlDriveNaming.fileName(MlDriveFileKind.FACES, DEVICE_A)))
        assertNull(DriveDataFolder.ML.deviceIdOf(LabelDriveNaming.fileName(DEVICE_A)))
    }

    @Test
    fun `the data folders sit side by side under the app folder`() {
        assertEquals("ML data", DriveDataFolder.ML.folderName)
        assertEquals("Labels", DriveDataFolder.LABELS.folderName)
    }

    @Test
    fun `a restore reads the newest file of every install, this one's included`() {
        val files = listOf(
            file(LabelDriveNaming.fileName(DEVICE_A), claimedMs = 10L, serverMs = 10L, uid = "a-old"),
            file(LabelDriveNaming.fileName(DEVICE_A), claimedMs = 30L, serverMs = 30L, uid = "a-new"),
            file(LabelDriveNaming.fileName(DEVICE_B), claimedMs = 20L, serverMs = 20L, uid = "b"),
            file("stray.txt", claimedMs = 99L, serverMs = 99L),
            file(MlDriveNaming.fileName(MlDriveFileKind.SEARCH, DEVICE_A), claimedMs = 99L, serverMs = 99L),
        )

        val picked = LabelDriveNaming.selectForRestore(files).associate { it.deviceId to it.file.nodeUid }

        assertEquals(mapOf(DEVICE_A to "a-new", DEVICE_B to "b"), picked)
    }

    @Test
    fun `equal claimed times fall back to the server time`() {
        val files = listOf(
            file(LabelDriveNaming.fileName(DEVICE_A), claimedMs = 5L, serverMs = 1L, uid = "early"),
            file(LabelDriveNaming.fileName(DEVICE_A), claimedMs = 5L, serverMs = 2L, uid = "late"),
        )

        assertEquals("late", LabelDriveNaming.selectForRestore(files).single().file.nodeUid)
    }

    @Test
    fun `a listing with no label files restores nothing`() {
        assertEquals(emptyList<LabelDriveFile>(), LabelDriveNaming.selectForRestore(emptyList()))
    }

    private companion object {
        const val DEVICE_A = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val DEVICE_B = "7d1e9a40-2222-4b4b-8d8d-abcdefabcdef"
    }
}
