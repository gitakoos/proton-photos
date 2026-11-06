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

package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import eu.akoos.photos.domain.entity.LocalMediaItem

/**
 * One read of the device's photos and videos, and whether it saw all of them.
 *
 * [items] is whatever the read found, for a reader that shows or acts on what is there. [complete]
 * says the read saw every file: both media grants held, each collection read to its end, no row
 * handed back twice (the mark of a read a concurrent change tore, which also pushes rows off the
 * end), and at least one file found, since an empty read is also what a storage volume not yet
 * mounted answers. Only a complete read may stand for "a file it did not return is gone": a step
 * that deletes, demotes or reaps on that absence skips itself otherwise, and the next complete read
 * catches up.
 */
data class DeviceMediaScan(
    val items: List<LocalMediaItem>,
    val complete: Boolean,
) {
    /** When the read began (epoch millis), 0 when unknown: a reader that guards rows written since the scan
     *  uses it, as a scan it is handed may be older than its own request. Not part of equality, so a re-read
     *  that found the same files is still the same scan. */
    var startedAtMs: Long = 0L
}

interface LocalMediaRepository {
    /**
     * Every photo and video on the device, newest first, kept current as MediaStore changes.
     *
     * One list for the whole app: every collector shares the same scan and the same copy, and a new
     * collector gets the list already held at once. That list can trail a change that is still
     * settling, so a reader that acts on the device as it is right now reads [freshMedia] instead.
     */
    fun observeLocalMedia(): Flow<List<LocalMediaItem>>

    /**
     * The [limit] newest photos and videos on the device, newest first, read on their own so a screen can
     * fill in while the whole-library scan behind [observeLocalMedia] is still running. Not the whole
     * device, so a reader that acts on what is absent from it must not use it. Empty when nothing can be read.
     */
    suspend fun newestMedia(limit: Int): List<LocalMediaItem> = emptyList()

    /**
     * Every photo and video on the device, read from MediaStore after this call began, or by a scan that saw
     * the whole device, finished a minute and a half ago at most, and had no change signalled since it began.
     *
     * For a one-shot reader that acts on the device as it is now (a reconcile, an upload pass, a
     * backfill walk), where the list [observeLocalMedia] holds could miss a file that just arrived
     * or still carry one that just went. Calls that overlap share one scan, a scan never runs
     * alongside another, and the result also refreshes what [observeLocalMedia] collectors see.
     * The list is what the scan found even when it could not see everything, so a reader that takes
     * a file's absence for its deletion reads [freshScan] instead. Throws when the scan fails outright.
     */
    suspend fun freshMedia(): List<LocalMediaItem> = freshScan().items

    /**
     * [freshMedia] together with whether the scan saw the whole device ([DeviceMediaScan.complete]):
     * the read for every step that deletes, demotes or reaps because a file is missing from it.
     */
    suspend fun freshScan(): DeviceMediaScan

    /** A scan that began after this call, never one kept from before; for a step that pairs it with another
     *  read of the same moment (the face reaper's trash query) and cannot take the risk of an older one. */
    suspend fun freshScanStrict(): DeviceMediaScan = freshScan()

    fun observeTrashedMedia(): Flow<List<LocalMediaItem>>

    /**
     * How many items [observeTrashedMedia] lists, kept current the same way, for a reader that shows
     * only the number. The device repository counts the trash without building an item per row.
     */
    fun countTrashedMedia(): Flow<Int> = observeTrashedMedia().map { it.size }

    fun hasMediaPermission(): Flow<Boolean>

    /**
     * Which of [uris] a direct look by id confirms are gone from the device, for a step that drops or
     * demotes a row on a scan's word alone: a trashed file is missing from every scan yet still
     * restorable, and a torn read can skip a row without repeating one. A file in the trash counts as
     * present, and a uri that cannot be looked up is never reported gone.
     */
    suspend fun confirmedGone(uris: Collection<String>): Set<String>

    suspend fun queryByUri(uri: String): LocalMediaItem?

    /**
     * The device photos of one MediaStore bucket, newest first.
     *
     * A caller that only needs one folder asks for that folder rather than filtering a whole-library
     * scan down to it: the query is bounded by the folder, so a surface acting on a single folder
     * never materialises every row the device holds.
     */
    suspend fun queryByBucket(bucketName: String): List<LocalMediaItem>

    /** Lowercase SHA-1 hex of the file's plaintext bytes, or null if it can't be read. Lets
     *  reconcile pair a local file with its cloud copy by content after a reinstall wiped the
     *  stored hash — name-independent, so a rename-on-upload cloud copy still matches. */
    suspend fun sha1(uri: String): String?

    /**
     * Asks the shared [observeLocalMedia] list to re-query MediaStore. Use after the user grants the
     * media permission: the MediaStore [android.database.ContentObserver] does not fire on a
     * permission change, so without this trigger the gallery would stay empty until the app
     * restarts. Also the way a side store the scan reads (the capture-date overrides) announces a
     * change the provider itself never sees, so a correction shows without waiting for the next
     * MediaStore event. Does nothing while no one observes the list, since the next observer
     * starts from a new query anyway.
     */
    fun notifyMediaChanged()

    /**
     * Drops [uris], device files the app just removed, from the shared [observeLocalMedia] list at once,
     * ahead of the re-scan that would drop them anyway: on a big library that re-scan takes seconds, and
     * until it lands a photo just removed from the phone still reads as on the phone.
     */
    fun forgetRemoved(uris: Collection<String>) {}

    /**
     * The device list as the last complete scan of an earlier run found it, or null when there is none for
     * the access the app has now. For a first picture of the library while the scan runs, never for acting on
     * what is or is not on the device: a file removed while the app was closed is still in it.
     */
    suspend fun lastKnownMedia(): List<LocalMediaItem>? = null

    /** Asks for a new scan only when the media access or the mounted volumes changed since the last one, as
     *  after a grant given in the system settings while the app kept running. */
    fun refreshIfAccessChanged() {}
}
