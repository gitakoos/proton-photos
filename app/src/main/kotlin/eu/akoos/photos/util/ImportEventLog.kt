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

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The import's step-by-step record, kept in a small file so it survives the process being killed in the
 * middle of a run (the situation a long import is most likely to hit, and exactly the one an in-memory
 * counter forgets). One line per event, newest last, trimmed to the last [MAX_LINES], and each line starts
 * with a time of day so a stall is visible as a gap.
 *
 * Privacy matches [ImportDiagnostics]: callers pass only counts, sizes, percentages, fixed labels and
 * exception class names (see `ImportFailure.describe`), never an archive, photo or folder name, a hash, a
 * caption or a message. The file sits in the diagnostics directory, which sign-out deletes whole, and a new
 * import run starts it afresh so the copied bundle describes the run in question.
 */
object ImportEventLog {

    private const val DIR = "diagnostics"
    private const val FILE = "import_log.txt"
    const val MAX_LINES = 80

    private val lock = Any()

    fun file(filesDir: File): File = File(File(filesDir, DIR), FILE)

    /** Starts a new run's record, dropping the previous one. */
    fun reset(filesDir: File) {
        synchronized(lock) { runCatching { file(filesDir).delete() } }
    }

    /** Appends [event] with the current time of day. Best effort: a write failure is swallowed, since the
     *  log must never be the reason an import fails. */
    fun record(filesDir: File, event: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            runCatching {
                val f = file(filesDir)
                f.parentFile?.mkdirs()
                val line = stamp(nowMs) + " " + event.replace('\n', ' ')
                val lines = if (f.exists()) f.readLines() else emptyList()
                val kept = (lines + line).takeLast(MAX_LINES)
                f.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            }
        }
    }

    /** The recorded lines, oldest first, or an empty string when nothing was recorded. */
    fun text(filesDir: File): String = synchronized(lock) {
        val f = file(filesDir)
        if (!f.exists()) "" else runCatching { f.readText().trim() }.getOrDefault("")
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
