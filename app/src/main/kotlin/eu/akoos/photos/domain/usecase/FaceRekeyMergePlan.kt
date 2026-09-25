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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.dao.CuratedFace
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.mlsync.FaceMergeChange
import eu.akoos.photos.data.mlsync.LocalFaceCuration
import eu.akoos.photos.data.mlsync.ScanMarkerChange
import eu.akoos.photos.data.mlsync.SnapshotFaceCuration
import eu.akoos.photos.data.mlsync.faceIndexIn
import eu.akoos.photos.data.mlsync.matchSnapshotFaces
import eu.akoos.photos.data.mlsync.mergeFaceCuration
import eu.akoos.photos.data.mlsync.notPersonMarkApplies
import eu.akoos.photos.data.mlsync.scanMarkerChange

/**
 * How one photo's device-keyed face data folds into its cloud-keyed face data when both copies were
 * scanned, which a restored face snapshot causes: it writes the cloud copy's faces while this device has
 * scanned its own copy. The cloud-keyed set is the one that stays, since the gallery, the export and the
 * people grid read that key. Written by [planFaceRekeyMerge].
 */
internal data class FaceRekeyMergePlan(
    /** Device face to the cloud face it is: its curation fills the cloud face's gaps, then the row goes. */
    val merged: Map<String, String>,
    /** Device face the cloud copy lacks to the free id under the cloud key it moves to, curation kept. */
    val moved: Map<String, String>,
    /** Name to the cloud faces the device side confirms as it. */
    val confirm: Map<String, List<String>>,
    /** Cloud faces the device side removes from People. */
    val remove: List<String>,
    /** Not-a-person marks the device side carries onto the faces that stay, none already present. */
    val marks: List<NotPersonEntity>,
    /** Person to the face its cover moves to, for each person whose cover is a device face. */
    val covers: Map<Long, String>,
    /** Whether the cloud key's marker takes the device marker's hi-res swept flag. */
    val raiseHiRes: Boolean,
    /** People whose photos, faces or cover changed, so their count, cover and centroid are refreshed. */
    val people: Set<Long>,
)

/**
 * Plans folding the faces on [oldKey] ([device]) into the faces on [newKey] ([cloud]).
 *
 * The two sets pair by box through [matchSnapshotFaces], removed faces included, so a face removed on the
 * cloud side keeps its device twin from coming back as a second, kept face. Each pair's curation merges
 * through [mergeFaceCuration] with the cloud face as the local side: its confirmation and its removal win,
 * even over a device face confirmed as someone else, and the device side only fills a gap. The device
 * face then goes. A device face with no pair moves whole to a free id under [newKey]: its own index when
 * that is free there, else the lowest free one.
 *
 * The device faces' not-a-person marks ([deviceMarks]) follow them, each landing once and never on a cloud
 * face confirmed as that very name; a mark on no face of the photo is dropped. A person whose cover is a
 * device face ([deviceCovers]) takes the face it lands on. The cloud marker takes the device marker's
 * hi-res flag ([deviceHiRes]). [namedPeople] (person id to name) names the grouping on either side and
 * the people the photo is attached to by hand ([deviceManualNames]), whose counts change with the move.
 */
internal fun planFaceRekeyMerge(
    oldKey: String,
    newKey: String,
    device: List<CuratedFace>,
    cloud: List<CuratedFace>,
    deviceMarks: List<NotPersonEntity> = emptyList(),
    cloudMarks: List<NotPersonEntity> = emptyList(),
    namedPeople: Map<Long, String> = emptyMap(),
    deviceManualNames: Set<String> = emptySet(),
    deviceCovers: Map<Long, String> = emptyMap(),
    deviceHiRes: Boolean = false,
): FaceRekeyMergePlan {
    // Both sets sit on one photo, so they are paired on the cloud key; the ids differ by key, so the
    // pairing is by box alone, the strongest overlap first and each face once.
    val merged = matchSnapshotFaces(
        snapshot = device.map { it.onPhoto(newKey) },
        local = cloud.map { it.onPhoto(newKey) },
    )
    val cloudById = cloud.associateBy { it.id }
    val cloudBlocked = cloudMarks.groupBy({ it.faceId }, { it.personName })
    val confirm = LinkedHashMap<String, MutableList<String>>()
    val remove = ArrayList<String>()
    val confirmedAfter = HashMap<String, String?>()
    for (face in device) {
        val cloudId = merged[face.id] ?: continue
        val target = cloudById.getValue(cloudId)
        val change = mergeFaceCuration(
            local = LocalFaceCuration(
                rejected = target.rejected,
                confirmedName = target.confirmedName(),
                groupedName = target.personId?.let(namedPeople::get),
                blockedNames = cloudBlocked[cloudId].orEmpty().toSet(),
            ),
            snapshot = SnapshotFaceCuration(
                personName = face.personId?.let(namedPeople::get),
                confirmedName = face.confirmedName(),
                rejected = face.rejected,
            ),
        )
        when (change) {
            is FaceMergeChange.Confirm -> confirm.getOrPut(change.name) { ArrayList() } += cloudId
            FaceMergeChange.Remove -> remove += cloudId
            FaceMergeChange.Keep -> Unit
        }
        confirmedAfter[cloudId] = (change as? FaceMergeChange.Confirm)?.name ?: target.confirmedName()
    }

    val unpaired = device.filter { it.id !in merged }
        .sortedWith(compareBy<CuratedFace> { faceIndexIn(it.id, oldKey) ?: Int.MAX_VALUE }.thenBy { it.id })
    val taken = cloud.mapTo(HashSet()) { it.id }
    val moved = LinkedHashMap<String, String>()
    // A face whose own index is free under the cloud key keeps it before any other face takes a free id.
    for (face in unpaired) {
        val own = faceIndexIn(face.id, oldKey)?.let { "$newKey#$it" } ?: continue
        if (taken.add(own)) moved[face.id] = own
    }
    var next = 0
    for (face in unpaired) {
        if (face.id in moved) continue
        while ("$newKey#$next" in taken) next++
        val id = "$newKey#$next"
        taken += id
        moved[face.id] = id
    }

    val landsOn = merged + moved
    val present = cloudMarks.mapTo(HashSet()) { it.personName to it.faceId }
    val marks = ArrayList<NotPersonEntity>()
    for (mark in deviceMarks) {
        val target = landsOn[mark.faceId] ?: continue
        if (mark.faceId in merged && !notPersonMarkApplies(confirmedAfter[target], mark.personName)) continue
        if (present.add(mark.personName to target)) marks += mark.copy(faceId = target)
    }

    val covers = LinkedHashMap<Long, String>()
    for ((personId, faceId) in deviceCovers) landsOn[faceId]?.let { covers[personId] = it }

    val people = LinkedHashSet<Long>()
    device.mapNotNullTo(people) { it.personId }
    remove.mapNotNullTo(people) { cloudById[it]?.personId }
    people += covers.keys
    for ((personId, name) in namedPeople) if (name in deviceManualNames) people += personId

    return FaceRekeyMergePlan(
        merged = merged,
        moved = moved,
        confirm = confirm,
        remove = remove,
        marks = marks,
        covers = covers,
        raiseHiRes = scanMarkerChange(localMarker = true, snapshotHiRes = deviceHiRes) == ScanMarkerChange.RaiseHiRes,
        people = people,
    )
}

private fun CuratedFace.confirmedName(): String? = manualName?.takeIf { it.isNotBlank() }

private fun CuratedFace.onPhoto(photoKey: String) =
    ReattachFace(id, photoKey, boxLeft, boxTop, boxRight, boxBottom)
