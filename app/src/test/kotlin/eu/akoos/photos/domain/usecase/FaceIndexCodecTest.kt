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
 * Coverage for the portable face-index codec: the pure body serialiser, the encrypted v3 envelope, the
 * read routing that binds an index to its account and model, the refusal of the older unauthenticated v1
 * layout, and the v2 body still read with its absent removed bit and cover defaulted. The pure merge
 * decisions that keep a local removal or name from being undone on import are covered directly.
 *
 * The real crypto (gopenpgp) is native and absent from a JVM unit test, so it is stood in by a trivial
 * reversible transform: encrypt = base64, decrypt-and-verify = its inverse wrapped as [FaceIndexDecrypt.Opened],
 * a key that opens nothing ([FaceIndexDecrypt.WrongKey]) for a foreign account, and a body that opens but
 * does not verify ([FaceIndexDecrypt.Unsigned]). The envelope and routing carry no crypto of their own, so
 * this proves every branch without a device.
 *
 * No Android, no coroutines: plain JVM assertions on bytes and values.
 */
class FaceIndexCodecTest {

    private val localModelFile = FaceModelAssets.EMBED_MODEL.fileName
    private val localModelSha = FaceModelAssets.EMBED_MODEL.sha256
    private val localTag = accountTag("account-local")
    private val foreignTag = accountTag("account-foreign")

    private val encrypt: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val decrypt: (String) -> FaceIndexDecrypt = { FaceIndexDecrypt.Opened(Base64.getDecoder().decode(it)) }
    private val decryptWrongKey: (String) -> FaceIndexDecrypt = { FaceIndexDecrypt.WrongKey }
    private val decryptUnsigned: (String) -> FaceIndexDecrypt = { FaceIndexDecrypt.Unsigned }

    private fun sampleFaces(): List<ParsedFace> = listOf(
        ParsedFace("f1", "link-a", 0.10f, 0.20f, 0.30f, 0.40f, "lm-1", 0.99f, 0.5f, byteArrayOf(1, 2, 3, 4), rejected = false),
        ParsedFace("f2", "link-b", 0.50f, 0.60f, 0.70f, 0.80f, "lm-2", 0.88f, null, byteArrayOf(9, 8, 7), rejected = true),
    )

    private fun samplePeople(): List<ParsedPerson> = listOf(
        ParsedPerson("Ann", members = listOf("f1"), manual = listOf("link-c"), nots = listOf("f2"), cover = "link-a"),
        ParsedPerson("Bob", members = listOf("f2"), manual = emptyList(), nots = emptyList(), cover = null),
    )

    /** What a pre-v3 body (no removed bit, no cover) reads back as: every face not rejected, every cover null. */
    private fun flatFaces(): List<ParsedFace> = sampleFaces().map { it.copy(rejected = false) }
    private fun flatPeople(): List<ParsedPerson> = samplePeople().map { it.copy(cover = null) }

    private fun bodyBytes(faces: List<ParsedFace>, people: List<ParsedPerson>, version: Int): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { writeBody(it, faces, people, version) }
            buffer.toByteArray()
        }

    private fun writeEnvelope(
        version: Int,
        tag: String,
        modelFile: String = localModelFile,
        modelSha: String = localModelSha,
        faces: List<ParsedFace> = sampleFaces(),
        people: List<ParsedPerson> = samplePeople(),
        encryptWith: (ByteArray) -> String = encrypt,
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use {
            writeFaceIndexEnvelope(it, version, tag, modelFile, modelSha, bodyBytes(faces, people, version), encryptWith)
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
            writeBody(out, faces, people, FACE_INDEX_VERSION_V1)
        }
        buffer.toByteArray()
    }

    private fun read(
        bytes: ByteArray,
        tag: String = localTag,
        modelFile: String = localModelFile,
        modelSha: String = localModelSha,
        decryptWith: (String) -> FaceIndexDecrypt = decrypt,
    ): FaceIndexRead = DataInputStream(ByteArrayInputStream(bytes)).use {
        readFaceIndex(it, tag, modelFile, modelSha, decryptWith)
    }

    @Test
    fun `body writer and reader round-trip faces and people`() {
        val faces = sampleFaces()
        val people = samplePeople()
        val parsed = DataInputStream(ByteArrayInputStream(bodyBytes(faces, people, FACE_INDEX_VERSION_V3)))
            .use { readBody(it, FACE_INDEX_VERSION_V3) }
        assertEquals(faces, parsed.faces)
        assertEquals(people, parsed.people)
    }

    @Test
    fun `a v3 envelope round-trips the body with the removed bit and the chosen cover`() {
        val faces = sampleFaces()
        val people = samplePeople()
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag, faces = faces, people = people))
        assertTrue(read is FaceIndexRead.Body)
        val body = (read as FaceIndexRead.Body).parsed
        assertEquals(faces, body.faces)
        assertEquals(people, body.people)
        assertTrue(body.faces.first { it.id == "f2" }.rejected)
        assertEquals("link-a", body.people.first { it.name == "Ann" }.cover)
    }

    @Test
    fun `a foreign account tag is refused before any decrypt`() {
        var decryptCalls = 0
        val counting: (String) -> FaceIndexDecrypt = { decryptCalls++; decrypt(it) }
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, foreignTag), decryptWith = counting)
        assertEquals(FaceIndexRead.WrongAccount, read)
        assertEquals(0, decryptCalls)
    }

    @Test
    fun `a matching tag whose body will not decrypt is refused as the wrong account`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag), decryptWith = decryptWrongKey)
        assertEquals(FaceIndexRead.WrongAccount, read)
    }

    @Test
    fun `a matching tag whose body is not validly signed is refused as unreadable`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag), decryptWith = decryptUnsigned)
        assertEquals(FaceIndexRead.Unreadable, read)
    }

    @Test
    fun `a different model file name is routed to reattach`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag, modelFile = "some_other_model.onnx", modelSha = ""))
        assertTrue(read is FaceIndexRead.BodyReattachOnly)
    }

    @Test
    fun `the same model file with two present differing digests is routed to reattach`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag, modelFile = localModelFile, modelSha = "aaaa"), modelSha = "bbbb")
        assertTrue(read is FaceIndexRead.BodyReattachOnly)
    }

    @Test
    fun `the same model file with a blank digest on one side is accepted`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V3, localTag, modelFile = localModelFile, modelSha = ""), modelSha = "bbbb")
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
    fun `an import never un-rejects a locally removed or locally named face`() {
        // A face this device removed stays removed even when the file does not reject it.
        assertTrue(mergeImportedRejected(localRejected = true, locallyNamed = false, fileRejected = false))
        // A face this device named is never removed by the file: the local name wins.
        assertFalse(mergeImportedRejected(localRejected = false, locallyNamed = true, fileRejected = true))
        // An un-curated face takes the file's removed bit, so a plain transfer still carries removals.
        assertTrue(mergeImportedRejected(localRejected = false, locallyNamed = false, fileRejected = true))
        assertFalse(mergeImportedRejected(localRejected = false, locallyNamed = false, fileRejected = false))
    }

    @Test
    fun `local curation decides whether an imported member is labelable`() {
        // A locally removed face is never relabelled by the import.
        assertFalse(isImportLabelable(localName = null, personName = "Ann", locallyRejected = true))
        // An un-curated face is labelable.
        assertTrue(isImportLabelable(localName = null, personName = "Ann", locallyRejected = false))
        // A face already confirmed as the same name is labelable.
        assertTrue(isImportLabelable(localName = "Ann", personName = "Ann", locallyRejected = false))
        // A face confirmed as a different name keeps its own.
        assertFalse(isImportLabelable(localName = "Bob", personName = "Ann", locallyRejected = false))
    }

    @Test
    fun `a v2 envelope with a matching tag parses and defaults the new fields`() {
        val read = read(writeEnvelope(FACE_INDEX_VERSION_V2, localTag))
        assertTrue(read is FaceIndexRead.Body)
        val body = (read as FaceIndexRead.Body).parsed
        // A v2 body carries no removed bit or cover, so they default to not-rejected and no cover.
        assertEquals(flatFaces(), body.faces)
        assertEquals(flatPeople(), body.people)
    }

    @Test
    fun `a v1 plaintext file is refused as unreadable`() {
        // The v1 body is unencrypted and unsigned, so it is refused rather than trusted on import.
        assertEquals(FaceIndexRead.Unreadable, read(writeV1(localTag)))
    }

    @Test
    fun `a v1 plaintext file is refused before the account tag is read`() {
        // The version refusal precedes the tag check, so even a foreign-tag v1 reads back unreadable.
        assertEquals(FaceIndexRead.Unreadable, read(writeV1(foreignTag)))
    }

    @Test
    fun `a file with the wrong magic is unreadable`() {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF("NOTAFACEIDX")
                out.writeInt(FACE_INDEX_VERSION_V3)
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
                out.writeInt(FACE_INDEX_VERSION_V3 + 1)
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

    @Test
    fun `a declared ciphertext length over the cap is refused before decrypt`() {
        // A header claiming a ciphertext larger than the cap is refused before any buffer is allocated
        // or the body is opened, so a crafted length cannot force a huge allocation.
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(FACE_INDEX_MAGIC)
                out.writeInt(FACE_INDEX_VERSION_V3)
                out.writeUTF(localTag)
                out.writeUTF(localModelFile)
                out.writeUTF(localModelSha)
                out.writeInt(MAX_FACE_INDEX_CIPHER_BYTES + 1)
            }
            buffer.toByteArray()
        }
        var decryptCalls = 0
        val counting: (String) -> FaceIndexDecrypt = { decryptCalls++; decrypt(it) }
        assertEquals(FaceIndexRead.Unreadable, read(bytes, decryptWith = counting))
        assertEquals(0, decryptCalls)
    }
}
