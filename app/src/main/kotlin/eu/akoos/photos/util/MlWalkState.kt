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

package eu.akoos.photos.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One photo's transient-failure record as it sits on disk: how many failures in a row, and when it is due
 *  again on the wall clock, since the elapsed-time clock a running book keeps does not outlive a boot. */
class RetryRecord(val key: String, val attempts: Int, val dueEpochMs: Long)

/**
 * What a background model walk (faces, semantic search) keeps between process starts, so a process the
 * system killed does not begin its backoffs again: the photos waiting out a retry, and, for the face walk,
 * the spacing of full regroups. Every time is wall-clock milliseconds. [account] is the partition it
 * describes. [finalizeOwed] marks a batched grouping whose closing steps (merging the groups it opened,
 * filling Unsorted) have not run yet, and [groupedUpToRow] the last stored face row a batched run has looked
 * at, so the next one reads only the faces stored after it.
 */
class MlWalkState(
    val account: String,
    val retries: List<RetryRecord> = emptyList(),
    val lastFailedRegroupEpochMs: Long? = null,
    val lastRegroupEndEpochMs: Long? = null,
    val lastRegroupDurationMs: Long = 0L,
    val baselineUngrouped: Int? = null,
    val finalizeOwed: Boolean = false,
    val groupedUpToRow: Long = 0L,
) {
    val isEmpty: Boolean
        get() = retries.isEmpty() && lastFailedRegroupEpochMs == null && lastRegroupEndEpochMs == null &&
            baselineUngrouped == null && !finalizeOwed && groupedUpToRow == 0L
}

/** At most this many retry records are written or read back, so a damaged file cannot inflate the book. */
const val MAX_PERSISTED_RETRIES = 5_000

/** The wall-clock time of a moment the elapsed-time clock reads [elapsedMs], given both clocks now. */
fun epochFromElapsed(elapsedMs: Long, nowElapsedMs: Long, nowEpochMs: Long): Long =
    nowEpochMs - (nowElapsedMs - elapsedMs)

/** The elapsed-time reading of a wall-clock moment [epochMs] given both clocks now. A moment the wall clock
 *  puts in the future (it was set back) counts as now, so a skewed clock never stretches an age. */
fun elapsedFromEpoch(epochMs: Long, nowElapsedMs: Long, nowEpochMs: Long): Long =
    nowElapsedMs - (nowEpochMs - epochMs).coerceAtLeast(0L)

/**
 * One small file holding a walk's [MlWalkState]. A write lands whole or not at all: the bytes go to a file
 * beside it, are flushed, and are renamed over it. A file that is absent, cut short, damaged, written by
 * another app version or for another account reads as no state at all, which is exactly how a fresh
 * process behaves, so a bad file can cost no more than the backoff it would have kept. Tied to the app
 * version so an update, which may fix whatever made a photo fail, starts every photo afresh.
 */
class MlWalkStateStore(private val file: File, private val appVersion: Int) {

    /** The saved state for [account], or null when there is none worth trusting. */
    @Synchronized
    fun read(account: String): MlWalkState? {
        if (!file.isFile) return null
        val state = try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input -> decode(input, appVersion) }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        return state?.takeIf { it.account == account }
    }

    /** Saves [state], or removes the file when it holds nothing. Best effort: a failure leaves the previous file. */
    @Synchronized
    fun write(state: MlWalkState) {
        if (state.isEmpty) {
            delete()
            return
        }
        val dir = file.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val temp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val output = DataOutputStream(BufferedOutputStream(stream))
                encode(output, state, appVersion)
                output.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
    }

    companion object {
        private const val MAGIC = "PPMLWALK"
        private const val FORMAT = 3

        internal fun encode(output: DataOutputStream, state: MlWalkState, appVersion: Int) {
            output.writeUTF(MAGIC)
            output.writeInt(FORMAT)
            output.writeInt(appVersion)
            output.writeUTF(state.account)
            val retries = state.retries.take(MAX_PERSISTED_RETRIES)
            output.writeInt(retries.size)
            for (record in retries) {
                output.writeUTF(record.key)
                output.writeInt(record.attempts)
                output.writeLong(record.dueEpochMs)
            }
            output.writeLong(state.lastFailedRegroupEpochMs ?: NONE)
            output.writeLong(state.lastRegroupEndEpochMs ?: NONE)
            output.writeLong(state.lastRegroupDurationMs)
            output.writeInt(state.baselineUngrouped ?: -1)
            output.writeBoolean(state.finalizeOwed)
            output.writeLong(state.groupedUpToRow)
        }

        /** The state [input] holds, or null when it is another format or another app version. A cut-short
         *  or damaged stream throws, which [read] turns into null. */
        internal fun decode(input: DataInputStream, appVersion: Int): MlWalkState? {
            if (input.readUTF() != MAGIC) return null
            if (input.readInt() != FORMAT) return null
            if (input.readInt() != appVersion) return null
            val account = input.readUTF()
            val count = input.readInt()
            if (count < 0 || count > MAX_PERSISTED_RETRIES) return null
            val retries = ArrayList<RetryRecord>(count)
            repeat(count) { retries.add(RetryRecord(input.readUTF(), input.readInt(), input.readLong())) }
            val failed = input.readLong().takeIf { it != NONE }
            val end = input.readLong().takeIf { it != NONE }
            val duration = input.readLong()
            val baseline = input.readInt().takeIf { it >= 0 }
            val finalizeOwed = input.readBoolean()
            val groupedUpToRow = input.readLong().coerceAtLeast(0L)
            return MlWalkState(account, retries, failed, end, duration.coerceAtLeast(0L), baseline, finalizeOwed, groupedUpToRow)
        }

        private const val NONE = Long.MIN_VALUE
    }
}
