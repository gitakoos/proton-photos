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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachData
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.face.ReattachManual
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject

/** File-format constants for the portable face index. */
internal const val FACE_INDEX_MAGIC = "PPFACEIDX"

/** The v1 layout: a plaintext header and an unencrypted, unsigned body. Refused on import, as only the
 *  encrypted, account-signed v2+ envelope is trusted. */
internal const val FACE_INDEX_VERSION_V1 = 1

/** The v2 layout: a plaintext header and an encrypted, account-bound body. */
internal const val FACE_INDEX_VERSION_V2 = 2

/** The v3 layout: same encrypted, account-bound envelope as v2, with a richer body that also carries
 *  each face's removed ("rejected") bit and each named person's chosen cover photo. A v1 or v2 body has
 *  neither, so [readBody] reads them only for v3 and defaults an older body to not-rejected / no-cover. */
internal const val FACE_INDEX_VERSION_V3 = 3

/** The largest ciphertext an import will allocate a buffer for. A real exported body is a few megabytes;
 *  this ceiling is far above any genuine file, so a crafted or corrupt header cannot request a huge
 *  allocation before the body is even opened. */
internal const val MAX_FACE_INDEX_CIPHER_BYTES = 512 * 1024 * 1024

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

/**
 * Whether an imported face reads as removed ("rejected") once merged, so an additive import never undoes
 * local curation. A face this device removed stays removed; a face this device named is never removed by
 * the file (the local name wins); an un-curated face takes the file's removed bit so a plain transfer
 * still carries removals across.
 */
internal fun mergeImportedRejected(localRejected: Boolean, locallyNamed: Boolean, fileRejected: Boolean): Boolean =
    localRejected || (fileRejected && !locallyNamed)

/**
 * Whether an imported person's member face may be labelled with [personName] here. A face this device
 * removed is left removed rather than reactivated, and a face already confirmed as a different name keeps
 * its own; a face with no local name, or the same name, takes the imported label.
 */
internal fun isImportLabelable(localName: String?, personName: String, locallyRejected: Boolean): Boolean =
    !locallyRejected && (localName == null || localName == personName)

/** One face as it travels in the file: geometry, quality, the raw embedding and whether the user had
 *  removed it, with no account or clustering state (those are re-applied on the importing device).
 *  [rejected] preserves a face the user dismissed ("not this person" / "not a person") so it does not
 *  come back active on the target; a v1 or v2 file carries no such bit and reads back as not rejected. */
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
    val rejected: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ParsedFace &&
        id == other.id && photoKey == other.photoKey &&
        left == other.left && top == other.top && right == other.right && bottom == other.bottom &&
        landmarks == other.landmarks && score == other.score && blur == other.blur &&
        embedding.contentEquals(other.embedding) && rejected == other.rejected

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
        result = 31 * result + rejected.hashCode()
        return result
    }
}

/** One named person's membership as it travels in the file. [cover] is the photo the user chose as the
 *  person's cover, or null for the automatic pick; a v1 or v2 file carries no cover and reads back null. */
internal data class ParsedPerson(
    val name: String,
    val members: List<String>,
    val manual: List<String>,
    val nots: List<String>,
    val cover: String?,
)

/** The decrypted contents of a face index: the faces and the named people that reference them. */
internal data class ParsedBody(
    val faces: List<ParsedFace>,
    val people: List<ParsedPerson>,
)

/**
 * Serialises [faces] then [people] into [out] in the layout of [version]. The v1 file stores this in the
 * clear and the v2/v3 files store it encrypted, so one reader ([readBody]) parses all three. A v3 body
 * additionally carries each face's [ParsedFace.rejected] bit and each person's [ParsedPerson.cover]; a
 * v1 or v2 body omits both.
 */
internal fun writeBody(out: DataOutputStream, faces: List<ParsedFace>, people: List<ParsedPerson>, version: Int) {
    val v3 = version >= FACE_INDEX_VERSION_V3
    out.writeInt(faces.size)
    for (f in faces) {
        out.writeUTF(f.id)
        out.writeUTF(f.photoKey)
        out.writeFloat(f.left); out.writeFloat(f.top); out.writeFloat(f.right); out.writeFloat(f.bottom)
        out.writeUTF(f.landmarks)
        out.writeFloat(f.score)
        out.writeFloat(f.blur ?: Float.NaN)
        if (v3) out.writeBoolean(f.rejected)
        out.writeInt(f.embedding.size)
        out.write(f.embedding)
    }
    out.writeInt(people.size)
    for (p in people) {
        out.writeUTF(p.name)
        writeStringList(out, p.members)
        writeStringList(out, p.manual)
        writeStringList(out, p.nots)
        if (v3) {
            out.writeBoolean(p.cover != null)
            if (p.cover != null) out.writeUTF(p.cover)
        }
    }
}

/** Parses a body written by [writeBody] back into faces and named people. [version] selects the layout:
 *  a pre-v3 body carries no rejected bit or cover, so those default to not-rejected and no cover. */
internal fun readBody(inp: DataInputStream, version: Int): ParsedBody {
    val v3 = version >= FACE_INDEX_VERSION_V3
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
        val rejected = if (v3) inp.readBoolean() else false
        val embedding = ByteArray(inp.readInt())
        inp.readFully(embedding)
        faces.add(ParsedFace(id, photoKey, left, top, right, bottom, landmarks, score, blur, embedding, rejected))
    }
    val peopleCount = inp.readInt()
    val people = ArrayList<ParsedPerson>(peopleCount)
    repeat(peopleCount) {
        val name = inp.readUTF()
        val members = readStringList(inp)
        val manual = readStringList(inp)
        val nots = readStringList(inp)
        val cover = if (v3 && inp.readBoolean()) inp.readUTF() else null
        people.add(ParsedPerson(name, members, manual, nots, cover))
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
 * Writes the encrypted v2/v3 envelope: a short plaintext header (magic, [version], the account tag and
 * the embedding model identity) followed by the length-prefixed ciphertext. [encrypt] wraps the already
 * serialised [bodyBytes] into an armored PGP message only the same account's key can open. The armored
 * text is stored as raw UTF-8 bytes with an explicit length rather than [DataOutputStream.writeUTF],
 * whose modified-UTF-8 form caps at 64 KB while a real body runs to megabytes.
 */
internal fun writeFaceIndexEnvelope(
    out: DataOutputStream,
    version: Int,
    accountTag: String,
    modelFile: String,
    modelSha: String,
    bodyBytes: ByteArray,
    encrypt: (ByteArray) -> String,
) {
    out.writeUTF(FACE_INDEX_MAGIC)
    out.writeInt(version)
    out.writeUTF(accountTag)
    out.writeUTF(modelFile)
    out.writeUTF(modelSha)
    val cipher = encrypt(bodyBytes).toByteArray(Charsets.UTF_8)
    out.writeInt(cipher.size)
    out.write(cipher)
}

/** The result of reading an envelope: the body to fold in, the body to reattach by geometry, or the
 *  reason it was refused. */
internal sealed interface FaceIndexRead {
    data class Body(val parsed: ParsedBody) : FaceIndexRead

    /** The account matches but the embedding model differs, so the file's vectors are not comparable
     *  here. The names it carries are still recoverable by where each face sat, so the body comes back
     *  for a geometry reattach rather than a full import. */
    data class BodyReattachOnly(val parsed: ParsedBody) : FaceIndexRead

    data object WrongAccount : FaceIndexRead
    data object Unreadable : FaceIndexRead
}

/** The outcome of opening a v2/v3 encrypted body. [Opened] is the plaintext of a body this account's
 *  key decrypted AND that verified as signed by the account. [WrongKey] is a body no local key opens (a
 *  foreign account). [Unsigned] is a body that opened but is not validly signed, which must be refused
 *  rather than trusted. */
internal sealed interface FaceIndexDecrypt {
    class Opened(val plain: ByteArray) : FaceIndexDecrypt
    data object WrongKey : FaceIndexDecrypt
    data object Unsigned : FaceIndexDecrypt
}

/**
 * Reads an envelope written by [writeFaceIndexEnvelope] and routes it. An older plaintext v1 file, a
 * foreign account tag, or a body that will not decrypt each stop the read before any parse, so a refused
 * import touches nothing.
 *
 * [decrypt] opens a v2/v3 armored PGP message: [FaceIndexDecrypt.Opened] with the verified plaintext,
 * [FaceIndexDecrypt.WrongKey] when no local key matches it (a foreign account, which physically cannot
 * decrypt), or [FaceIndexDecrypt.Unsigned] when it opened but is not validly signed. It is never invoked
 * for a header rejected on version or tag.
 */
internal fun readFaceIndex(
    inp: DataInputStream,
    localAccountTag: String,
    localModelFile: String,
    localModelSha: String,
    decrypt: (String) -> FaceIndexDecrypt,
): FaceIndexRead {
    return try {
        if (inp.readUTF() != FACE_INDEX_MAGIC) return FaceIndexRead.Unreadable
        val version = inp.readInt()
        if (version > FACE_INDEX_VERSION_V3) return FaceIndexRead.Unreadable
        // The v1 layout carries an unencrypted, unsigned body. Only the encrypted, account-signed v2+
        // envelope is trusted on import, so an older plaintext file is refused rather than read.
        if (version < FACE_INDEX_VERSION_V2) return FaceIndexRead.Unreadable
        if (inp.readUTF() != localAccountTag) return FaceIndexRead.WrongAccount

        val modelFile = inp.readUTF()
        val modelSha = inp.readUTF()
        // A different model no longer refuses the file: its vectors are unusable, but the names it
        // carries rebind by where each face sat, so the body is read for a reattach instead.
        val reattachOnly = !modelCompatible(modelFile, modelSha, localModelFile, localModelSha)
        val cipherLen = inp.readInt()
        if (cipherLen < 0 || cipherLen > MAX_FACE_INDEX_CIPHER_BYTES) return FaceIndexRead.Unreadable
        val cipher = ByteArray(cipherLen)
        inp.readFully(cipher)
        // A key that opens nothing is the same refusal as a foreign account; a body that opens but is
        // not validly signed by the account is refused as unreadable rather than trusted. Never a
        // silent empty import.
        val plain = when (val opened = decrypt(String(cipher, Charsets.UTF_8))) {
            is FaceIndexDecrypt.Opened -> opened.plain
            FaceIndexDecrypt.WrongKey -> return FaceIndexRead.WrongAccount
            FaceIndexDecrypt.Unsigned -> return FaceIndexRead.Unreadable
        }
        val parsed = readBody(DataInputStream(ByteArrayInputStream(plain)), version)
        if (reattachOnly) FaceIndexRead.BodyReattachOnly(parsed) else FaceIndexRead.Body(parsed)
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

    /** The file was built with a different embedding model, so its vectors were not imported, but the
     *  names it carries were staged to be put back on this device's own faces as the library is scanned. */
    data class ReattachScheduled(val people: Int) : FaceIndexImportOutcome

    /** The file belongs to a different Proton account, whose link ids and key do not match here. */
    data object WrongAccount : FaceIndexImportOutcome

    /** Not a face index, an unsupported newer version, or a body that would not decrypt or parse. */
    data object Unreadable : FaceIndexImportOutcome
}

/**
 * Writes the account's portable face index to [output] as an encrypted v3 file: a short plaintext
 * header (magic, version, a non-reversible account tag and the embedding model identity) wraps a body
 * that only the same Proton account's address key can open. The body holds every face whose photo is a
 * cloud link (embedding, box, landmarks, score, blur, and whether the user removed it), plus each named
 * person's member faces, manual photo attachments, "not this person" marks and chosen cover photo.
 * Device-only faces are skipped. The heavy detection and embedding work is what this preserves, so
 * another device on the same account can reuse it without recomputing; clustering is re-derived cheaply
 * on import.
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
    private val personCoverDao: PersonCoverDao,
) {
    /** Number of faces written, or -1 when signed out. */
    suspend operator fun invoke(output: OutputStream): Int = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first() ?: return@withContext -1
        val account = userId.id
        val faces = faceDao.allFacesByScoreDesc(account).filter { isPortableFaceKey(it.photoKey) }
        val portableIds = faces.mapTo(HashSet()) { it.id }
        val named = personDao.namedPeopleForUser(account).filter { !it.displayName.isNullOrBlank() }
        val notByName = notPersonDao.allForUser(account).groupBy({ it.personName }, { it.faceId })
        val coverByName = personCoverDao.allForUser(account).associate { it.personName to it.photoKey }

        val parsedFaces = faces.map { f ->
            ParsedFace(
                id = f.id, photoKey = f.photoKey,
                left = f.left, top = f.top, right = f.right, bottom = f.bottom,
                landmarks = f.landmarks, score = f.score, blur = f.blur, embedding = f.embedding,
                rejected = f.rejected,
            )
        }
        val parsedPeople = named.map { person ->
            val name = person.displayName!!
            val members = faceDao.faceIdsForPerson(account, person.id).filter { it in portableIds }
            val manual = personManualPhotoDao.photoKeysForNameList(account, name).filter { isPortableFaceKey(it) }
            val nots = (notByName[name] ?: emptyList()).filter { it in portableIds }
            val cover = coverByName[name]?.takeIf { isPortableFaceKey(it) }
            ParsedPerson(name, members, manual, nots, cover)
        }

        val bodyBytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { writeBody(it, parsedFaces, parsedPeople, FACE_INDEX_VERSION_V3) }
            buffer.toByteArray()
        }

        val key = cryptoHelper.getAddressSigningKey(userId)
        DataOutputStream(BufferedOutputStream(output)).use { out ->
            writeFaceIndexEnvelope(
                out = out,
                version = FACE_INDEX_VERSION_V3,
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
 * account. The encrypted v2/v3 forms are accepted and the older plaintext v1 form is refused, and each
 * is bound to this account: an index whose tag or key belongs to a different account is refused before any row
 * is touched, and an encrypted body that is not validly signed by the account is refused as unreadable
 * (see [FaceIndexImportOutcome]). On a same-model read the faces are upserted (the deterministic id
 * dedups a photo already scanned here), carrying each face's removed bit, each named person's members
 * are re-confirmed under its name, and manual attachments, rejections and chosen covers are restored; a
 * face this device has already confirmed as a different person keeps its own name rather than being
 * relabelled. A caller reclusters afterwards so the confirmed faces form named people. On a
 * different-model read the vectors would not compare, so instead of a refusal the names it carries are
 * staged to be reattached to this device's own faces by geometry as the library is scanned. Faces whose
 * photos are not in this account's library simply never resolve to a visible person, so an inert row is
 * harmless.
 */
class ImportFaceIndexUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cryptoHelper: DriveCryptoHelper,
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(input: InputStream): FaceIndexImportOutcome = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext FaceIndexImportOutcome.Unreadable
        val account = userId.id

        // The address key is needed only to open the encrypted body; unlock it up front and tolerate a
        // failure, so a file whose key cannot be obtained falls through to the same "not this account"
        // refusal as a foreign key rather than throwing.
        val key = runCatching { cryptoHelper.getAddressSigningKey(userId) }.getOrNull()
        // Verify against every enabled address key, not just the one resolved for signing, so a body this
        // account legitimately signed still verifies after a primary-address change (matching how the app
        // verifies its own uploaded content); fall back to the signing key's own public key.
        val verifyKeys = runCatching { cryptoHelper.getOwnPublicKeysArmored(userId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: key?.let { listOf(it.publicKeyArmored) }
            ?: emptyList()
        val decrypt: (String) -> FaceIndexDecrypt = { armored ->
            val k = key
            if (k == null) {
                FaceIndexDecrypt.WrongKey
            } else {
                // The body is encrypted AND signed to the account's own address key on export, so a valid
                // signature is required here: a body that opens but does not verify is refused rather than
                // trusted, and one no key opens is the foreign-account refusal.
                val opened = cryptoHelper.decryptAndVerifyData(
                    armored, listOf(k.unlockedKeyBytes), verifyKeys,
                )
                when {
                    opened == null -> FaceIndexDecrypt.WrongKey
                    opened.verified -> FaceIndexDecrypt.Opened(opened.data)
                    else -> FaceIndexDecrypt.Unsigned
                }
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
                FaceIndexRead.Unreadable -> FaceIndexImportOutcome.Unreadable
                is FaceIndexRead.BodyReattachOnly -> {
                    // Different model: its vectors cannot compare here, so stage the names to be put back
                    // on this device's own faces by geometry as the library is scanned, rather than
                    // importing embeddings that would never match. The indexer consumes this once its
                    // walk has drained. Covers are keyed by name and independent of the model, so they are
                    // written now and take effect once the names reattach.
                    val parsed = read.parsed
                    FaceReattachSnapshot.write(reattachFile(), buildReattachData(parsed))
                    val covers = coverRows(account, parsed)
                    if (covers.isNotEmpty()) {
                        appDatabase.withTransaction { covers.forEach { personCoverDao.set(it) } }
                    }
                    markModelVersionCurrent()
                    FaceIndexImportOutcome.ReattachScheduled(parsed.people.size)
                }
                is FaceIndexRead.Body -> {
                    val parsed = read.parsed
                    appDatabase.withTransaction {
                        // A face's id is deterministic (photoKey#index), so an imported face can land on one
                        // this device already curated. Read the local confirmations and removals before the
                        // upsert (which would blank them) so local curation is preserved below.
                        val importedIds = parsed.faces.map { it.id }
                        val localNames = faceDao.manualNamesForIds(importedIds)
                            .associate { it.id to it.manualName }
                        val locallyRejected = faceDao.rejectedIdsAmong(importedIds).toHashSet()
                        val faces = parsed.faces.map { pf ->
                            FaceEntity(
                                id = pf.id, userId = account, photoKey = pf.photoKey,
                                left = pf.left, top = pf.top, right = pf.right, bottom = pf.bottom,
                                landmarks = pf.landmarks, embedding = pf.embedding, personId = null,
                                score = pf.score, blur = pf.blur,
                                // A local removal is never undone, and a locally named face is never removed
                                // by the file; an un-curated face takes the file's removed bit.
                                rejected = mergeImportedRejected(
                                    localRejected = pf.id in locallyRejected,
                                    locallyNamed = localNames[pf.id] != null,
                                    fileRejected = pf.rejected,
                                ),
                                // Keep this device's own confirmation rather than blanking it on upsert; a
                                // face with none takes the imported label below.
                                manualName = localNames[pf.id],
                            )
                        }
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
                            // Do not relabel a face this device already curated: a local removal stays
                            // removed and a different confirmed name is kept, so only faces with no local
                            // name or the same name are labelled.
                            val labelable = p.members.filter {
                                isImportLabelable(localNames[it], p.name, it in locallyRejected)
                            }
                            if (labelable.isNotEmpty()) faceDao.labelFacesByIds(labelable, p.name)
                            if (p.manual.isNotEmpty()) {
                                personManualPhotoDao.add(p.manual.map { PersonManualPhotoEntity(account, p.name, it) })
                            }
                            if (p.nots.isNotEmpty()) {
                                notPersonDao.add(p.nots.map { NotPersonEntity(account, p.name, it) })
                            }
                            p.cover?.let { personCoverDao.set(PersonCoverEntity(account, p.name, it)) }
                        }
                    }
                    markModelVersionCurrent()
                    FaceIndexImportOutcome.Success(parsed.faces.size, parsed.people.size)
                }
            }
        }
    }

    /** The chosen-cover rows an import carries, one per named person that has a cover in the file. */
    private fun coverRows(account: String, parsed: ParsedBody): List<PersonCoverEntity> =
        parsed.people.mapNotNull { p -> p.cover?.let { PersonCoverEntity(account, p.name, it) } }

    /**
     * Record that the just-imported embeddings belong to the current recognition model, in the same key
     * the indexer's migration reads. Without this a fresh device has no marker, so the first indexing
     * walk would treat the import as a model change and clear every imported face (and clobber a pending
     * reattach snapshot) before anything used it.
     */
    private suspend fun markModelVersionCurrent() {
        context.settingsDataStore.edit { it[SettingsKeys.FACE_MODEL_VERSION_KEY] = FACE_MODEL_VERSION }
    }

    private fun reattachFile(): File =
        FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    /**
     * Turns a decrypted body into the model-independent labels the reattach consumes: each named
     * person's member faces and rejections carry their box (looked up from the body's face list), so
     * they can be rebound by geometry, and the manual attachments carry their photo. A member or
     * rejection whose face is missing from the body is skipped.
     */
    private fun buildReattachData(parsed: ParsedBody): FaceReattachData {
        val faceById = parsed.faces.associateBy { it.id }
        val members = ArrayList<ReattachLabel>()
        val manual = ArrayList<ReattachManual>()
        val nots = ArrayList<ReattachLabel>()
        for (p in parsed.people) {
            for (fid in p.members) {
                val f = faceById[fid] ?: continue
                members.add(ReattachLabel(f.id, f.photoKey, f.left, f.top, f.right, f.bottom, p.name))
            }
            for (photoKey in p.manual) manual.add(ReattachManual(p.name, photoKey))
            for (fid in p.nots) {
                val f = faceById[fid] ?: continue
                nots.add(ReattachLabel(f.id, f.photoKey, f.left, f.top, f.right, f.bottom, p.name))
            }
        }
        return FaceReattachData(members, manual, nots)
    }
}
