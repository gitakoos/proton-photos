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

import kotlin.math.abs

/**
 * Pure helpers for the recorded capture-date override map (SettingsKeys.DOWNLOAD_DATE_OVERRIDES),
 * persisted as a set of "mediaUri|captureMs|modifiedSeconds" strings. An entry comes from a download
 * or from an EXIF read, and either way it is written only where the MediaStore DATE_TAKEN column is
 * established to be missing or wrong, so the map outranks that column.
 *
 * MediaStore only derives DATE_TAKEN from the embedded date for JPEG/HEIF images (and the video
 * mvhd). For a downloaded PNG/WebP/GIF it refuses the DATE_TAKEN column write entirely, leaving the
 * column 0, so the file would read as its download date once its cloud twin is gone (the local scan
 * falls back to DATE_ADDED). A file the provider dated from something other than its own embedded
 * date carries the opposite fault: a non-zero column holding the wrong instant. The map answers both.
 *
 * The trailing field is the DATE_MODIFIED (seconds) the file carried when the entry was written, so
 * an entry can be held against the file it describes. A file whose modified time has moved on was
 * edited by something outside the app, and since an entry outranks the column nothing else would ever
 * notice: such an entry is void ([isCurrent]), and dropping it is what lets the file be read from
 * scratch. An entry written without that field carries no modified time, which reads as unknown and
 * is never current, so it too is re-derived once.
 *
 * Centralised so the write sides (the downloader, the EXIF read) and the read side (the media scan)
 * share one encoding and can be verified without a device.
 */
object CaptureDateOverride {

    /** What one persisted entry records: the capture date to report for the file, and the
     *  DATE_MODIFIED (seconds) the file carried when the entry was written, null when unknown. */
    data class Entry(val captureMs: Long, val modifiedSeconds: Long?)

    /** Slack allowed when the EXIF datetime carries an offset: the pair pins one exact instant, so only
     *  sub-second truncation and rounding separate two readings of the same moment. */
    private const val EXACT_INSTANT_SLACK_MS = 60_000L

    /** Slack allowed when the EXIF datetime carries no offset: the wall clock is only as accurate as
     *  the device zone, and the widest real zone spread is 26 hours, so a timezone cannot trip two days
     *  while a capture date wrong by months or years still clears it. */
    private const val WALL_CLOCK_SLACK_MS = 2L * 24 * 60 * 60 * 1000

    /** True when a download should record an override: the capture date is known but MediaStore left
     *  DATE_TAKEN unset (0), so nothing else carries the date for this file. */
    fun shouldRecord(dateTakenMs: Long?, storedDateTaken: Long): Boolean =
        dateTakenMs != null && dateTakenMs > 0L && storedDateTaken <= 0L

    /**
     * The capture date an EXIF read should record for a file whose MediaStore DATE_TAKEN column reads
     * [rawDateTakenMs], or null to leave the file alone. [exifHasOffset] says whether [exifMs] came
     * from a datetime that carried an EXIF offset, which is what decides how far the two may drift
     * apart before the difference counts as an error rather than as a zone difference.
     */
    fun captureDateCorrection(rawDateTakenMs: Long, exifMs: Long?, exifHasOffset: Boolean): Long? {
        if (exifMs == null || exifMs <= 0L) return null
        if (rawDateTakenMs <= 0L) return exifMs
        val slack = if (exifHasOffset) EXACT_INSTANT_SLACK_MS else WALL_CLOCK_SLACK_MS
        return exifMs.takeIf { abs(it - rawDateTakenMs) > slack }
    }

    /** True when [entry] still describes a file whose DATE_MODIFIED reads [modifiedSeconds]. An
     *  unknown modified time pins the entry to no state of the file at all, so it is never current
     *  and the walk re-derives it. */
    fun isCurrent(entry: Entry, modifiedSeconds: Long): Boolean =
        entry.modifiedSeconds != null && entry.modifiedSeconds == modifiedSeconds

    /** Flatten a recorded correction into its persisted entry. An unknown [modifiedSeconds] writes the
     *  two-field form, which is what [parse] reads back as unknown. */
    fun encode(uri: String, captureMs: Long, modifiedSeconds: Long?): String =
        if (modifiedSeconds == null) "$uri|$captureMs" else "$uri|$captureMs|$modifiedSeconds"

    /** Parse the persisted entry set into a uri -> [Entry] map, dropping malformed or non-positive
     *  entries. */
    fun parse(entries: Set<String>?): Map<String, Entry> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Entry>(entries.size)
        for (entry in entries) split(entry)?.let { out[it.first] = it.second }
        return out
    }

    /**
     * One persisted entry split into the uri it belongs to and what it records, or null when it
     * carries no usable capture date.
     *
     * The numeric fields are read from the RIGHT and everything before them is the uri, so a uri that
     * itself contains the separator still resolves. Three fields only when the last TWO both parse as
     * numbers, which is what keeps a two-field entry (no modified time) readable: its middle field is
     * part of the uri and does not look like a number. A trailing field that parses as nothing is read
     * as a corrupted modified time rather than as the capture date, so a damaged tail costs the entry
     * its modified time and not the correction itself.
     */
    private fun split(entry: String): Pair<String, Entry>? {
        val lastSep = entry.lastIndexOf('|')
        if (lastSep <= 0) return null
        val last = entry.substring(lastSep + 1).toLongOrNull()
        val midSep = entry.lastIndexOf('|', lastSep - 1)
        val mid = if (midSep > 0) entry.substring(midSep + 1, lastSep).toLongOrNull() else null
        return when {
            last != null && mid != null ->
                if (mid > 0L) entry.substring(0, midSep) to Entry(mid, last) else null
            last != null ->
                if (last > 0L) entry.substring(0, lastSep) to Entry(last, null) else null
            mid != null && mid > 0L -> entry.substring(0, midSep) to Entry(mid, null)
            else -> null
        }
    }

    /** The date a local media row should report: the recorded override (ms) when one exists, else the
     *  MediaStore DATE_TAKEN (ms) when set, else the file's added time ([dateAddedSeconds], promoted to
     *  ms). The override leads because an entry is recorded only where the column is established to be
     *  missing or wrong, so reading the column first would keep reporting the very value the entry
     *  exists to replace. */
    fun resolveDateTaken(rawDateTaken: Long, overrideMs: Long?, dateAddedSeconds: Long): Long = when {
        overrideMs != null && overrideMs > 0L -> overrideMs
        rawDateTaken > 0L -> rawDateTaken
        else -> dateAddedSeconds * 1000L
    }

    /**
     * Move the recorded override of every uri in [uris] to [captureMs], the value a metadata edit just
     * wrote. Only a uri that ALREADY has an entry moves: an entry exists exactly for the files whose
     * stored date the app had to correct, and [resolveDateTaken] reads an entry ahead of the column,
     * so leaving one behind would keep reporting the recorded date over the edit. A uri with no entry
     * is left alone, because its own column carries the date and an invented entry would only bloat
     * the map. Returns null when nothing changed, so the caller can skip a needless persist.
     *
     * The moved entry carries an UNKNOWN modified time: an edit rewrites the file, so whatever the
     * caller could read here is already behind the write it just made. Unknown means the next walk
     * drops the entry and re-decides from the file, which is the right answer after an edit that put
     * the date into both the EXIF and the column.
     */
    fun retarget(entries: Set<String>, uris: Set<String>, captureMs: Long): Set<String>? =
        if (captureMs <= 0L) null else retarget(entries, uris.associateWith { captureMs })

    /**
     * Move the recorded override of every uri in [captureMsByUri] to the date it maps to. A bulk date
     * SHIFT lands a different instant on each file, so the targets are a map rather than one shared
     * value; the rules are the ones [retarget] above states, applied per uri. A uri mapped to a
     * non-positive date describes no capture and is left alone.
     */
    fun retarget(entries: Set<String>, captureMsByUri: Map<String, Long>): Set<String>? {
        if (entries.isEmpty() || captureMsByUri.isEmpty()) return null
        var changed = false
        val out = HashSet<String>(entries.size)
        for (entry in entries) {
            val uri = split(entry)?.first
            val captureMs = uri?.let { captureMsByUri[it] }
            val moved = if (uri != null && captureMs != null && captureMs > 0L) {
                encode(uri, captureMs, modifiedSeconds = null)
            } else {
                entry
            }
            if (moved != entry) changed = true
            out += moved
        }
        return if (changed) out else null
    }

    /**
     * Create or replace the override of each uri in [captureMsByUri] (ms > 0) with a fresh entry
     * carrying [modifiedByUri]'s DATE_MODIFIED (seconds), so a file with no prior entry gains a durable
     * one [isCurrent] keeps rather than one the next walk re-derives away. Every other entry is left
     * as-is. Returns null when no positive-ms date was written, so the caller can skip a needless
     * persist.
     *
     * Where [retarget] only MOVES an entry a file already holds, this writes one where none existed: a
     * meme or a downloaded PNG carries no MediaStore DATE_TAKEN and no EXIF, so a date read off its own
     * file name has nowhere durable to live until an entry is created for it. Recording the file's
     * modified time is what keeps that fresh entry from reading as stale on the very next scan.
     */
    fun upsert(
        entries: Set<String>,
        captureMsByUri: Map<String, Long>,
        modifiedByUri: Map<String, Long?>,
    ): Set<String>? {
        val out = entries.filterTo(HashSet()) { entry ->
            val uri = split(entry)?.first
            uri == null || uri !in captureMsByUri
        }
        var wrote = false
        for ((uri, ms) in captureMsByUri) {
            if (ms <= 0L) continue
            out += encode(uri, ms, modifiedByUri[uri])
            wrote = true
        }
        return if (wrote) out else null
    }

    /**
     * Drop the entries recorded for [uris], plus any entry that no longer parses, so the map stays
     * bounded. Returns null when nothing changed, so the caller can skip a needless persist.
     *
     * Takes the uris to DROP rather than the ones to keep, because the caller decides elsewhere which
     * of them a media scan actually PROVES are deleted (see MediaScanCoverage) and only then re-reads
     * the persisted set to edit it. An entry written in between belongs to no such decision, and a
     * keep-list would delete it unseen — which for a downloaded PNG or WebP takes the only record of
     * its capture date with it.
     */
    fun dropUris(entries: Set<String>, uris: Set<String>): Set<String>? {
        if (entries.isEmpty() || uris.isEmpty()) return null
        val kept = entries.filterTo(HashSet()) { entry ->
            val uri = split(entry)?.first
            uri != null && uri !in uris
        }
        return if (kept.size == entries.size) null else kept
    }
}
