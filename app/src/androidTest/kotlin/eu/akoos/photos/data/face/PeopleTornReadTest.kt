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
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Merges pairs of clusters of a stored face library while the people table is being read the way the People
 * screens read it (a Room stream plus a tight loop of list reads), once with the merge's statements one by
 * one and once in a single transaction, and reports how many reads were torn. Run by hand against a device
 * that holds a seeded library, with the app process stopped:
 * `adb shell am instrument -w -e class ...PeopleTornReadTest -e rounds 40 ...`. It changes the stored people.
 */
@RunWith(AndroidJUnit4::class)
class PeopleTornReadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun mergeWhileObserving() = runBlocking<Unit> {
        val rounds = args.getString("rounds")?.toIntOrNull() ?: 40
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no faces stored", account != null)
            account!!
            val personDao = db.personDao()
            val faceDao = db.faceDao()
            val manualDao = db.personManualPhotoDao()
            val unnamed = personDao.allForUser(account)
                .filter { it.displayName == null && !it.isOther && it.faceCount >= 1 }
                .map { it.id }
            assertTrue("need ${rounds * 4} unnamed people, found ${unnamed.size}", unnamed.size >= rounds * 4)

            suspend fun mergeOneByOne(ids: Set<Long>, name: String) {
                val target = ids.maxByOrNull { faceDao.faceCountForPerson(account, it) } ?: return
                val faceIds = ids.flatMap { faceDao.faceIdsForPerson(account, it) }
                if (faceIds.isNotEmpty()) {
                    faceDao.reassignFacesToPerson(faceIds, target)
                    faceDao.labelFacesByIds(faceIds, name)
                }
                personDao.updateName(target, name)
                val manual = manualDao.photoKeysForNameList(account, name)
                val count = (faceDao.distinctPhotoKeysForPerson(account, target) + manual).toHashSet().size
                personDao.updateCoverAndCount(target, faceDao.topFaceForPerson(account, target), count)
                for (id in ids) if (id != target) personDao.updateCoverAndCount(id, null, 0)
                personDao.deleteEmpty(account)
            }

            suspend fun mergeInOneTransaction(ids: Set<Long>, name: String) {
                val target = ids.maxByOrNull { faceDao.faceCountForPerson(account, it) } ?: return
                val faceIds = ids.flatMap { faceDao.faceIdsForPerson(account, it) }
                val manual = manualDao.photoKeysForNameList(account, name)
                db.withTransaction {
                    if (faceIds.isNotEmpty()) {
                        faceDao.reassignFacesToPerson(faceIds, target)
                        faceDao.labelFacesByIds(faceIds, name)
                    }
                    personDao.updateName(target, name)
                    val count = (faceDao.distinctPhotoKeysForPerson(account, target) + manual).toHashSet().size
                    personDao.updateCoverAndCount(target, faceDao.topFaceForPerson(account, target), count)
                    for (id in ids) if (id != target) personDao.updateCoverAndCount(id, null, 0)
                    personDao.deleteEmpty(account)
                }
            }

            suspend fun phase(label: String, ids: List<Long>, merge: suspend (Set<Long>, String) -> Unit) {
                val emissions = AtomicInteger()
                val listReads = AtomicInteger()
                val failures = ConcurrentLinkedQueue<String>()
                val stream = launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            personDao.observePeopleForUser(account).collect { emissions.incrementAndGet() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            failures.add("stream " + e.javaClass.simpleName + ": " + e.message)
                            delay(100)
                        }
                    }
                }
                val loop = launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            personDao.allForUser(account)
                            listReads.incrementAndGet()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            failures.add("list " + e.javaClass.simpleName + ": " + e.message)
                        }
                    }
                }
                val start = android.os.SystemClock.elapsedRealtime()
                for (i in 0 until rounds) {
                    merge(setOf(ids[2 * i], ids[2 * i + 1]), "TornReadTest-$label-$i")
                }
                val took = android.os.SystemClock.elapsedRealtime() - start
                delay(1_000)
                stream.cancelAndJoin()
                loop.cancelAndJoin()
                Log.i(
                    TAG,
                    "phase=$label merges=$rounds tookMs=$took streamEmissions=${emissions.get()} " +
                        "listReads=${listReads.get()} tornReads=${failures.size} first=${failures.firstOrNull()}",
                )
            }

            phase("oneByOne", unnamed.subList(0, rounds * 2), ::mergeOneByOne)
            phase("transaction", unnamed.subList(rounds * 2, rounds * 4), ::mergeInOneTransaction)
        } finally {
            db.close()
        }
    }

    /**
     * The one-time photo recount over every person, run while the people table is read as the People screens read
     * it, to count the reads it tears. Needs a DEBUG build installed and the app process stopped.
     */
    @Test
    fun recountWhileObserving() = runBlocking<Unit> {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val account = db.openHelper.readableDatabase
                .query("SELECT userId FROM face GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            assertTrue("no faces stored", account != null)
            account!!
            val personDao = db.personDao()
            val cluster = eu.akoos.photos.domain.usecase.ClusterFacesUseCase(
                db.faceDao(), personDao, db.personManualPhotoDao(), db.notPersonDao(),
                db.personCoverDao(), db.clusterSummaryDao(), db,
            )
            val emissions = AtomicInteger()
            val listReads = AtomicInteger()
            val failures = ConcurrentLinkedQueue<String>()
            val stream = launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        personDao.observePeopleForUser(account).collect { emissions.incrementAndGet() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures.add("stream " + e.javaClass.simpleName + ": " + e.message)
                        delay(100)
                    }
                }
            }
            val loop = launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        personDao.allForUser(account)
                        listReads.incrementAndGet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures.add("list " + e.javaClass.simpleName + ": " + e.message)
                    }
                }
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val looked = cluster.recountPhotoCounts(me.proton.core.domain.entity.UserId(account))
            val took = android.os.SystemClock.elapsedRealtime() - start
            delay(1_000)
            stream.cancelAndJoin()
            loop.cancelAndJoin()
            Log.i(
                TAG,
                "recountWhileObserving people=$looked tookMs=$took streamEmissions=${emissions.get()} " +
                    "listReads=${listReads.get()} tornReads=${failures.size} first=${failures.firstOrNull()}",
            )
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TAG = "FaceSeeder"
    }
}
