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

import eu.akoos.photos.data.mlsync.MlDriveFileKind.FACES
import eu.akoos.photos.data.mlsync.MlDriveFileKind.SEARCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Coverage for [MlDriveNaming]: every snapshot name round-trips, any other name is refused, and the
 * import keeps one newest file per install and kind, leaving this install's own files out by default.
 */
class MlDriveNamingTest {

    private val own = "0f8b2c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
    private val phone = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val tablet = "16fd2706-8baf-433b-82eb-8c7fada847da"

    private fun faces(deviceId: String) = MlDriveNaming.fileName(FACES, deviceId)

    private fun search(deviceId: String) = MlDriveNaming.fileName(SEARCH, deviceId)

    private fun file(name: String, claimedMs: Long?, serverMs: Long, uid: String = name) = MlDriveFile(
        nodeUid = uid,
        name = name,
        sizeBytes = 1_024L,
        activeRevisionUid = "$uid-revision",
        serverModifiedAtMs = serverMs,
        serverCreatedAtMs = 0L,
        claimedModifiedAtMs = claimedMs,
    )

    private fun select(files: List<MlDriveFile>, includeOwnDevice: Boolean = false) =
        MlDriveNaming.selectForImport(files, ownDeviceId = own, includeOwnDevice = includeOwnDevice)

    @Test
    fun `file names follow the fixed scheme`() {
        assertEquals("faces-$phone.pfpml", faces(phone))
        assertEquals("search-$phone.pfpml", search(phone))
    }

    @Test
    fun `every kind round-trips through its file name`() {
        MlDriveFileKind.entries.forEach { kind ->
            val name = MlDriveNaming.fileName(kind, tablet)
            assertEquals(name, MlDriveFileName(kind, tablet), MlDriveNaming.parse(name))
        }
    }

    @Test
    fun `a freshly generated device id names and parses`() {
        val deviceId = UUID.randomUUID().toString()
        assertTrue(MlDriveNaming.isDeviceId(deviceId))
        assertEquals(MlDriveFileName(SEARCH, deviceId), MlDriveNaming.parse(search(deviceId)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a malformed device id cannot name a file`() {
        MlDriveNaming.fileName(FACES, "not-a-device-id")
    }

    @Test
    fun `foreign and malformed names are rejected`() {
        listOf(
            "",
            ".pfpml",
            "faces-.pfpml",
            "faces-$phone",
            "faces-$phone.pfpml.part",
            "faces-$phone.PFPML",
            "Faces-$phone.pfpml",
            "faces_$phone.pfpml",
            "people-$phone.pfpml",
            "faces-search-$phone.pfpml",
            "faces-${phone.uppercase()}.pfpml",
            "faces-${phone.replace("-", "")}.pfpml",
            "faces-$phone (1).pfpml",
            " faces-$phone.pfpml",
            "faces-$phone.pfpml ",
            "IMG_0001.jpg",
        ).forEach { name -> assertNull(name, MlDriveNaming.parse(name)) }
    }

    @Test
    fun `import takes one file per install and kind`() {
        val files = listOf(
            file(faces(phone), claimedMs = 100L, serverMs = 100L),
            file(search(phone), claimedMs = 110L, serverMs = 110L),
            file(faces(tablet), claimedMs = 200L, serverMs = 200L),
            file(search(tablet), claimedMs = 210L, serverMs = 210L),
        )
        val picked = select(files).map { it.kind to it.deviceId }.toSet()
        assertEquals(setOf(FACES to phone, SEARCH to phone, FACES to tablet, SEARCH to tablet), picked)
    }

    @Test
    fun `the newest claimed time of an install and kind wins`() {
        val older = file(faces(phone), claimedMs = 100L, serverMs = 900L, uid = "older")
        val newer = file(faces(phone), claimedMs = 200L, serverMs = 150L, uid = "newer")
        assertEquals(listOf(newer), select(listOf(older, newer)).map { it.file })
        assertEquals(listOf(newer), select(listOf(newer, older)).map { it.file })
    }

    @Test
    fun `equal claimed times are broken by the server time`() {
        val first = file(faces(phone), claimedMs = 100L, serverMs = 300L, uid = "first")
        val second = file(faces(phone), claimedMs = 100L, serverMs = 400L, uid = "second")
        assertEquals(listOf(second), select(listOf(first, second)).map { it.file })
        assertEquals(listOf(second), select(listOf(second, first)).map { it.file })
    }

    @Test
    fun `files without a claimed time are ordered by the server time`() {
        val first = file(search(tablet), claimedMs = null, serverMs = 500L, uid = "first")
        val second = file(search(tablet), claimedMs = null, serverMs = 600L, uid = "second")
        assertEquals(listOf(second), select(listOf(second, first)).map { it.file })
    }

    @Test
    fun `the own install is left out unless asked for`() {
        val files = listOf(
            file(faces(own), claimedMs = 100L, serverMs = 100L),
            file(search(own), claimedMs = 100L, serverMs = 100L),
            file(faces(phone), claimedMs = 100L, serverMs = 100L),
        )
        assertEquals(listOf(phone), select(files).map { it.deviceId })
        assertEquals(
            setOf(FACES to own, SEARCH to own, FACES to phone),
            select(files, includeOwnDevice = true).map { it.kind to it.deviceId }.toSet(),
        )
    }

    @Test
    fun `files that are not snapshots never reach the import`() {
        val files = listOf(
            file("notes.txt", claimedMs = 100L, serverMs = 100L),
            file("faces-$phone (1).pfpml", claimedMs = 900L, serverMs = 900L),
            file(search(phone), claimedMs = 100L, serverMs = 100L),
        )
        assertEquals(listOf(search(phone)), select(files).map { it.file.name })
    }

    @Test
    fun `picks come newest first`() {
        val files = listOf(
            file(faces(phone), claimedMs = 100L, serverMs = 100L),
            file(faces(tablet), claimedMs = 300L, serverMs = 300L),
            file(search(tablet), claimedMs = 200L, serverMs = 200L),
        )
        assertEquals(
            listOf(faces(tablet), search(tablet), faces(phone)),
            select(files).map { it.file.name },
        )
    }

    @Test
    fun `an empty listing imports nothing`() {
        assertTrue(select(emptyList()).isEmpty())
    }

    @Test
    fun `the device label is the tidied model name and never blank`() {
        assertEquals("Model X 2", MlDriveNaming.deviceLabel("  Model  X\t2 "))
        assertEquals("Android", MlDriveNaming.deviceLabel(null))
        assertEquals("Android", MlDriveNaming.deviceLabel("   "))
        assertEquals(40, MlDriveNaming.deviceLabel("x".repeat(100)).length)
    }
}
