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

import eu.akoos.photos.domain.usecase.REATTACH_MIN_IOU
import eu.akoos.photos.domain.usecase.boxIou
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.domain.usecase.normalize
import eu.akoos.photos.domain.usecase.unpackEmbedding
import kotlin.math.abs

/*
 * How two devices that both looked at a photo end up holding the same faces for it.
 *
 * Each device finds a photo's faces on its own, so a photo scanned on two devices before their copies met
 * holds two sets of the same faces: other boxes, other vectors, and ids (photo key + "#" + index) that
 * agree only by chance. A name travels by face, so two sets keep names from crossing reliably.
 *
 * The sets are brought together once. Of the two devices, the one whose device id sorts first keeps its
 * set; the other takes it in place of its own, carrying its own names, removals and marks onto the faces
 * that are the same, and keeping every face of its own that has no twin there under a free id. The device
 * that keeps its set takes in the faces the other found and it did not. After that both hold one set under
 * one set of ids, and everything set by hand afterwards crosses by id.
 *
 * Every device runs the same rules on the same two device ids, so they agree without talking to each other,
 * and the rules are the same in the desktop app.
 */

/** Below this box overlap two faces are the same only when their vectors say so as well. */
internal const val SAME_FACE_LOOSE_IOU = 0.3f

/** The vector similarity two faces whose boxes overlap only loosely need to be taken as the same face. */
internal const val SAME_FACE_MIN_COSINE = 0.7f

/** How far apart two box edges may be and still be the same box after a copy's 32-bit floats. */
internal const val SAME_BOX_TOLERANCE = 1e-4f

/** A face as these rules read it: its id, where it sits on its photo, and its recognition vector. */
internal class SetFace(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    embedding: ByteArray,
) {
    /** The vector at unit length, empty when it is not one of this model's. */
    val direction: FloatArray by lazy { unpackEmbedding(embedding).let { if (it.isEmpty()) it else normalize(it) } }
}

/**
 * Whether the copy written by [fileDeviceId] holds the set this device takes in place of its own: the device
 * whose id sorts first keeps its set. Never for an unknown id or this device's own earlier copy.
 */
internal fun incomingSetWins(fileDeviceId: String?, ownDeviceId: String?): Boolean =
    !fileDeviceId.isNullOrBlank() && !ownDeviceId.isNullOrBlank() && fileDeviceId < ownDeviceId

private fun overlap(a: SetFace, b: SetFace): Float = boxIou(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

private fun similarity(a: SetFace, b: SetFace): Float {
    val x = a.direction
    val y = b.direction
    return if (x.isEmpty() || x.size != y.size) Float.NaN else cosineSimilarity(x, y)
}

/** Whether [a] and [b], on the same photo, are the same face: boxes that overlap by half, or loosely
 *  overlapping boxes whose vectors agree. */
internal fun sameFace(a: SetFace, b: SetFace): Boolean {
    val iou = overlap(a, b)
    return iou >= REATTACH_MIN_IOU || (iou >= SAME_FACE_LOOSE_IOU && similarity(a, b) >= SAME_FACE_MIN_COSINE)
}

/**
 * Pairs [incoming] faces with [local] faces of one photo, each face once: first every two boxes that
 * overlap by half, the strongest overlap first, then, when the vectors compare ([loose]), the rest whose
 * boxes overlap loosely and whose vectors agree, the closest vectors first. Ties go to the lower index.
 * The desktop pairs by the very same steps (`pairOnPhoto`), so both devices pick the same twins. Returns
 * incoming id to local id.
 */
internal fun pairSameFaces(
    photoKey: String,
    incoming: List<SetFace>,
    local: List<SetFace>,
    loose: Boolean = true,
): Map<String, String> {
    if (incoming.isEmpty() || local.isEmpty()) return emptyMap()
    val indexOf = { face: SetFace -> faceIndexIn(face.id, photoKey) ?: Int.MAX_VALUE }
    val pairs = HashMap<String, String>()
    val takenLocal = HashSet<String>()
    fun take(options: List<Triple<SetFace, SetFace, Float>>) {
        val ordered = options.sortedWith(
            compareByDescending<Triple<SetFace, SetFace, Float>> { it.third }
                .thenBy { indexOf(it.first) }
                .thenBy { indexOf(it.second) },
        )
        for ((a, b) in ordered) {
            if (a.id in pairs || b.id in takenLocal) continue
            pairs[a.id] = b.id
            takenLocal += b.id
        }
    }
    val close = ArrayList<Triple<SetFace, SetFace, Float>>()
    for (a in incoming) for (b in local) {
        val iou = overlap(a, b)
        if (iou >= REATTACH_MIN_IOU) close += Triple(a, b, iou)
    }
    take(close)
    if (!loose) return pairs
    val near = ArrayList<Triple<SetFace, SetFace, Float>>()
    for (a in incoming) {
        if (a.id in pairs) continue
        for (b in local) {
            if (b.id in takenLocal || overlap(a, b) < SAME_FACE_LOOSE_IOU) continue
            val similarity = similarity(a, b)
            if (similarity >= SAME_FACE_MIN_COSINE) near += Triple(a, b, similarity)
        }
    }
    take(near)
    return pairs
}

/** Whether this device already holds every one of [incoming] faces as it is, under its id and in its box:
 *  the two sets were brought together before, and only what was set by hand since is left to cross. */
internal fun holdsIncomingSet(incoming: List<SetFace>, local: List<SetFace>): Boolean {
    val byId = local.associateBy { it.id }
    return incoming.all { face ->
        val here = byId[face.id] ?: return@all false
        abs(here.left - face.left) <= SAME_BOX_TOLERANCE &&
            abs(here.top - face.top) <= SAME_BOX_TOLERANCE &&
            abs(here.right - face.right) <= SAME_BOX_TOLERANCE &&
            abs(here.bottom - face.bottom) <= SAME_BOX_TOLERANCE
    }
}

/**
 * Free ids on [photoKey] for [faces] (ids of faces that move onto it), none of them in [taken]: each face's
 * own index when that is free, else the lowest free one. Returns old id to new id.
 */
internal fun freeFaceIds(photoKey: String, faces: List<String>, taken: Set<String>): Map<String, String> {
    val used = HashSet(taken)
    val out = LinkedHashMap<String, String>()
    val ordered = faces.sortedWith(compareBy<String> { faceIndexIn(it, photoKey) ?: Int.MAX_VALUE }.thenBy { it })
    for (id in ordered) {
        val own = faceIndexIn(id, photoKey)?.let { "$photoKey#$it" } ?: continue
        if (used.add(own)) out[id] = own
    }
    var next = 0
    for (id in ordered) {
        if (id in out) continue
        while ("$photoKey#$next" in used) next++
        val fresh = "$photoKey#$next"
        used += fresh
        out[id] = fresh
    }
    return out
}

/** How a photo's own faces give way to the set this device takes in. */
internal class FaceSetAdoption(
    /** Incoming face id to the face here that is the same face, whose names and marks it takes over. */
    val carried: Map<String, String>,
    /** A face here with no twin among the incoming ones to the free id it stays under. */
    val kept: Map<String, String>,
)

/** Plans taking [incoming] in place of [local] on [photoKey], losing no face found here. */
internal fun planFaceSetAdoption(photoKey: String, incoming: List<SetFace>, local: List<SetFace>): FaceSetAdoption {
    val carried = pairSameFaces(photoKey, incoming, local)
    val paired = carried.values.toHashSet()
    val alone = local.filter { it.id !in paired }.map { it.id }
    return FaceSetAdoption(carried, freeFaceIds(photoKey, alone, incoming.mapTo(HashSet()) { it.id }))
}

/**
 * The [incoming] faces of [photoKey] this device lacks: every one not paired with a face here ([paired],
 * incoming id to local id, paired against all of [local], removed ones too). Pairing takes every same face
 * it can, so an unpaired face is none of the faces here, or a second detection the other device holds and
 * this one must hold too for the two sets to be one. Each gets its own id when that is free here, else the
 * lowest free one. Returns incoming id to the id it is written under.
 */
internal fun planIncomingAdds(
    photoKey: String,
    incoming: List<SetFace>,
    local: List<SetFace>,
    paired: Map<String, String>,
): Map<String, String> {
    val missing = incoming.filter { it.id !in paired }
    if (missing.isEmpty()) return emptyMap()
    return freeFaceIds(photoKey, missing.map { it.id }, local.mapTo(HashSet()) { it.id })
}

/** Where a face of the set taken in ends up: removed from People or not, the name confirmed for it, and the
 *  named person it is put under (null: the grouping it had here, if any, stays). */
internal data class CarriedCuration(val rejected: Boolean, val confirmedName: String?, val groupedName: String?)

/**
 * The curation of a face of the incoming set, given the face here it is the same as ([local], null when
 * none) and what the other device set for it ([snapshot]). What was decided by hand here stays, as on any
 * merge ([mergeFaceCuration]); the other device fills what is open.
 */
internal fun carriedCuration(local: LocalFaceCuration?, snapshot: SnapshotFaceCuration): CarriedCuration {
    if (local == null) {
        return CarriedCuration(
            rejected = snapshot.rejected,
            confirmedName = snapshot.confirmedName,
            groupedName = if (snapshot.rejected) null else snapshot.personName,
        )
    }
    val change = mergeFaceCuration(local, snapshot)
    val rejected = local.rejected || change == FaceMergeChange.Remove
    val confirmed = local.confirmedName ?: (change as? FaceMergeChange.Confirm)?.name
    val grouped = when {
        rejected -> null
        change is FaceMergeChange.Confirm -> change.name
        change is FaceMergeChange.Group -> change.name
        else -> null
    }
    return CarriedCuration(rejected, confirmed, grouped)
}
