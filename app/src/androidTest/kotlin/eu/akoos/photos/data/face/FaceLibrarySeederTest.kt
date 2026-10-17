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

import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.util.MlWalkState
import eu.akoos.photos.util.MlWalkStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Writes a synthetic big face library into the installed app's own database, so the grouping of a
 * 100k+ library can be reproduced on a device that holds no such real library. It is a seeder, not an
 * assertion: run it by hand with `adb shell am instrument -w -e class ...FaceLibrarySeederTest`, then
 * open the app and read what the real scheduler does with it.
 *
 * The faces ride on photos the face walk has already marked scanned (`face_scan`), so the walk finds
 * nothing left to detect and goes straight to grouping. Every embedding is synthetic: identities are
 * random unit vectors, a clear face is its identity plus modest noise (two of them land near 0.64
 * cosine, above the 0.58 join floor), a weak face carries much more noise and a quality score under the
 * confident floor, and a share of faces match nobody. Identity sizes follow a power law, like a real
 * library of a few big families and a long tail.
 *
 * Arguments (all optional): `faces` (120000), `identities` (2500), `state`, `grouped` (0.38), `named` (0)
 * and `seed`. `named` puts a confirmed name on a third of the clear faces of that many of the biggest
 * identities, the way faces look after a restore or an import whose names wait for their groups. `state` is `empty` (no people, no centroid cache: the library after an ML data restore),
 * `fresh` (the biggest identities grouped, a centroid cache that sums to the grouped faces), `stale`
 * (as fresh, but the cache is one face short: a curation edit just happened) or `backoff` (empty plus a
 * full regroup recorded as failed a moment ago).
 */
@RunWith(AndroidJUnit4::class)
class FaceLibrarySeederTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun intArg(name: String, default: Int): Int = args.getString(name)?.toIntOrNull() ?: default

    @Test
    fun seedFaceLibrary() = runBlocking<Unit> {
        val total = intArg("faces", 120_000)
        val identityCount = intArg("identities", 2_500)
        val state = args.getString("state") ?: "empty"
        val groupedShare = args.getString("grouped")?.toDoubleOrNull() ?: 0.38
        val namedIdentities = intArg("named", 0)
        val seed = (args.getString("seed")?.toLongOrNull() ?: 7L)
        assertTrue("unknown state $state", state in setOf("empty", "fresh", "stale", "backoff"))

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face_scan GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no scanned photos in face_scan: run the face walk once first", account != null)
            account!!

            val photoCount = (total + FACES_PER_PHOTO - 1) / FACES_PER_PHOTO
            val photoKeys = ArrayList<String>(photoCount)
            db.openHelper.readableDatabase
                .query("SELECT photoKey FROM face_scan WHERE userId = ? LIMIT $photoCount", arrayOf(account))
                .use { while (it.moveToNext()) photoKeys.add(it.getString(0)) }
            assertTrue("need $photoCount scanned photos, found ${photoKeys.size}", photoKeys.size >= photoCount)

            val faceDao = db.faceDao()
            val personDao = db.personDao()
            val summaryDao = db.clusterSummaryDao()
            faceDao.clearForUser(account)
            personDao.clearForUser(account)
            summaryDao.clearForUser(account)

            val rnd = Random(seed)
            val centers = Array(identityCount) { unitGaussian(rnd) }
            val weights = DoubleArray(identityCount) { 1.0 / (it + 1).toDouble().pow(0.9) }
            val weightSum = weights.sum()
            val cumulative = DoubleArray(identityCount)
            var running = 0.0
            for (i in weights.indices) {
                running += weights[i] / weightSum
                cumulative[i] = running
            }
            fun pickIdentity(): Int {
                val x = rnd.nextDouble()
                var lo = 0
                var hi = identityCount - 1
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (cumulative[mid] < x) lo = mid + 1 else hi = mid
                }
                return lo
            }

            val groupsPeople = state == "fresh" || state == "stale"
            // Clear faces are GOOD_SHARE of the library, so the biggest identities holding this much of the
            // weight put about groupedShare of ALL faces into people.
            val groupedMass = groupedShare / GOOD_SHARE
            var groupedIdentityCount = 0
            if (groupsPeople) {
                while (groupedIdentityCount < identityCount && cumulative[groupedIdentityCount] < groupedMass) {
                    groupedIdentityCount++
                }
            }

            val sums = HashMap<Int, FloatArray>()
            val counts = HashMap<Int, Int>()
            val groupedIds = HashMap<Int, MutableList<String>>()
            var inserted = 0
            var grouped = 0
            val batch = ArrayList<FaceEntity>(BATCH)

            suspend fun flush() {
                if (batch.isEmpty()) return
                db.withTransaction { faceDao.upsert(ArrayList(batch)) }
                inserted += batch.size
                batch.clear()
                if (inserted % 20_000 == 0) Log.i(TAG, "seeded $inserted of $total faces")
            }

            for (n in 0 until total) {
                val photoKey = photoKeys[n / FACES_PER_PHOTO]
                val id = "$photoKey#${n % FACES_PER_PHOTO}"
                val role = rnd.nextDouble()
                val identity = pickIdentity()
                val embedding: FloatArray
                val score: Float
                val blur: Float
                when {
                    role < GOOD_SHARE -> {
                        embedding = noisy(centers[identity], SIGMA_GOOD, rnd)
                        score = 0.62f + 0.37f * rnd.nextFloat()
                        blur = 14f + 66f * rnd.nextFloat()
                    }
                    role < GOOD_SHARE + WEAK_SHARE -> {
                        embedding = noisy(centers[identity], SIGMA_WEAK, rnd)
                        score = 0.45f + 0.20f * rnd.nextFloat()
                        blur = 3f + 8f * rnd.nextFloat()
                    }
                    else -> {
                        embedding = unitGaussian(rnd)
                        score = 0.50f + 0.25f * rnd.nextFloat()
                        blur = 2f + 28f * rnd.nextFloat()
                    }
                }
                val isGood = role < GOOD_SHARE
                val groupsThis = groupsPeople && isGood && identity < groupedIdentityCount
                if (groupsThis) {
                    val sum = sums.getOrPut(identity) { FloatArray(FACE_EMBEDDING_DIM) }
                    for (k in 0 until FACE_EMBEDDING_DIM) sum[k] += embedding[k]
                    counts[identity] = (counts[identity] ?: 0) + 1
                    groupedIds.getOrPut(identity) { ArrayList() }.add(id)
                    grouped++
                }
                batch.add(
                    FaceEntity(
                        id = id,
                        userId = account,
                        photoKey = photoKey,
                        left = 0.1f,
                        top = 0.1f,
                        right = 0.4f,
                        bottom = 0.4f,
                        landmarks = FRONTAL_LANDMARKS,
                        embedding = pack(embedding),
                        personId = null,
                        score = score,
                        blur = blur,
                        manualName = if (isGood && identity < namedIdentities && rnd.nextInt(3) == 0) "Seed person $identity" else null,
                    ),
                )
                if (batch.size >= BATCH) flush()
            }
            flush()

            if (groupsPeople) {
                val now = System.currentTimeMillis()
                val summaries = ArrayList<ClusterSummaryEntity>(groupedIds.size)
                var biggest: Int? = null
                for ((identity, ids) in groupedIds) {
                    if (biggest == null || ids.size > (counts[biggest] ?: 0)) biggest = identity
                }
                for ((identity, ids) in groupedIds) {
                    val personId = personDao.insert(PersonEntity(userId = account, coverFaceId = ids.first(), faceCount = ids.size))
                    ids.chunked(500).forEach { faceDao.assignPersonBatch(personId, it) }
                    val cached = if (state == "stale" && identity == biggest) ids.size - 1 else ids.size
                    summaries.add(
                        ClusterSummaryEntity(
                            personId = personId,
                            userId = account,
                            centroid = pack(normalized(sums.getValue(identity))),
                            memberCount = cached,
                            modelVersion = FACE_MODEL_VERSION,
                            updatedAt = now,
                        ),
                    )
                }
                summaryDao.upsertAll(summaries)
            }

            val store = MlWalkStateStore(File(File(context.filesDir, "ml_state"), "face_walk.bin"), BuildConfig.VERSION_CODE)
            if (state == "backoff") {
                store.write(MlWalkState(account = account, lastFailedRegroupEpochMs = System.currentTimeMillis()))
            } else {
                store.write(MlWalkState(account = account))
            }

            Log.i(
                TAG,
                "seeded state=$state faces=${faceDao.countForUser(account)} ungrouped=${faceDao.unclusteredCount(account)} " +
                    "people=${personDao.countForUser(account)} cache=${summaryDao.countForUser(account)} grouped=$grouped",
            )
        } finally {
            db.close()
        }
    }

    /**
     * Adds [extra] faces to the library as it is, as a scan that is still going on would, and lets the face
     * walk find [rescan] photos unscanned again so its next pass has work and does not end the walk: most
     * new faces look like the identities the seed made (they match people already there), some are weak
     * and some match nobody. Arguments: `extra` (300), `rescan` (40), `identities` (2500), `seed` (7).
     */
    @Test
    fun appendFaces() = runBlocking<Unit> {
        val extra = intArg("extra", 300)
        val rescan = intArg("rescan", 40)
        val identityCount = intArg("identities", 2_500)
        val seed = (args.getString("seed")?.toLongOrNull() ?: 7L)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no faces stored", account != null)
            account!!
            val centers = Random(seed).let { r -> Array(identityCount) { unitGaussian(r) } }
            val rnd = Random(seed + 101)
            val photoKeys = ArrayList<String>()
            db.openHelper.readableDatabase
                .query("SELECT photoKey FROM face_scan WHERE userId = ? LIMIT 200", arrayOf(account))
                .use { while (it.moveToNext()) photoKeys.add(it.getString(0)) }
            assertTrue("no scanned photos", photoKeys.isNotEmpty())
            val batch = ArrayList<FaceEntity>(extra)
            for (k in 0 until extra) {
                val role = rnd.nextDouble()
                val embedding: FloatArray
                val score: Float
                val blur: Float
                when {
                    role < 0.60 -> {
                        embedding = noisy(centers[rnd.nextInt(50)], SIGMA_GOOD, rnd)
                        score = 0.62f + 0.37f * rnd.nextFloat()
                        blur = 14f + 66f * rnd.nextFloat()
                    }
                    role < 0.85 -> {
                        embedding = noisy(centers[rnd.nextInt(identityCount)], SIGMA_WEAK, rnd)
                        score = 0.45f + 0.20f * rnd.nextFloat()
                        blur = 3f + 8f * rnd.nextFloat()
                    }
                    else -> {
                        embedding = unitGaussian(rnd)
                        score = 0.50f + 0.25f * rnd.nextFloat()
                        blur = 2f + 28f * rnd.nextFloat()
                    }
                }
                val photoKey = photoKeys[rnd.nextInt(photoKeys.size)]
                batch.add(
                    FaceEntity(
                        id = "extra${System.currentTimeMillis()}_$k#0", userId = account, photoKey = photoKey,
                        left = 0.1f, top = 0.1f, right = 0.4f, bottom = 0.4f, landmarks = FRONTAL_LANDMARKS,
                        embedding = pack(embedding), personId = null, score = score, blur = blur,
                    ),
                )
            }
            db.withTransaction { db.faceDao().upsert(batch) }
            val writable = db.openHelper.writableDatabase
            writable.execSQL(
                "DELETE FROM face_scan WHERE userId = ? AND photoKey IN " +
                    "(SELECT photoKey FROM face_scan WHERE userId = ? LIMIT $rescan OFFSET 100000)",
                arrayOf(account, account),
            )
            Log.i(
                TAG,
                "appended extra=$extra rescan=$rescan faces=${db.faceDao().countForUser(account)} " +
                    "ungrouped=${db.faceDao().unclusteredCount(account)}",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun reportFaceLibrary() = runBlocking<Unit> {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no faces stored", account != null)
            account!!
            val faceDao = db.faceDao()
            val sizes = db.openHelper.readableDatabase
                .query(
                    "SELECT COUNT(*) FROM (SELECT personId FROM face WHERE userId = ? AND personId IS NOT NULL " +
                        "GROUP BY personId HAVING COUNT(*) >= 2)",
                    arrayOf(account),
                ).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            Log.i(
                TAG,
                "report faces=${faceDao.countForUser(account)} ungrouped=${faceDao.unclusteredCount(account)} " +
                    "assigned=${faceDao.assignedFaceCount(account)} people=${db.personDao().countForUser(account)} " +
                    "peopleWithTwoFaces=$sizes cache=${db.clusterSummaryDao().countForUser(account)}",
            )
        } finally {
            db.close()
        }
    }

    /**
     * Times [eu.akoos.photos.domain.usecase.ClusterFacesUseCase.recountPhotoCounts] over the stored library: zeroes
     * every cached photo count first, recounts, and compares the result with a direct count of each person's
     * distinct photos. Needs the app process stopped and a DEBUG build installed (a release build renames the class).
     */
    @Test
    fun timeRecount() = runBlocking<Unit> {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no faces stored", account != null)
            account!!
            db.openHelper.writableDatabase.execSQL("UPDATE person SET faceCount = 0 WHERE userId = ?", arrayOf(account))
            val cluster = eu.akoos.photos.domain.usecase.ClusterFacesUseCase(
                db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
                db.personCoverDao(), db.clusterSummaryDao(), db,
            )
            val startMs = android.os.SystemClock.elapsedRealtime()
            val looked = cluster.recountPhotoCounts(me.proton.core.domain.entity.UserId(account))
            val tookMs = android.os.SystemClock.elapsedRealtime() - startMs
            val wrong = db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM person p WHERE p.userId = ? AND p.faceCount != " +
                    "(SELECT COUNT(DISTINCT photoKey) FROM face f WHERE f.personId = p.id AND f.rejected = 0)",
                arrayOf(account),
            ).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            Log.i(TAG, "recount people=$looked tookMs=$tookMs differsFromDirectCount=$wrong faces=${db.faceDao().countForUser(account)}")
        } finally {
            db.close()
        }
    }

    private fun unitGaussian(rnd: Random): FloatArray {
        val v = FloatArray(FACE_EMBEDDING_DIM) { rnd.nextGaussian().toFloat() }
        return normalized(v)
    }

    private fun noisy(center: FloatArray, sigma: Float, rnd: Random): FloatArray {
        val v = FloatArray(FACE_EMBEDDING_DIM) { center[it] + sigma * rnd.nextGaussian().toFloat() }
        return normalized(v)
    }

    private fun normalized(v: FloatArray): FloatArray {
        var normSq = 0.0
        for (x in v) normSq += x.toDouble() * x
        val inv = if (normSq > 0.0) (1.0 / sqrt(normSq)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * inv }
    }

    private fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    private companion object {
        const val TAG = "FaceSeeder"
        const val FACES_PER_PHOTO = 2
        const val BATCH = 2_000
        const val GOOD_SHARE = 0.70
        const val WEAK_SHARE = 0.20
        const val SIGMA_GOOD = 0.033f
        const val SIGMA_WEAK = 0.060f
        const val FRONTAL_LANDMARKS = "30,40;70,40;50,60;35,80;65,80"
    }
}
