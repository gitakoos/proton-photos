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

import eu.akoos.photos.domain.usecase.ReattachFace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules of a face snapshot import: when a restore asks Merge or Replace and what an import that
 * cannot ask does, which photo keys a snapshot carries, which snapshots import whole and which by name
 * only, which rows a Merge adds, how a snapshot face lands on the face this device holds (its own
 * confirmed names and removals winning), how the two are paired by box overlap, and how the index's
 * landmark string and face id map onto the snapshot's fields and back.
 */
class FaceSnapshotMergeTest {

    private fun header(
        modelVersion: Int = 7,
        modelSha: String = "abc123",
        dim: Int = 512,
        clusterParams: Int = 1,
    ) = MlSnapshotHeader.Faces(
        deviceId = "device",
        deviceLabel = "Phone",
        createdAtMillis = 0L,
        faceModelVersion = modelVersion,
        faceModelSha256 = modelSha,
        clusterParamsVersion = clusterParams,
        embeddingDim = dim,
        keyCount = 0,
        personCount = 0,
        faceCount = 0,
        manualPhotoCount = 0,
        notPersonCount = 0,
        coverCount = 0,
        scanMarkerCount = 0,
    )

    private fun fit(header: MlSnapshotHeader.Faces, localSha: String = "abc123") =
        faceSnapshotFit(header, localModelVersion = 7, localModelSha = localSha, localEmbeddingDim = 512)

    private fun snapshot(personName: String? = null, confirmedName: String? = null, rejected: Boolean = false) =
        SnapshotFaceCuration(personName, confirmedName, rejected)

    private fun face(id: String, photoKey: String, left: Float, top: Float, right: Float, bottom: Float) =
        ReattachFace(id, photoKey, left, top, right, bottom)

    @Test
    fun `a restore replaces unasked only on a fresh device`() {
        assertFalse(restoreNeedsChoice(holdsCloudFaces = false, reattachPending = false))
        // Faces of cloud photos ask whether any of them is named or not: a Replace clears them, their scan
        // markers and their "not this person" marks.
        assertTrue(restoreNeedsChoice(holdsCloudFaces = true, reattachPending = false))
        // So do names still waiting to be put back, which a Replace lets go of.
        assertTrue(restoreNeedsChoice(holdsCloudFaces = false, reattachPending = true))
        assertTrue(restoreNeedsChoice(holdsCloudFaces = true, reattachPending = true))
    }

    @Test
    fun `an import that cannot ask merges unless the device is fresh`() {
        assertEquals(FaceSnapshotMode.Replace, defaultRestoreMode(needsChoice = false))
        assertEquals(FaceSnapshotMode.Merge, defaultRestoreMode(needsChoice = true))
    }

    @Test
    fun `the same pipeline and model imports whole`() {
        assertEquals(FaceSnapshotFit.Full, fit(header()))
        // The digest compares without regard to case, and a blank digest on either side proves no mismatch.
        assertEquals(FaceSnapshotFit.Full, fit(header(modelSha = "ABC123")))
        assertEquals(FaceSnapshotFit.Full, fit(header(modelSha = "")))
        assertEquals(FaceSnapshotFit.Full, fit(header(), localSha = ""))
    }

    @Test
    fun `another pipeline, model or width imports names only`() {
        assertEquals(FaceSnapshotFit.NamesOnly, fit(header(modelVersion = 6)))
        assertEquals(FaceSnapshotFit.NamesOnly, fit(header(modelVersion = 8)))
        assertEquals(FaceSnapshotFit.NamesOnly, fit(header(modelSha = "def456")))
        assertEquals(FaceSnapshotFit.NamesOnly, fit(header(dim = 128)))
    }

    @Test
    fun `other clustering parameters ask for a regroup`() {
        assertFalse(snapshotNeedsRegroup(header(clusterParams = 1), localClusterParamsVersion = 1))
        assertTrue(snapshotNeedsRegroup(header(clusterParams = 0), localClusterParamsVersion = 1))
        assertTrue(snapshotNeedsRegroup(header(clusterParams = 2), localClusterParamsVersion = 1))
    }

    @Test
    fun `a merge adds rows only for photos this device has not scanned`() {
        assertTrue(importsFaceRows(FaceSnapshotMode.Merge, scannedHere = false))
        assertFalse(importsFaceRows(FaceSnapshotMode.Merge, scannedHere = true))
        assertTrue(importsFaceRows(FaceSnapshotMode.Replace, scannedHere = false))
        assertTrue(importsFaceRows(FaceSnapshotMode.Replace, scannedHere = true))
    }

    @Test
    fun `a name confirmed here wins over everything the snapshot says`() {
        val local = LocalFaceCuration(confirmedName = "Ann")
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(confirmedName = "Anna")))
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(personName = "Anna")))
        // A face confirmed here is never removed by the snapshot either.
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(rejected = true)))
    }

    @Test
    fun `a face removed here stays removed`() {
        val local = LocalFaceCuration(rejected = true)
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(confirmedName = "Ann")))
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(personName = "Ann")))
    }

    @Test
    fun `a removal in the snapshot removes a face this device has not confirmed`() {
        assertEquals(FaceMergeChange.Remove, mergeFaceCuration(LocalFaceCuration(), snapshot(rejected = true)))
        assertEquals(
            FaceMergeChange.Remove,
            mergeFaceCuration(LocalFaceCuration(groupedName = "Ann"), snapshot(rejected = true)),
        )
    }

    @Test
    fun `a name confirmed in the snapshot is confirmed here, over an automatic grouping`() {
        assertEquals(FaceMergeChange.Confirm("Ann"), mergeFaceCuration(LocalFaceCuration(), snapshot(confirmedName = "Ann")))
        assertEquals(
            FaceMergeChange.Confirm("Ann"),
            mergeFaceCuration(LocalFaceCuration(groupedName = "Bob"), snapshot(personName = "Bob", confirmedName = "Ann")),
        )
    }

    @Test
    fun `the snapshot's grouping fills only a face this device has not grouped under a named person`() {
        assertEquals(FaceMergeChange.Confirm("Ann"), mergeFaceCuration(LocalFaceCuration(), snapshot(personName = "Ann")))
        assertEquals(
            FaceMergeChange.Keep,
            mergeFaceCuration(LocalFaceCuration(groupedName = "Bob"), snapshot(personName = "Ann")),
        )
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(LocalFaceCuration(), snapshot()))
    }

    @Test
    fun `a name this device marked the face as not being is never put on it`() {
        val local = LocalFaceCuration(blockedNames = setOf("Ann"))
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(confirmedName = "Ann")))
        assertEquals(FaceMergeChange.Keep, mergeFaceCuration(local, snapshot(personName = "Ann")))
        assertEquals(FaceMergeChange.Confirm("Cleo"), mergeFaceCuration(local, snapshot(confirmedName = "Cleo")))
    }

    @Test
    fun `a not-this-person mark never lands on a face confirmed as that name`() {
        assertFalse(notPersonMarkApplies(localConfirmedName = "Ann", markName = "Ann"))
        assertTrue(notPersonMarkApplies(localConfirmedName = "Bob", markName = "Ann"))
        assertTrue(notPersonMarkApplies(localConfirmedName = null, markName = "Ann"))
    }

    @Test
    fun `a merge keeps this device's own cover choice`() {
        assertTrue(coverApplies(FaceSnapshotMode.Merge, localHasCover = false))
        assertFalse(coverApplies(FaceSnapshotMode.Merge, localHasCover = true))
        assertTrue(coverApplies(FaceSnapshotMode.Replace, localHasCover = true))
    }

    @Test
    fun `every scan marker imports and a high-resolution flag is only raised`() {
        // A face-free photo's marker arrives like any other, so it is not scanned here again.
        assertEquals(ScanMarkerChange.Insert(false), scanMarkerChange(localMarker = false, snapshotHiRes = false))
        assertEquals(ScanMarkerChange.Insert(true), scanMarkerChange(localMarker = false, snapshotHiRes = true))
        assertEquals(ScanMarkerChange.RaiseHiRes, scanMarkerChange(localMarker = true, snapshotHiRes = true))
        assertEquals(ScanMarkerChange.Keep, scanMarkerChange(localMarker = true, snapshotHiRes = false))
    }

    @Test
    fun `faces pair across devices only where their boxes overlap by half`() {
        val snapshotFaces = listOf(
            face("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.40f),
            face("p1#1", "p1", 0.60f, 0.10f, 0.80f, 0.40f),
            face("p2#0", "p2", 0.10f, 0.10f, 0.30f, 0.40f),
        )
        val localFaces = listOf(
            // The two faces of p1, detected here in the other order.
            face("p1#0", "p1", 0.61f, 0.10f, 0.80f, 0.40f),
            face("p1#1", "p1", 0.11f, 0.11f, 0.30f, 0.40f),
            // Same id and photo as the snapshot's lone p2 face but somewhere else entirely.
            face("p2#0", "p2", 0.60f, 0.60f, 0.90f, 0.90f),
        )
        val pairs = matchSnapshotFaces(snapshotFaces, localFaces)
        assertEquals(mapOf("p1#0" to "p1#1", "p1#1" to "p1#0"), pairs)
    }

    @Test
    fun `faces on different photos never pair`() {
        val pairs = matchSnapshotFaces(
            listOf(face("p1#0", "p1", 0.1f, 0.1f, 0.3f, 0.4f)),
            listOf(face("p9#0", "p9", 0.1f, 0.1f, 0.3f, 0.4f)),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `an overlap under half pairs nothing`() {
        val pairs = matchSnapshotFaces(
            listOf(face("p1#0", "p1", 0.0f, 0.0f, 0.4f, 0.4f)),
            listOf(face("p1#3", "p1", 0.2f, 0.2f, 0.6f, 0.6f)),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `a landmark string the indexer wrote survives the trip character for character`() {
        val encoded = listOf(
            12.5f to 40.25f, -0.001f to 1.0E-5f, 3.4028235E38f to 0.0f, 1024.0f to 777.77f, 5.0f to 6.0f,
        ).joinToString(";") { (x, y) -> "$x,$y" }
        val points = landmarkPoints(encoded)
        assertEquals(10, points.size)
        assertEquals(12.5f, points[0])
        assertEquals(40.25f, points[1])
        assertEquals(encoded, landmarkString(points))
    }

    @Test
    fun `a damaged landmark string carries no points rather than shifted ones`() {
        assertArrayEquals(FloatArray(0), landmarkPoints(""), 0f)
        assertArrayEquals(FloatArray(0), landmarkPoints("1.0,2.0;oops,4.0"), 0f)
        assertArrayEquals(FloatArray(0), landmarkPoints("1.0,2.0;3.0"), 0f)
        assertArrayEquals(FloatArray(0), landmarkPoints("1.0,2.0,3.0"), 0f)
        assertEquals("", landmarkString(FloatArray(0)))
    }

    @Test
    fun `a face id splits into its photo and detection index`() {
        assertEquals(3, faceIndexIn("linkA#3", "linkA"))
        assertEquals(12, faceIndexIn("link#A#12", "link#A"))
        assertNull(faceIndexIn("linkA#03", "linkA"))
        assertNull(faceIndexIn("linkA#-1", "linkA"))
        assertNull(faceIndexIn("linkA#", "linkA"))
        assertNull(faceIndexIn("linkB#1", "linkA"))
        assertNull(faceIndexIn("linkA_1", "linkA"))
        assertEquals("linkA" to 4, faceIdParts("linkA#4"))
        assertNull(faceIdParts("linkA"))
        assertNull(faceIdParts("#4"))
    }

    @Test
    fun `only cloud photo keys and real names travel`() {
        assertTrue(carriesPhotoKey("AbC-123_xyz=="))
        assertFalse(carriesPhotoKey("content://media/external/images/media/42"))
        // A photo in the hidden area is a file path on this device, and never leaves it.
        assertFalse(carriesPhotoKey("file:///data/user/0/eu.akoos.photos/files/hidden/IMG_0042.jpg"))
        assertFalse(carriesPhotoKey(""))
        assertEquals("Ann", snapshotName("Ann"))
        assertNull(snapshotName("  "))
        assertNull(snapshotName(null))
        assertNull(snapshotName("x".repeat(MlSnapshotFormat.MAX_STRING_BYTES + 1)))
    }
}
