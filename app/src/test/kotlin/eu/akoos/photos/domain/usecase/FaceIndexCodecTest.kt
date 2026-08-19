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

import eu.akoos.photos.data.face.FaceModelAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Coverage for the portable face-index codec: the pure body serialiser, the encrypted v2 envelope, and
 * the read routing that binds an index to its account and model.
 *
 * The real crypto (gopenpgp) is native and absent from a JVM unit test, so it is stood in by a trivial
 * reversible transform: encrypt = base64, decrypt = its inverse, and a decrypt that returns null to
 * stand for a foreign account whose key opens nothing. The envelope and routing carry no crypto of
 * their own, so this proves every branch without a device.
 *
 * No Android, no coroutines: plain JVM assertions on bytes and values.
 */
class FaceIndexCodecTest {

    private val localModelFile = FaceModelAssets.EMBED_MODEL.fileName
    private val localModelSha = FaceModelAssets.EMBED_MODEL.sha256
    private val localTag = accountTag("account-local")
    private val foreignTag = accountTag("account-foreign")

    private val encrypt: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val decrypt: (String) -> ByteArray? = { Base64.getDecoder().decode(it) }
    private val decryptNothing: (String) -> ByteArray? = { null }

    private fun sampleFaces(): List<ParsedFace> = listOf(
        ParsedFace("f1", "link-a", 0.10f, 0.20f, 0.30f, 0.40f, "lm-1", 0.99f, 0.5f, byteArrayOf(1, 2, 3, 4)),
        ParsedFace("f2", "link-b", 0.50f, 0.60f, 0.70f, 0.80f, "lm-2", 0.88f, null, byteArrayOf(9, 8, 7)),
    )

    private fun samplePeople(): List<ParsedPerson> = listOf(
        ParsedPerson("Ann", members = listOf("f1"), manual = listOf("link-c"), nots = listOf("f2")),
        ParsedPerson("Bob", members = listOf("f2"), manual = emptyList(), nots = emptyList()),
    )

    private fun bodyBytes(faces: List<ParsedFace>, people: List<ParsedPerson>): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { writeBody(it, faces, people) }
            buffer.toByteArray()
        }

    private fun writeV2(
        tag: String,
        modelFile: String = localModelFile,
        modelSha: String = localModelSha,
        faces: List<ParsedFace> = sampleFaces(),
        people: List<ParsedPerson> = samplePeople(),
        encryptWith: (ByteArray) -> String = encrypt,
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use {
            writeFaceIndexV2(it, tag, modelFile, modelSha, bodyBytes(faces, people), encryptWith)
        }
        buffer.toByteArray()
    }

    private fun writeV1(
        tag: String,
        faces: List<ParsedFace> = sampleFaces(),
        people: List<ParsedPerson> = samplePeople(),
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeUTF(FACE_INDEX_MAGIC)
            out.writeInt(FACE_INDEX_VERSION_V1)
            out.writeUTF(tag)
            writeBody(out, faces, people)
        }
        buffer.toByteArray()
    }

    private fun read(
        bytes: ByteArray,
        tag: String = localTag,
        modelFile: String = localModelFile,
        modelSha: String = localModelSha,
        decryptWith: (String) -> ByteArray? = decrypt,
    ): FaceIndexRead = DataInputStream(ByteArrayInputStream(bytes)).use {
        readFaceIndex(it, tag, modelFile, modelSha, decryptWith)
    }

    @Test
    fun `body writer and reader round-trip faces and people`() {
        val faces = sampleFaces()
        val people = samplePeople()
        val parsed = DataInputStream(ByteArrayInputStream(bodyBytes(faces, people))).use { readBody(it) }
        assertEquals(faces, parsed.faces)
        assertEquals(people, parsed.people)
    }

    @Test
    fun `a v2 envelope round-trips to the body with correct counts`() {
        val faces = sampleFaces()
        val people = samplePeople()
        val read = read(writeV2(localTag, faces = faces, people = people))
        assertTrue(read is FaceIndexRead.Body)
        val body = (read as FaceIndexRead.Body).parsed
        assertEquals(faces.size, body.faces.size)
        assertEquals(people.size, body.people.size)
        assertEquals(faces, body.faces)
        assertEquals(people, body.people)
    }

    @Test
    fun `a foreign account tag is refused before any decrypt`() {
        var decryptCalls = 0
        val counting: (String) -> ByteArray? = { decryptCalls++; decrypt(it) }
        val read = read(writeV2(foreignTag), decryptWith = counting)
        assertEquals(FaceIndexRead.WrongAccount, read)
        assertEquals(0, decryptCalls)
    }

    @Test
    fun `a matching tag whose body will not decrypt is refused as the wrong account`() {
        val read = read(writeV2(localTag), decryptWith = decryptNothing)
        assertEquals(FaceIndexRead.WrongAccount, read)
    }

    @Test
    fun `a different model file name is refused`() {
        val read = read(writeV2(localTag, modelFile = "some_other_model.onnx", modelSha = ""))
        assertEquals(FaceIndexRead.WrongModel, read)
    }

    @Test
    fun `the same model file with two present differing digests is refused`() {
        val read = read(writeV2(localTag, modelFile = localModelFile, modelSha = "aaaa"), modelSha = "bbbb")
        assertEquals(FaceIndexRead.WrongModel, read)
    }

    @Test
    fun `the same model file with a blank digest on one side is accepted`() {
        val read = read(writeV2(localTag, modelFile = localModelFile, modelSha = ""), modelSha = "bbbb")
        assertTrue(read is FaceIndexRead.Body)
    }

    @Test
    fun `the model compatibility rule`() {
        assertTrue(modelCompatible("m.onnx", "", "m.onnx", ""))
        assertTrue(modelCompatible("m.onnx", "AB12", "m.onnx", ""))
        assertTrue(modelCompatible("m.onnx", "", "m.onnx", "CD34"))
        assertTrue(modelCompatible("m.onnx", "abcd", "m.onnx", "ABCD"))
        assertFalse(modelCompatible("m.onnx", "AB12", "m.onnx", "CD34"))
        assertFalse(modelCompatible("m.onnx", "", "n.onnx", ""))
    }

    @Test
    fun `a v1 plaintext file with a matching tag parses`() {
        val faces = sampleFaces()
        val people = samplePeople()
        val read = read(writeV1(localTag, faces, people))
        assertTrue(read is FaceIndexRead.Body)
        val body = (read as FaceIndexRead.Body).parsed
        assertEquals(faces, body.faces)
        assertEquals(people, body.people)
    }

    @Test
    fun `a v1 plaintext file with a foreign tag is refused as the wrong account`() {
        assertEquals(FaceIndexRead.WrongAccount, read(writeV1(foreignTag)))
    }

    @Test
    fun `a file with the wrong magic is unreadable`() {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF("NOTAFACEIDX")
                out.writeInt(FACE_INDEX_VERSION_V2)
                out.writeUTF(localTag)
            }
            buffer.toByteArray()
        }
        assertEquals(FaceIndexRead.Unreadable, read(bytes))
    }

    @Test
    fun `a newer unsupported version is unreadable`() {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(FACE_INDEX_MAGIC)
                out.writeInt(FACE_INDEX_VERSION_V2 + 1)
                out.writeUTF(localTag)
            }
            buffer.toByteArray()
        }
        assertEquals(FaceIndexRead.Unreadable, read(bytes))
    }

    @Test
    fun `an empty file is unreadable`() {
        assertEquals(FaceIndexRead.Unreadable, read(ByteArray(0)))
    }
}
