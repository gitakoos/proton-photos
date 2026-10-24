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

package eu.akoos.photos.data.label

import eu.akoos.photos.util.LabelNormalizer

/** One photo's labels and when they last changed; an empty list is a photo whose labels were all taken off. */
data class LabelPhotoState(val labels: List<String>, val changedAt: Long)

/** A label by its spelling, with a time: when it was made (a label with no photo) or when it was deleted. */
data class LabelTimedName(val label: String, val at: Long)

/**
 * An account's labels as the shared backup puts them together with another device's copy, every map keyed so
 * that two devices agree: [photos] by the photo's key, [names] (labels made with no photo) and [deleted] by the
 * label's fold ([LabelNormalizer.fold]), and [applied] by the device id of each other install's copy taken in,
 * at the time that copy was made.
 */
data class LabelSyncState(
    val photos: MutableMap<String, LabelPhotoState> = LinkedHashMap(),
    val names: MutableMap<String, LabelTimedName> = LinkedHashMap(),
    val deleted: MutableMap<String, LabelTimedName> = LinkedHashMap(),
    val applied: MutableMap<String, Long> = LinkedHashMap(),
) {
    /** An independent copy, so a merge can be compared with what it started from. */
    fun deepCopy(): LabelSyncState = LabelSyncState(
        LinkedHashMap(photos),
        LinkedHashMap(names),
        LinkedHashMap(deleted),
        LinkedHashMap(applied),
    )
}

/**
 * The labels' merge rules, the same ones the desktop client keeps, so the phone and the computer end with the
 * same labels whichever saved last. Each local edit carries when it was made; another device's copy is taken in
 * once, and for each photo the later change wins, a label deleted on one device comes off the photos the other
 * labelled before that deletion, and a label made there with no photo is added unless a later deletion outlives
 * it. A part of a copy without a time reads as 0, older than any edit, so an older app's copy only fills gaps.
 * Pure, so every rule runs in a plain JVM test.
 */
object LabelCopyMerge {

    /** Clean, distinct labels in order, the first spelling kept, not yet capped. */
    private fun distinct(labels: List<String>): List<String> {
        val seen = HashSet<String>()
        return labels.mapNotNull { raw -> LabelNormalizer.clean(raw)?.takeIf { seen.add(LabelNormalizer.fold(it)) } }
    }

    private fun capped(labels: List<String>): List<String> = distinct(labels).take(LabelNormalizer.MAX_LABELS)

    /** Whether a label deleted at its time still holds over a part changed at [at]. */
    private fun outlives(deleted: LabelTimedName?, at: Long): Boolean = deleted != null && deleted.at >= at

    private fun carried(state: LabelSyncState, fold: String): Boolean =
        state.photos.values.any { photo -> photo.labels.any { LabelNormalizer.fold(it) == fold } }

    /** A label put back on purpose clears its deletion. */
    private fun undelete(state: LabelSyncState, label: String, at: Long) {
        val fold = LabelNormalizer.fold(label)
        val deleted = state.deleted[fold] ?: return
        if (deleted.at <= at) state.deleted.remove(fold)
    }

    /** Sets a photo's labels to [labels], as of [at]. True when anything changed. */
    fun setLabels(state: LabelSyncState, key: String, labels: List<String>, at: Long): Boolean {
        val next = capped(labels)
        val now = state.photos[key]
        if (if (now != null) now.labels == next else next.isEmpty()) return false
        state.photos[key] = LabelPhotoState(next, at)
        for (label in next) undelete(state, label, at)
        return true
    }

    /** Adds [labels] after what each photo of [keys] carries, while there is room. Returns (changed, full) counts. */
    fun addLabels(state: LabelSyncState, keys: List<String>, labels: List<String>, at: Long): Pair<Int, Int> {
        var changed = 0
        var full = 0
        val incoming = distinct(labels)
        for (key in keys) {
            val merged = distinct(state.photos[key]?.labels.orEmpty() + incoming)
            if (merged.size > LabelNormalizer.MAX_LABELS) full++
            if (setLabels(state, key, merged.take(LabelNormalizer.MAX_LABELS), at)) changed++
        }
        return changed to full
    }

    /** Takes [labels] off each photo of [keys]. Returns how many photos changed. */
    fun removeLabels(state: LabelSyncState, keys: List<String>, labels: List<String>, at: Long): Int {
        val gone = labels.mapTo(HashSet()) { LabelNormalizer.fold(it) }
        var changed = 0
        for (key in keys) {
            val current = state.photos[key]?.labels ?: continue
            if (setLabels(state, key, current.filter { LabelNormalizer.fold(it) !in gone }, at)) changed++
        }
        return changed
    }

    /** Makes a label by name, which stays listed at no photos. Returns its clean spelling, or null. */
    fun createLabel(state: LabelSyncState, raw: String, at: Long): String? {
        val label = LabelNormalizer.clean(raw) ?: return null
        undelete(state, label, at)
        state.names.putIfAbsent(LabelNormalizer.fold(label), LabelTimedName(label, at))
        return label
    }

    /** Deletes a label from every photo and the list. True when it was there. */
    fun deleteLabel(state: LabelSyncState, label: String, at: Long): Boolean {
        val fold = LabelNormalizer.fold(label)
        var found = state.names.remove(fold) != null
        for ((key, photo) in state.photos.entries.toList()) {
            if (photo.labels.none { LabelNormalizer.fold(it) == fold }) continue
            found = true
            state.photos[key] = LabelPhotoState(photo.labels.filter { LabelNormalizer.fold(it) != fold }, at)
        }
        if (found) state.deleted[fold] = LabelTimedName(label, at)
        return found
    }

    /**
     * Renames a label on every photo that carries it; a photo that already has the new name keeps it once. The
     * old name counts as deleted, so another device's older copy does not bring it back. Returns the new
     * spelling, or null when it is not a label.
     *
     * The deletion is recorded a millisecond before the photos change: another device takes a copy's deletions
     * before its photos and stamps each photo it takes the old name off with the deletion's time, so a deletion
     * as late as the photos' own change would leave those photos as they are there, holding neither name.
     */
    fun renameLabel(state: LabelSyncState, from: String, to: String, at: Long): String? {
        val label = LabelNormalizer.clean(to) ?: return null
        val oldFold = LabelNormalizer.fold(from)
        val swap = { l: String -> if (LabelNormalizer.fold(l) == oldFold) label else l }
        if (oldFold == LabelNormalizer.fold(label)) {
            for ((key, photo) in state.photos.entries.toList()) {
                if (photo.labels.any { LabelNormalizer.fold(it) == oldFold && it != label }) {
                    state.photos[key] = LabelPhotoState(photo.labels.map(swap), at)
                }
            }
            state.names[oldFold]?.let { state.names[oldFold] = LabelTimedName(label, at) }
            return label
        }
        val hadName = state.names.containsKey(oldFold)
        for ((key, photo) in state.photos.entries.toList()) {
            if (photo.labels.any { LabelNormalizer.fold(it) == oldFold }) setLabels(state, key, photo.labels.map(swap), at)
        }
        state.names.remove(oldFold)
        state.deleted[oldFold] = LabelTimedName(from, at - 1)
        if (hadName) createLabel(state, label, at) else undelete(state, label, at)
        return label
    }

    /**
     * Takes another install's [copy] into [state], once per copy: deletions first, then each photo whose labels
     * changed there later than here, then the labels made there with no photo. A device key (a `content://` uri)
     * names a photo on that one phone and is passed over, and this install's own copy ([ownDeviceId]) is never
     * taken in. True when a photo's labels, a name or a deletion changed.
     */
    fun takeIn(state: LabelSyncState, copy: LabelSnapshot, ownDeviceId: String): Boolean {
        if (copy.deviceId.isEmpty() || copy.deviceId == ownDeviceId) return false
        if ((state.applied[copy.deviceId] ?: -1L) >= copy.createdAtMillis) return false
        var changed = false
        // The photos this copy's deletions just stamped with a deletion's time: a photo the same copy lists as
        // changed at that very time (a rename writes both together) still takes the copy's list.
        val stampedByDeletion = HashMap<String, Long>()
        for (entry in copy.deleted) {
            val name = entry.name.trim()
            val fold = LabelNormalizer.fold(name)
            val at = entry.deletedAtMillis ?: copy.createdAtMillis
            if (fold.isEmpty() || outlives(state.deleted[fold], at)) continue
            state.deleted[fold] = LabelTimedName(name, at)
            state.names[fold]?.let { if (it.at <= at) state.names.remove(fold) }
            for ((key, photo) in state.photos.entries.toList()) {
                if (photo.changedAt > at || photo.labels.none { LabelNormalizer.fold(it) == fold }) continue
                state.photos[key] = LabelPhotoState(photo.labels.filter { LabelNormalizer.fold(it) != fold }, at)
                stampedByDeletion[key] = at
            }
            changed = true
        }
        for (photo in copy.photos) {
            if (!LabelSnapshotMerge.isPortableKey(photo.key)) continue
            val here = state.photos[photo.key]
            val tiedWithDeletion = stampedByDeletion[photo.key] == photo.changedAtMillis
            if (here != null && here.changedAt >= photo.changedAtMillis && !tiedWithDeletion) continue
            val labels = distinct(photo.labels)
                .filter { !outlives(state.deleted[LabelNormalizer.fold(it)], photo.changedAtMillis) }
                .take(LabelNormalizer.MAX_LABELS)
            if (here == null && labels.isEmpty()) continue
            if (here != null && here.labels == labels) {
                state.photos[photo.key] = here.copy(changedAt = photo.changedAtMillis)
                continue
            }
            state.photos[photo.key] = LabelPhotoState(labels, photo.changedAtMillis)
            changed = true
        }
        for (name in copy.names) {
            val label = LabelNormalizer.clean(name) ?: continue
            val fold = LabelNormalizer.fold(label)
            val at = copy.nameTimes[name] ?: copy.nameTimes[name.trim()] ?: 0L
            if (state.names.containsKey(fold) || outlives(state.deleted[fold], at)) continue
            state.names[fold] = LabelTimedName(label, at)
            changed = true
        }
        state.applied[copy.deviceId] = copy.createdAtMillis
        return changed
    }

    /**
     * [state] as this install's copy: every photo with its labels and change time, a photo whose labels were taken
     * off as an empty list, the labels made by name that no photo carries with when they were made, and the
     * deleted labels with when. Photos are ordered by key, so an unchanged state writes the same copy.
     */
    fun forDrive(state: LabelSyncState, deviceId: String, deviceLabel: String, createdAt: Long): LabelSnapshot {
        val names = state.names.entries
            .filter { (fold, _) -> !carried(state, fold) }
            .map { it.value }
        return LabelSnapshot(
            deviceId = deviceId,
            deviceLabel = deviceLabel,
            createdAtMillis = createdAt,
            photos = state.photos.entries.sortedBy { it.key }
                .map { (key, photo) -> LabelSnapshotPhoto(key, photo.labels, photo.changedAt) },
            names = names.map { it.label },
            nameTimes = names.associate { it.label to it.at },
            deleted = state.deleted.entries.sortedBy { it.key }
                .map { LabelSnapshotDeleted(it.value.label, it.value.at) },
        )
    }

    /** The labels each photo carries, keyed as [state] keys them, for comparing two states. */
    fun view(state: LabelSyncState): Map<String, List<String>> = state.photos.mapValues { it.value.labels }
}
