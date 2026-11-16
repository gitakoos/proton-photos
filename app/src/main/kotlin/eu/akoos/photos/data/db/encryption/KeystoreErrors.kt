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

import androidx.annotation.VisibleForTesting
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.AEADBadTagException

/** How a Keystore failure is taken. */
enum class KeystoreFailure {
    /** The key can never be used again (invalidated): only a recovery copy gets the data back. */
    PERMANENT,

    /** Looks permanent, but Android also reports a busy or restarting Keystore this way: lost only once
     *  [KeystoreStrikes] saw it on several starts over a while. */
    SUSPECT,

    /** The Keystore did not answer this time: tried again, nothing set aside. */
    TRANSIENT,
}

/**
 * Tells a Keystore key that is broken for good from a Keystore that did not answer this time, by the whole cause
 * chain. Only an invalidated key counts as broken for good at once. The other errors a broken key throws
 * (InvalidKeyException, UnrecoverableKeyException, KeyStoreException, a refused tag) are also what Android wraps a
 * busy Keystore in, so they are [KeystoreFailure.SUSPECT] and need [KeystoreStrikes] to agree. A missing alias and a
 * tag refused by a key that loads are decided where they happen ([DatabaseKeyStore]).
 */
object KeystoreErrors {

    fun classify(error: Throwable): KeystoreFailure {
        var suspect = false
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_DEPTH) {
            if (e.javaClass.name == INVALIDATED) return KeystoreFailure.PERMANENT
            if (e is InvalidKeyException || e is UnrecoverableKeyException || e is KeyStoreException ||
                e is AEADBadTagException
            ) {
                suspect = true
            }
            if (e.javaClass.name == ANDROID_KEYSTORE_EXCEPTION && !isTransient(e)) suspect = true
            e = e.cause
            depth++
        }
        return if (suspect) KeystoreFailure.SUSPECT else KeystoreFailure.TRANSIENT
    }

    /** Only an invalidated key: the one case where a Keystore entry may be replaced at once. */
    fun isPermanent(error: Throwable): Boolean = classify(error) == KeystoreFailure.PERMANENT

    /** [android.security.KeyStoreException.isTransientFailure] (API 33), read without linking the class, so the
     *  rule runs in a unit test. Unknown answers transient. */
    private fun isTransient(e: Throwable): Boolean =
        runCatching { e.javaClass.getMethod("isTransientFailure").invoke(e) as Boolean }.getOrDefault(true)

    private const val INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    private const val ANDROID_KEYSTORE_EXCEPTION = "android.security.KeyStoreException"
    private const val MAX_DEPTH = 8
}

/**
 * Real time across process starts, measured on the time since boot, so a change of the wall clock never moves it.
 * A reboot between two starts counts only the time since the new boot: the total is never more than the real time.
 */
object RealTime {
    /** The time since boot now. */
    fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    /** [spanSoFar] grown by the time from a start at [lastElapsed] to one at [now] (both times since boot). */
    fun advance(spanSoFar: Long, lastElapsed: Long, now: Long): Long =
        spanSoFar + if (now >= lastElapsed) now - lastElapsed else now
}

/**
 * Counts a [KeystoreFailure.SUSPECT] error across process starts in [file]: it counts as broken for good only once
 * the same error was seen on [STARTS] different starts spread over at least [SPAN_MS] of real time ([RealTime]). A
 * key that opens clears it.
 */
class KeystoreStrikes(private val file: File) {

    /** Notes [error] for this start and answers whether it has now been seen long enough to be taken as permanent. */
    fun note(error: Throwable): Boolean {
        val now = clock()
        val start = currentStart()
        val signature = signatureOf(error)
        val record = read()
        val next = when {
            record == null || record.signature != signature -> Record(signature, 0L, 1, start, now)
            record.lastStart != start -> record.copy(
                span = RealTime.advance(record.span, record.lastElapsed, now),
                count = record.count + 1,
                lastStart = start,
                lastElapsed = now,
            )
            else -> record
        }
        if (next != record) write(next)
        return next.count >= STARTS && next.span >= SPAN_MS
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private data class Record(val signature: String, val span: Long, val count: Int, val lastStart: String, val lastElapsed: Long)

    /** A record written before the span was measured on the time since boot reads as none, so counting starts over. */
    private fun read(): Record? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.size < 5) return null
        val span = lines[1].toLongOrNull() ?: return null
        val count = lines[2].toIntOrNull() ?: return null
        val lastElapsed = lines[4].toLongOrNull() ?: return null
        return Record(lines[0], span, count, lines[3], lastElapsed)
    }

    private fun write(record: Record) {
        runCatching {
            file.parentFile?.mkdirs()
            val lines = listOf(record.signature, record.span, record.count, record.lastStart, record.lastElapsed)
            writeWhole(file, lines.joinToString("\n").toByteArray())
        }
    }

    private fun signatureOf(error: Throwable): String =
        generateSequence(error) { it.cause }.take(MAX_SIGNATURE).joinToString(">") { it.javaClass.name }

    companion object {
        const val STARTS = 3
        const val SPAN_MS = 10L * 60 * 1000
        private const val MAX_SIGNATURE = 4

        /** One id per process, so each start counts once however often a key is tried in it. */
        private val PROCESS_START: String = UUID.randomUUID().toString()

        @VisibleForTesting internal var currentStart: () -> String = { PROCESS_START }
        /** The time since boot ([RealTime.elapsed]). */
        @VisibleForTesting internal var clock: () -> Long = RealTime::elapsed
    }
}
