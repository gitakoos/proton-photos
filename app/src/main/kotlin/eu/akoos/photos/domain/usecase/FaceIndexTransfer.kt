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

import androidx.room.withTransaction
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceModelAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject

/** File-format constants for the portable face index. */
internal const val FACE_INDEX_MAGIC = "PPFACEIDX"

/** The v1 layout: a plaintext header and an unencrypted body, accepted on import for back-compat. */
internal const val FACE_INDEX_VERSION_V1 = 1

/** The v2 layout: a plaintext header and an encrypted, account-bound body. */
internal const val FACE_INDEX_VERSION_V2 = 2

/** A face is portable only if its photo is addressed by a Proton Drive link id, which is the same on
 *  every device signed into the account. A device `content://` MediaStore uri means nothing on another
 *  phone, so those faces are left out of a transfer. */
internal fun isPortableFaceKey(photoKey: String): Boolean = !photoKey.startsWith("content://")

/** A short, non-reversible tag of the account, written into the file so an import can bind the index
 *  to the account it was produced on (whose link ids match this library). */
internal fun accountTag(userId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * Whether an index built against the model identified by [headerFile]/[headerSha] holds vectors this
 * build can compare against the local [localFile]/[localSha] model. A different file name is always
 * incompatible; the same file name is compatible unless both sides carry a digest and the two differ
 * (a blank digest on either side, the unpinned state, cannot prove a mismatch, so it is allowed).
 */
internal fun modelCompatible(
    headerFile: String,
    headerSha: String,
    localFile: String,
    localSha: String,
): Boolean = headerFile == localFile &&
    !(headerSha.isNotBlank() && localSha.isNotBlank() && !headerSha.equals(localSha, ignoreCase = true))

/** One face as it travels in the file: geometry, quality and the raw embedding, with no account or
 *  clustering state (those are re-applied on the importing device). */
internal data class ParsedFace(
    val id: String,
    val photoKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val landmarks: String,
    val score: Float,
    val blur: Float?,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ParsedFace &&
        id == other.id && photoKey == other.photoKey &&
        left == other.left && top == other.top && right == other.right && bottom == other.bottom &&
        landmarks == other.landmarks && score == other.score && blur == other.blur &&
        embedding.contentEquals(other.embedding)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + photoKey.hashCode()
        result = 31 * result + left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + landmarks.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + (blur?.hashCode() ?: 0)
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/** One named person's membership as it travels in the file. */
internal data class ParsedPerson(
    val name: String,
    val members: List<String>,
    val manual: List<String>,
    val nots: List<String>,
)

/** The decrypted contents of a face index: the faces and the named people that reference them. */
internal data class ParsedBody(
    val faces: List<ParsedFace>,
    val people: List<ParsedPerson>,
)

/**
 * Serialises [faces] then [people] into [out]. The v1 file stores this layout in the clear and the
 * v2 file stores it encrypted, so one reader ([readBody]) parses both.
 */
internal fun writeBody(out: DataOutputStream, faces: List<ParsedFace>, people: List<ParsedPerson>) {
    out.writeInt(faces.size)
    for (f in faces) {
        out.writeUTF(f.id)
        out.writeUTF(f.photoKey)
        out.writeFloat(f.left); out.writeFloat(f.top); out.writeFloat(f.right); out.writeFloat(f.bottom)
        out.writeUTF(f.landmarks)
        out.writeFloat(f.score)
        out.writeFloat(f.blur ?: Float.NaN)
        out.writeInt(f.embedding.size)
        out.write(f.embedding)
    }
    out.writeInt(people.size)
    for (p in people) {
        out.writeUTF(p.name)
        writeStringList(out, p.members)
        writeStringList(out, p.manual)
        writeStringList(out, p.nots)
    }
}

/** Parses a body written by [writeBody] back into faces and named people. */
internal fun readBody(inp: DataInputStream): ParsedBody {
    val faceCount = inp.readInt()
    val faces = ArrayList<ParsedFace>(faceCount)
    repeat(faceCount) {
        val id = inp.readUTF()
        val photoKey = inp.readUTF()
        val left = inp.readFloat(); val top = inp.readFloat()
        val right = inp.readFloat(); val bottom = inp.readFloat()
        val landmarks = inp.readUTF()
        val score = inp.readFloat()
        val blur = inp.readFloat().let { if (it.isNaN()) null else it }
        val embedding = ByteArray(inp.readInt())
        inp.readFully(embedding)
        faces.add(ParsedFace(id, photoKey, left, top, right, bottom, landmarks, score, blur, embedding))
    }
    val peopleCount = inp.readInt()
    val people = ArrayList<ParsedPerson>(peopleCount)
    repeat(peopleCount) {
        people.add(ParsedPerson(inp.readUTF(), readStringList(inp), readStringList(inp), readStringList(inp)))
    }
    return ParsedBody(faces, people)
}

private fun writeStringList(out: DataOutputStream, values: List<String>) {
    out.writeInt(values.size)
    for (v in values) out.writeUTF(v)
}

private fun readStringList(inp: DataInputStream): List<String> {
    val n = inp.readInt()
    return List(n) { inp.readUTF() }
}

/**
 * Writes the encrypted v2 envelope: a short plaintext header (magic, version, the account tag and the
 * embedding model identity) followed by the length-prefixed ciphertext. [encrypt] wraps the already
 * serialised [bodyBytes] into an armored PGP message only the same account's key can open. The armored
 * text is stored as raw UTF-8 bytes with an explicit length rather than [DataOutputStream.writeUTF],
 * whose modified-UTF-8 form caps at 64 KB while a real body runs to megabytes.
 */
internal fun writeFaceIndexV2(
    out: DataOutputStream,
    accountTag: String,
    modelFile: String,
    modelSha: String,
    bodyBytes: ByteArray,
    encrypt: (ByteArray) -> String,
) {
    out.writeUTF(FACE_INDEX_MAGIC)
    out.writeInt(FACE_INDEX_VERSION_V2)
    out.writeUTF(accountTag)
    out.writeUTF(modelFile)
    out.writeUTF(modelSha)
    val cipher = encrypt(bodyBytes).toByteArray(Charsets.UTF_8)
    out.writeInt(cipher.size)
    out.write(cipher)
}

/** The result of reading an envelope: either the body to fold in, or the reason it was refused. */
internal sealed interface FaceIndexRead {
    data class Body(val parsed: ParsedBody) : FaceIndexRead
    data object WrongAccount : FaceIndexRead
    data object WrongModel : FaceIndexRead
    data object Unreadable : FaceIndexRead
}

/**
 * Reads an envelope written by [writeFaceIndexV2] (or a legacy v1 plaintext file) and routes it. A
 * foreign account tag, an incompatible model, or a body that will not decrypt each stop the read
 * before any parse, so a refused import touches nothing.
 *
 * [decrypt] turns a v2 armored PGP message back into the plaintext body, or null when no local key
 * matches it (a foreign account, which physically cannot decrypt). It is never invoked for a v1 file
 * or for a header rejected on tag or model.
 */
internal fun readFaceIndex(
    inp: DataInputStream,
    localAccountTag: String,
    localModelFile: String,
    localModelSha: String,
    decrypt: (String) -> ByteArray?,
): FaceIndexRead {
    return try {
        if (inp.readUTF() != FACE_INDEX_MAGIC) return FaceIndexRead.Unreadable
        val version = inp.readInt()
        if (version > FACE_INDEX_VERSION_V2) return FaceIndexRead.Unreadable
        if (inp.readUTF() != localAccountTag) return FaceIndexRead.WrongAccount

        val body: DataInputStream = if (version == FACE_INDEX_VERSION_V2) {
            val modelFile = inp.readUTF()
            val modelSha = inp.readUTF()
            if (!modelCompatible(modelFile, modelSha, localModelFile, localModelSha)) return FaceIndexRead.WrongModel
            val cipherLen = inp.readInt()
            if (cipherLen < 0) return FaceIndexRead.Unreadable
            val cipher = ByteArray(cipherLen)
            inp.readFully(cipher)
            // A matching tag but no key that opens the message means a defensive fall-through to the same
            // refusal as a foreign account, never a silent empty import.
            val plain = decrypt(String(cipher, Charsets.UTF_8)) ?: return FaceIndexRead.WrongAccount
            DataInputStream(ByteArrayInputStream(plain))
        } else {
            inp
        }
        FaceIndexRead.Body(readBody(body))
    } catch (e: IOException) {
        FaceIndexRead.Unreadable
    }
}

/**
 * Why an import ended, so the caller can tell the user exactly what happened. Every refusal folds in
 * no data; only [Success] has touched the account.
 */
sealed interface FaceIndexImportOutcome {
    /** Faces and named people were merged into the account. */
    data class Success(val faces: Int, val people: Int) : FaceIndexImportOutcome

    /** The file belongs to a different Proton account, whose link ids and key do not match here. */
    data object WrongAccount : FaceIndexImportOutcome

    /** The file was built with a different face embedding model, so its vectors are not comparable. */
    data object WrongModel : FaceIndexImportOutcome

    /** Not a face index, an unsupported newer version, or a body that would not decrypt or parse. */
    data object Unreadable : FaceIndexImportOutcome
}

/**
 * Writes the account's portable face index to [output] as an encrypted v2 file: a short plaintext
 * header (magic, version, a non-reversible account tag and the embedding model identity) wraps a body
 * that only the same Proton account's address key can open. The body holds every face whose photo is a
 * cloud link (embedding, box, landmarks, score, blur), plus each named person's member faces, manual
 * photo attachments and "not this person" marks. Device-only faces are skipped. The heavy detection
 * and embedding work is what this preserves, so another device on the same account can reuse it without
 * recomputing; clustering is re-derived cheaply on import.
 *
 * The embeddings are biometric-adjacent, so the body is encrypted to the account and the file is only
 * ever produced on the user's explicit request and handed to the user to store; nothing here uploads it.
 */
class ExportFaceIndexUseCase @Inject constructor(
    private val accountManager: AccountManager,
    private val cryptoHelper: DriveCryptoHelper,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
) {
    /** Number of faces written, or -1 when signed out. */
    suspend operator fun invoke(output: OutputStream): Int = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first() ?: return@withContext -1
        val account = userId.id
        val faces = faceDao.allFacesByScoreDesc(account).filter { isPortableFaceKey(it.photoKey) }
        val portableIds = faces.mapTo(HashSet()) { it.id }
        val named = personDao.namedPeopleForUser(account).filter { !it.displayName.isNullOrBlank() }
        val notByName = notPersonDao.allForUser(account).groupBy({ it.personName }, { it.faceId })

        val parsedFaces = faces.map { f ->
            ParsedFace(
                id = f.id, photoKey = f.photoKey,
                left = f.left, top = f.top, right = f.right, bottom = f.bottom,
                landmarks = f.landmarks, score = f.score, blur = f.blur, embedding = f.embedding,
            )
        }
        val parsedPeople = named.map { person ->
            val name = person.displayName!!
            val members = faceDao.faceIdsForPerson(account, person.id).filter { it in portableIds }
            val manual = personManualPhotoDao.photoKeysForNameList(account, name).filter { isPortableFaceKey(it) }
            val nots = (notByName[name] ?: emptyList()).filter { it in portableIds }
            ParsedPerson(name, members, manual, nots)
        }

        val bodyBytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { writeBody(it, parsedFaces, parsedPeople) }
            buffer.toByteArray()
        }

        val key = cryptoHelper.getAddressSigningKey(userId)
        DataOutputStream(BufferedOutputStream(output)).use { out ->
            writeFaceIndexV2(
                out = out,
                accountTag = accountTag(account),
                modelFile = FaceModelAssets.EMBED_MODEL.fileName,
                modelSha = FaceModelAssets.EMBED_MODEL.sha256,
                bodyBytes = bodyBytes,
            ) { plain ->
                cryptoHelper.encryptAndSignDataToPgpMessage(plain, key.publicKeyArmored, key.unlockedKeyBytes)
            }
            out.flush()
        }
        faces.size
    }
}

/**
 * Reads a face index written by [ExportFaceIndexUseCase] from [input] and folds it into the current
 * account. Both the encrypted v2 form and the legacy v1 plaintext form are accepted, and both are bound
 * to this account: an index whose tag or key belongs to a different account, or whose embedding model
 * differs, is refused before any row is touched (see [FaceIndexImportOutcome]). On a clean read faces
 * are upserted (the deterministic id dedups a photo already scanned here), each named person's members
 * are re-confirmed under its name, and manual attachments and rejections are restored. A caller
 * reclusters afterwards so the confirmed faces form named people. Faces whose photos are not in this
 * account's library simply never resolve to a visible person, so an inert row is harmless.
 */
class ImportFaceIndexUseCase @Inject constructor(
    private val accountManager: AccountManager,
    private val cryptoHelper: DriveCryptoHelper,
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(input: InputStream): FaceIndexImportOutcome = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext FaceIndexImportOutcome.Unreadable
        val account = userId.id

        // The address key is needed only to open a v2 body; unlock it up front and tolerate a failure,
        // so a v1 file (which carries no ciphertext) still imports, and a v2 file whose key cannot be
        // obtained falls through to the same "not this account" refusal as a foreign key.
        val key = runCatching { cryptoHelper.getAddressSigningKey(userId) }.getOrNull()
        val decrypt: (String) -> ByteArray? = { armored ->
            key?.let {
                cryptoHelper.decryptAndVerifyData(armored, listOf(it.unlockedKeyBytes), listOf(it.publicKeyArmored))?.data
            }
        }

        DataInputStream(BufferedInputStream(input)).use { inp ->
            when (val read = readFaceIndex(
                inp = inp,
                localAccountTag = accountTag(account),
                localModelFile = FaceModelAssets.EMBED_MODEL.fileName,
                localModelSha = FaceModelAssets.EMBED_MODEL.sha256,
                decrypt = decrypt,
            )) {
                FaceIndexRead.WrongAccount -> FaceIndexImportOutcome.WrongAccount
                FaceIndexRead.WrongModel -> FaceIndexImportOutcome.WrongModel
                FaceIndexRead.Unreadable -> FaceIndexImportOutcome.Unreadable
                is FaceIndexRead.Body -> {
                    val parsed = read.parsed
                    val faces = parsed.faces.map { pf ->
                        FaceEntity(
                            id = pf.id, userId = account, photoKey = pf.photoKey,
                            left = pf.left, top = pf.top, right = pf.right, bottom = pf.bottom,
                            landmarks = pf.landmarks, embedding = pf.embedding, personId = null,
                            score = pf.score, blur = pf.blur, rejected = false, manualName = null,
                        )
                    }
                    appDatabase.withTransaction {
                        if (faces.isNotEmpty()) faceDao.upsert(faces)
                        // Mark every imported photo as already scanned, so the background indexer skips it
                        // rather than re-detecting and overwriting the imported faces (which would wipe the
                        // name labels below and force a full rescan). The export holds every face on a cloud
                        // photo, so nothing is missed by not scanning it again here.
                        val scannedKeys = faces.mapTo(HashSet()) { it.photoKey }
                        if (scannedKeys.isNotEmpty()) {
                            faceScanDao.upsert(scannedKeys.map { FaceScanEntity(account, it) })
                        }
                        for (p in parsed.people) {
                            if (p.members.isNotEmpty()) faceDao.labelFacesByIds(p.members, p.name)
                            if (p.manual.isNotEmpty()) {
                                personManualPhotoDao.add(p.manual.map { PersonManualPhotoEntity(account, p.name, it) })
                            }
                            if (p.nots.isNotEmpty()) {
                                notPersonDao.add(p.nots.map { NotPersonEntity(account, p.name, it) })
                            }
                        }
                    }
                    FaceIndexImportOutcome.Success(parsed.faces.size, parsed.people.size)
                }
            }
        }
    }
}
