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

package eu.akoos.photos.facefile

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.domain.usecase.FACE_INDEX_VERSION_V3
import eu.akoos.photos.domain.usecase.ParsedFace
import eu.akoos.photos.domain.usecase.ParsedPerson
import eu.akoos.photos.domain.usecase.accountTag
import eu.akoos.photos.domain.usecase.isPortableFaceKey
import eu.akoos.photos.domain.usecase.writeBody
import eu.akoos.photos.domain.usecase.writeFaceIndexEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * DEBUG ONLY (debug source set, never in a release). Checks the face file import against a real face file:
 *
 *  - EXPORT writes the account's faces as the 2.5.0 "Export face index" did, byte for byte the same v3
 *    envelope, to Downloads/photosforproton-faces.ppfi, and saves a snapshot of the face data beside it.
 *  - COMPARE reads the face data now and reports how it differs from that snapshot.
 *
 *  - SNAPSHOT saves that snapshot alone, and REGROUP runs a forced regroup, so a regroup can be checked apart
 *    from an import.
 *
 * Trigger:  adb shell am broadcast -a eu.akoos.photos.FACE_FILE_CHECK --es step EXPORT|SNAPSHOT|REGROUP|COMPARE -p <applicationId>
 */
class FaceFileCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val step = intent.getStringExtra("step") ?: "COMPARE"
        val appContext = context.applicationContext
        // Not goAsync: on a big library the step outlasts the broadcast timeout and the process is
        // killed as an ANR, so the receiver returns at once and the work carries on in its own thread.
        Thread {
            try {
                runBlocking { FaceFileCheck(appContext).run(step) }
            } catch (t: Throwable) {
                Log.e(TAG, "$step failed: ${t.message}", t)
            }
        }.start()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaceFileCheckDeps {
    fun accountManager(): AccountManager
    fun cryptoHelper(): DriveCryptoHelper
    fun faceDao(): FaceDao
    fun faceScanDao(): FaceScanDao
    fun personDao(): PersonDao
    fun manualPhotoDao(): PersonManualPhotoDao
    fun notPersonDao(): NotPersonDao
    fun personCoverDao(): PersonCoverDao
    fun faceIndexingScheduler(): FaceIndexingScheduler
}

private const val TAG = "FaceFileCheck"

@RequiresApi(Build.VERSION_CODES.Q)
private class FaceFileCheck(private val context: Context) {
    private val deps = EntryPointAccessors.fromApplication(context, FaceFileCheckDeps::class.java)
    private val snapshotFile = File(context.filesDir, "face_file_check_snapshot.json")

    suspend fun run(step: String) {
        val userId = deps.accountManager().getPrimaryUserId().first() ?: error("signed out")
        when (step) {
            "SNAPSHOT" -> {
                val snapshot = snapshot(userId.id)
                snapshotFile.writeText(snapshot.toString(2))
                Log.i(TAG, "SNAPSHOT ${summary(snapshot)}")
            }
            "REGROUP" -> {
                Log.i(TAG, "REGROUP start")
                val ran = deps.faceIndexingScheduler().regroupNow(userId)
                Log.i(TAG, "REGROUP done ran=$ran")
            }
            "EXPORT" -> {
                val faces = exportV3(userId.id, userId)
                val snapshot = snapshot(userId.id)
                snapshotFile.writeText(snapshot.toString(2))
                Log.i(TAG, "EXPORT wrote $faces faces; snapshot ${summary(snapshot)}")
            }
            else -> {
                val before = JSONObject(snapshotFile.readText())
                val now = snapshot(userId.id)
                Log.i(TAG, "COMPARE before ${summary(before)}")
                Log.i(TAG, "COMPARE now    ${summary(now)}")
                compare(before, now).forEach { Log.i(TAG, "COMPARE $it") }
            }
        }
    }

    /** The 2.5.0 ExportFaceIndexUseCase, unchanged but for writing to Downloads. Returns the face count. */
    private suspend fun exportV3(account: String, userId: me.proton.core.domain.entity.UserId): Int {
        Log.i(TAG, "EXPORT reading faces")
        val faces = deps.faceDao().allFacesByScoreDesc(account).filter { isPortableFaceKey(it.photoKey) }
        Log.i(TAG, "EXPORT ${faces.size} portable faces")
        val portableIds = faces.mapTo(HashSet()) { it.id }
        val named = deps.personDao().namedPeopleForUser(account).filter { !it.displayName.isNullOrBlank() }
        val notByName = deps.notPersonDao().allForUser(account).groupBy({ it.personName }, { it.faceId })
        val coverByName = deps.personCoverDao().allForUser(account).associate { it.personName to it.photoKey }
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
            val members = deps.faceDao().faceIdsForPerson(account, person.id).filter { it in portableIds }
            val manual = deps.manualPhotoDao().photoKeysForNameList(account, name).filter { isPortableFaceKey(it) }
            val nots = (notByName[name] ?: emptyList()).filter { it in portableIds }
            val cover = coverByName[name]?.takeIf { isPortableFaceKey(it) }
            ParsedPerson(name, members, manual, nots, cover)
        }
        val bodyBytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { writeBody(it, parsedFaces, parsedPeople, FACE_INDEX_VERSION_V3) }
            buffer.toByteArray()
        }
        Log.i(TAG, "EXPORT body ${bodyBytes.size} bytes, unlocking the signing key")
        val key = deps.cryptoHelper().getAddressSigningKey(userId)
        Log.i(TAG, "EXPORT key ready, writing")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "photosforproton-faces.ppfi")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create the file in Downloads")
        context.contentResolver.openOutputStream(uri)!!.use { raw ->
            DataOutputStream(BufferedOutputStream(raw)).use { out ->
                writeFaceIndexEnvelope(
                    out = out,
                    version = FACE_INDEX_VERSION_V3,
                    accountTag = accountTag(account),
                    modelFile = FaceModelAssets.EMBED_MODEL.fileName,
                    modelSha = FaceModelAssets.EMBED_MODEL.sha256,
                    bodyBytes = bodyBytes,
                ) { plain ->
                    deps.cryptoHelper().encryptAndSignDataToPgpMessage(plain, key.publicKeyArmored, key.unlockedKeyBytes)
                }
                out.flush()
            }
        }
        return faces.size
    }

    /** The portable face data that a face file carries, keyed so two snapshots compare field by field. */
    private suspend fun snapshot(account: String): JSONObject {
        val people = deps.personDao().allForUser(account).associateBy { it.id }
        val faces = JSONObject()
        for (f in deps.faceDao().allFacesByScoreDesc(account)) {
            if (!isPortableFaceKey(f.photoKey)) continue
            faces.put(f.id, JSONObject().apply {
                put("photoKey", f.photoKey)
                put("rejected", f.rejected)
                put("person", f.personId?.let { people[it]?.displayName } ?: JSONObject.NULL)
                put("box", "%.4f,%.4f,%.4f,%.4f".format(f.left, f.top, f.right, f.bottom))
                put("embeddingBytes", f.embedding.size)
            })
        }
        val named = JSONObject()
        for (p in deps.personDao().namedPeopleForUser(account)) {
            val name = p.displayName ?: continue
            named.put(name, JSONObject().apply {
                put("faces", JSONArray(deps.faceDao().faceIdsForPerson(account, p.id).filter { faces.has(it) }.sorted()))
                put("manual", JSONArray(deps.manualPhotoDao().photoKeysForNameList(account, name).filter { isPortableFaceKey(it) }.sorted()))
            })
        }
        val nots = JSONArray(deps.notPersonDao().allForUser(account).map { "${it.personName}|${it.faceId}" }.sorted())
        val covers = JSONArray(deps.personCoverDao().allForUser(account).map { "${it.personName}|${it.photoKey}" }.sorted())
        val scanned = deps.faceScanDao().scannedKeysForUser(account).count { isPortableFaceKey(it) }
        return JSONObject().apply {
            put("faces", faces)
            put("named", named)
            put("notPerson", nots)
            put("covers", covers)
            put("scannedCloudPhotos", scanned)
        }
    }

    private fun summary(s: JSONObject): String {
        val named = s.getJSONObject("named")
        val names = named.keys().asSequence().sorted().joinToString { "$it=${named.getJSONObject(it).getJSONArray("faces").length()}" }
        return "faces=${s.getJSONObject("faces").length()} named=[$names] notPerson=${s.getJSONArray("notPerson").length()} " +
            "covers=${s.getJSONArray("covers").length()} scannedCloudPhotos=${s.getInt("scannedCloudPhotos")}"
    }

    /** Every difference between [before] and [now] that a restore should have put back, or "identical". */
    private fun compare(before: JSONObject, now: JSONObject): List<String> {
        val out = ArrayList<String>()
        val bf = before.getJSONObject("faces")
        val nf = now.getJSONObject("faces")
        for (id in bf.keys()) {
            if (!nf.has(id)) { out += "face missing $id"; continue }
            val a = bf.getJSONObject(id)
            val b = nf.getJSONObject(id)
            for (field in listOf("photoKey", "rejected", "person", "box", "embeddingBytes")) {
                if (a.get(field).toString() != b.get(field).toString()) out += "face $id $field ${a.get(field)} -> ${b.get(field)}"
            }
        }
        for (id in nf.keys()) if (!bf.has(id)) out += "face extra $id"
        val bn = before.getJSONObject("named")
        val nn = now.getJSONObject("named")
        for (name in bn.keys()) {
            if (!nn.has(name)) { out += "person missing $name"; continue }
            for (field in listOf("faces", "manual")) {
                val a = bn.getJSONObject(name).getJSONArray(field).toString()
                val b = nn.getJSONObject(name).getJSONArray(field).toString()
                if (a != b) out += "person $name $field $a -> $b"
            }
        }
        for (name in nn.keys()) if (!bn.has(name)) out += "person extra $name"
        for (field in listOf("notPerson", "covers")) {
            val a = before.getJSONArray(field).toString()
            val b = now.getJSONArray(field).toString()
            if (a != b) out += "$field $a -> $b"
        }
        return out.ifEmpty { listOf("identical") }
    }
}
