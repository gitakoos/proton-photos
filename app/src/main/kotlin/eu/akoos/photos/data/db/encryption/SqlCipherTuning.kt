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

package eu.akoos.photos.data.db.encryption

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteGlobal

/**
 * The sizes behind [SqlCipherTuning], kept free of Android calls so they run in a unit test.
 *
 * SQLCipher decrypts and checks every page it reads from storage. A page read a second time costs a second decrypt, where the platform SQLite only copies it from
 * the kernel's file cache. Both libraries cap the page cache of the whole process at 8 MB (a soft heap limit
 * set when the library starts), so a query over a large table decrypts the same pages again and again. A larger
 * page cache keeps them decrypted. Pages are only allocated as they are read, so a small database never uses it.
 */
internal object SqlCipherBudget {
    const val MIB = 1024L * 1024

    /** Room for the parsed schema and prepared statements of every connection, on top of the page cache. */
    const val HEADROOM_BYTES = 16 * MIB

    private const val LOW_RAM_BYTES = 16 * MIB
    private const val MIN_BYTES = 32 * MIB
    private const val MAX_BYTES = 128 * MIB

    /** The page cache all connections share: a 48th of the device memory, 32 to 128 MB; 16 MB on a low-RAM device. */
    fun cacheBytes(totalMemoryBytes: Long, lowRamDevice: Boolean): Long =
        if (lowRamDevice) LOW_RAM_BYTES else (totalMemoryBytes / 48).coerceIn(MIN_BYTES, MAX_BYTES)

    /**
     * Connections in the pool: the platform SQLite's own number (at least 2, the same rule it applies), so the app
     * runs as many reads at once as it did on the platform SQLite. SQLCipher's default of 10 opens connections the
     * platform never did, each keyed and with its own cold cache.
     */
    fun poolSize(platformPoolSize: Int?): Int = (platformPoolSize ?: DEFAULT_POOL_SIZE).coerceIn(2, MAX_POOL_SIZE)

    /** `PRAGMA cache_size` takes a negative number as a size in KiB. */
    fun cacheSizePragma(cacheBytes: Long): Long = -(cacheBytes / 1024)

    private const val DEFAULT_POOL_SIZE = 4
    private const val MAX_POOL_SIZE = 10
}

/**
 * How every SQLCipher connection is set up. None of it changes the file format, so a database encrypted before
 * these settings existed opens exactly as before, and an older build opens a file written with them.
 */
internal class SqlCipherTuning(val cacheBytes: Long, val poolSize: Int) {

    val softHeapLimitBytes: Long get() = cacheBytes + SqlCipherBudget.HEADROOM_BYTES

    /** Runs on each connection the pool opens, right after the key. The heap limit is process-wide; setting it again is a no-op. */
    val hook: SQLiteDatabaseHook = object : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) {
            connection.executeForLong("PRAGMA soft_heap_limit = $softHeapLimitBytes", null, null)
            connection.execute("PRAGMA cache_size = ${SqlCipherBudget.cacheSizePragma(cacheBytes)}", null, null)
        }
    }

    companion object {
        @Volatile private var current: SqlCipherTuning? = null

        /**
         * The settings for this device, worked out once per process. The first call also sets the pool size and
         * hands the cached pages back to the system when the app goes to the background or memory runs low.
         */
        fun forContext(context: Context): SqlCipherTuning {
            current?.let { return it }
            synchronized(this) {
                current?.let { return it }
                val app = context.applicationContext
                val activityManager = app.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo()
                activityManager?.getMemoryInfo(memory)
                val tuning = SqlCipherTuning(
                    cacheBytes = SqlCipherBudget.cacheBytes(memory.totalMem, activityManager?.isLowRamDevice == true),
                    poolSize = SqlCipherBudget.poolSize(platformPoolSize()),
                )
                SQLiteGlobal.setWALConnectionPoolSize(tuning.poolSize)
                app.registerComponentCallbacks(ReleaseCacheOnTrim)
                current = tuning
                return tuning
            }
        }

        @SuppressLint("DiscouragedApi")
        private fun platformPoolSize(): Int? = runCatching {
            val resources = Resources.getSystem()
            val id = resources.getIdentifier("config_wal_connection_pool_size", "integer", "android")
            if (id == 0) null else resources.getInteger(id)
        }.getOrNull()
    }
}

/** Frees the decrypted pages no connection is using; the platform SQLite gets the same call from the system. */
private object ReleaseCacheOnTrim : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Still delivered on low memory")
    override fun onLowMemory() = release()

    /** Each call frees at most 8 MB. */
    private fun release() {
        if (!SqlCipherLibrary.isLoaded) return
        var rounds = 0
        while (rounds++ < 64 && SQLiteDatabase.releaseMemory() > 0) Unit
    }
}
