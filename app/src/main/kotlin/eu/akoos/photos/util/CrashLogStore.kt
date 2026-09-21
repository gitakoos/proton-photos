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

/**
 * The on-disk crash record shared by the writer (the uncaught-exception handler) and the readers (the
 * startup prune and the Settings "Copy diagnostics" bundle). Each crash is appended as one block that
 * starts with [BLOCK_PREFIX] followed by the build's version code, so a block can be attributed to the
 * version it happened on and blocks from a version the device no longer runs can be dropped.
 *
 * Why prune by version: the file survives an app update, so without this a tester who updates keeps every
 * prior version's crashes and sends them along with a fresh report, where they read as current problems
 * long after the build that caused them is gone. Keeping only the running version's blocks makes a copied
 * bundle describe the build it was copied from and nothing older.
 */
object CrashLogStore {

    private const val DIR = "diagnostics"
    private const val FILE = "last_crash.txt"

    /** The marker every crash block begins with; the version code and " ----" follow on the same line. */
    const val BLOCK_PREFIX = "---- crash v"

    /** The crash record file under the app's files dir. Callers create the parent as needed. */
    fun file(filesDir: File): File = File(File(filesDir, DIR), FILE)

    /** The header line a new crash block for [versionCode] starts with, so the writer and this parser
     *  agree on one format. */
    fun blockHeader(versionCode: Int): String = "$BLOCK_PREFIX$versionCode ----\n"

    /**
     * Drops every crash block that did not happen on [versionCode], rewriting the file with only the
     * matching blocks or deleting it when none remain. Safe to run on every start: it keeps the running
     * version's own blocks, so a genuine crash from an earlier session on this same version survives, and
     * only ever removes blocks tagged with a different (old) version. Best effort; a read or write failure
     * leaves the file as it was.
     */
    fun pruneToVersion(filesDir: File, versionCode: Int) {
        val f = file(filesDir)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        val kept = blocksOf(text).filter { codeOf(it) == versionCode }
        if (kept.isEmpty()) {
            runCatching { f.delete() }
            return
        }
        val rebuilt = kept.joinToString(separator = "")
        if (rebuilt != text) runCatching { f.writeText(rebuilt) }
    }

    /** The crash blocks that happened on [versionCode], joined and trimmed, for the diagnostics bundle.
     *  Empty when the file is absent or holds no block for this version, so an updated build that has not
     *  crashed yet contributes nothing rather than a prior version's history. */
    fun currentVersionText(filesDir: File, versionCode: Int): String {
        val f = file(filesDir)
        if (!f.exists()) return ""
        val text = runCatching { f.readText() }.getOrNull() ?: return ""
        return blocksOf(text).filter { codeOf(it) == versionCode }.joinToString(separator = "").trim()
    }

    /** Split the file into blocks, each re-prefixed with [BLOCK_PREFIX]; any leading text before the first
     *  marker (there should be none) is discarded. */
    private fun blocksOf(text: String): List<String> {
        if (!text.contains(BLOCK_PREFIX)) return emptyList()
        return text.split(BLOCK_PREFIX)
            .filter { it.isNotBlank() }
            .map { BLOCK_PREFIX + it }
    }

    /** The version code a block is tagged with, read from the digits right after [BLOCK_PREFIX], or null
     *  when the header is malformed (that block is then treated as not matching any version and dropped). */
    private fun codeOf(block: String): Int? =
        block.removePrefix(BLOCK_PREFIX).takeWhile { it.isDigit() }.toIntOrNull()
}
