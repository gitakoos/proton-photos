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

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlin.math.ceil

/*
 * Whether a snapshot import fits on the volume the database writes to, checked before its first row, and
 * whether a failure while it wrote was that volume running full.
 */

/**
 * Free space a face import asks for per byte of its verified plaintext. Stored, a face row takes about
 * twice its snapshot bytes, since no second row fits beside its vector on a database page. The import's
 * single transaction writes every new page to the write-ahead log, the space its commit needs, and the
 * rest of the factor covers the indexes.
 */
internal const val FACE_IMPORT_SPACE_FACTOR = 2.5

/**
 * Free space a search import asks for per byte of its verified plaintext. A snapshot carries each vector
 * at half precision and the index stores it at full, and no second row fits beside it on a database page,
 * so a row takes close to four times its snapshot bytes. The chunks commit as they go, so the write-ahead
 * log stays small.
 */
internal const val SEARCH_IMPORT_SPACE_FACTOR = 4.0

/** Free space an import leaves on top of its estimate, for the rest of the app and the system meanwhile. */
internal const val IMPORT_SPACE_MARGIN_BYTES = 50L * 1024 * 1024

/** How deep [isStorageFull] follows a failure's causes. */
private const val MAX_CAUSE_DEPTH = 8

/** The free space an import of [plaintextBytes] of verified plaintext asks for, at [factor] per byte. */
internal fun importSpaceNeeded(plaintextBytes: Long, factor: Double): Long =
    ceil(plaintextBytes.coerceAtLeast(0L) * factor).toLong() + IMPORT_SPACE_MARGIN_BYTES

/**
 * Whether an import of [plaintextBytes] at [factor] fits in [freeBytes]. Free space that cannot be read
 * (null) never refuses: the import still reports storage running full while it writes.
 */
internal fun importFits(plaintextBytes: Long, factor: Double, freeBytes: Long?): Boolean =
    freeBytes == null || freeBytes >= importSpaceNeeded(plaintextBytes, factor)

/**
 * Free bytes on the volume that holds [database]'s file, read from its directory, or null for a database
 * held in memory or a volume that cannot be read.
 */
internal fun databaseFreeBytes(context: Context, database: RoomDatabase): Long? {
    val name = database.openHelper.databaseName ?: return null
    val directory = context.getDatabasePath(name).parentFile ?: return null
    return runCatching { StatFs(directory.path).availableBytes }.getOrNull()
}

/**
 * Whether [failure], or one of its causes, is storage running full: SQLite's disk-full error, by its class
 * or its code, or a write the system refused with ENOSPC.
 */
internal fun isStorageFull(failure: Throwable): Boolean {
    var cause: Throwable? = failure
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (cause is SQLiteFullException) return true
        if (cause is SQLiteException && cause.message?.contains("SQLITE_FULL") == true) return true
        if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) return true
        if (cause.message?.contains("ENOSPC") == true) return true
        cause = cause.cause
    }
    return false
}

/**
 * Runs [block] in a transaction, as [withTransaction] does, and when storage runs full inside it throws
 * that failure rather than whatever the way out adds. On a full disk SQLite may roll the whole transaction
 * back itself, and the rollback the transaction then asks for fails with an error of its own that would
 * hide the first.
 */
internal suspend fun <T> RoomDatabase.withStorageAwareTransaction(block: suspend () -> T): T {
    var full: Throwable? = null
    try {
        return withTransaction {
            try {
                block()
            } catch (e: Throwable) {
                if (isStorageFull(e)) full = e
                throw e
            }
        }
    } catch (e: Throwable) {
        val first = full ?: throw e
        if (first !== e) first.addSuppressed(e)
        throw first
    }
}
