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

/**
 * Reattaches user-given face names to a freshly detected set of faces without an embedding, so the
 * heavy work of naming and grouping survives a recognition-model change. A model swap rebuilds every
 * embedding, which would otherwise orphan the names bound to the old vectors; this rebinds them by the
 * one thing a swap does not touch, which face sits where in which photo.
 *
 * The rule is deliberately conservative. A label binds to the local face with the SAME stable id first:
 * the id is `photoKey#detectionIndex`, so an unchanged detector reproduces it exactly and an
 * embedder-only swap reattaches with no geometry at all. That id match is taken only when the photo
 * holds one face or the boxes still overlap, since a detector swap can renumber a multi-face photo onto
 * a different face; otherwise, and whenever no id matches at all, it falls back to the best-overlapping
 * box in the same photo, which is what carries a name across a detector change that shifts the ids but
 * not the faces on the wall. Everything here is pure and deterministic so the whole rule runs in a plain
 * JVM test.
 */

/** The lowest box overlap that still counts two boxes as the same face across a detector change. Set
 *  high so only a clear match rebinds a name; a weak overlap leaves the face unnamed for the user to
 *  confirm rather than mislabelling it. */
internal const val REATTACH_MIN_IOU = 0.5f

/** A name the user gave a face, tagged with where that face was, so it can be found again after a
 *  re-detection. Boxes are 0..1 fractions of the photo, the same form the face rows store. */
data class ReattachLabel(
    val faceId: String,
    val photoKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val name: String,
)

/** A freshly detected face a label may bind to. */
data class ReattachFace(
    val faceId: String,
    val photoKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Intersection-over-union of two boxes, 0 when they do not overlap or either has no area. */
internal fun boxIou(
    aLeft: Float, aTop: Float, aRight: Float, aBottom: Float,
    bLeft: Float, bTop: Float, bRight: Float, bBottom: Float,
): Float {
    val interLeft = maxOf(aLeft, bLeft)
    val interTop = maxOf(aTop, bTop)
    val interRight = minOf(aRight, bRight)
    val interBottom = minOf(aBottom, bBottom)
    val interW = interRight - interLeft
    val interH = interBottom - interTop
    if (interW <= 0f || interH <= 0f) return 0f
    val inter = interW * interH
    val areaA = (aRight - aLeft) * (aBottom - aTop)
    val areaB = (bRight - bLeft) * (bBottom - bTop)
    val union = areaA + areaB - inter
    return if (union > 0f) inter / union else 0f
}

/** True when a same-id match cannot cross names, so binding by id alone is safe: the photo holds at most
 *  one new face, or the label box still overlaps the id-matched face box at or above [minIou]. A detector
 *  change can renumber a multi-face photo so an id lands on a physically different face; that fails here
 *  and defers to the geometry pass rather than naming the wrong face. */
private fun idMatchIsSafe(
    label: ReattachLabel,
    face: ReattachFace,
    photoFaces: List<ReattachFace>,
    minIou: Float,
): Boolean {
    if (photoFaces.size <= 1) return true
    val iou = boxIou(
        label.left, label.top, label.right, label.bottom,
        face.left, face.top, face.right, face.bottom,
    )
    return iou >= minIou
}

/**
 * Binds each of [labels] to at most one of [faces] that should carry its name, and each face to at most
 * one name. Same-id matches are taken first (an unchanged detector reproduces the id), but only when the
 * photo holds one face or the id-matched boxes still overlap at or above [minIou], so a detector swap
 * that renumbers a multi-face photo cannot bind a name to a different face; whatever the id pass leaves
 * falls to the strongest same-photo box overlap at or above [minIou]. A face already claimed by an id
 * match is never re-used by a weaker overlap, and among overlaps the strongest pairing wins, so a photo
 * with two people cannot cross its names. Order-independent and deterministic. Returns local-faceId to name.
 */
fun matchReattachLabels(
    labels: List<ReattachLabel>,
    faces: List<ReattachFace>,
    minIou: Float = REATTACH_MIN_IOU,
): Map<String, String> {
    if (labels.isEmpty() || faces.isEmpty()) return emptyMap()

    val facesById = faces.associateBy { it.faceId }
    val facesByPhoto = faces.groupBy { it.photoKey }
    val assigned = HashMap<String, String>()
    val claimed = HashSet<String>()

    // 1. Exact stable-id match, guarded by geometry: an embedder-only swap keeps the detector, so
    //    `photoKey#index` is reproduced verbatim and the name rebinds with no geometry. A detector swap
    //    can renumber a multi-face photo, so an id match there is taken only when the photo holds one
    //    face or the boxes still overlap; otherwise the label falls to the geometry pass instead of
    //    binding a name to a physically different face.
    val needGeometry = ArrayList<ReattachLabel>()
    for (label in labels.sortedBy { it.faceId }) {
        val face = facesById[label.faceId]
        if (face != null && face.faceId !in claimed &&
            idMatchIsSafe(label, face, facesByPhoto[face.photoKey].orEmpty(), minIou)
        ) {
            assigned[face.faceId] = label.name
            claimed.add(face.faceId)
        } else {
            needGeometry.add(label)
        }
    }

    // 2. Best same-photo overlap for whatever the id pass could not place, strongest pair first so a
    //    contested face goes to its closest label rather than to whichever was visited first.
    data class Candidate(val faceId: String, val name: String, val iou: Float, val labelFaceId: String)
    val candidates = ArrayList<Candidate>()
    for (label in needGeometry) {
        for (face in facesByPhoto[label.photoKey].orEmpty()) {
            if (face.faceId in claimed) continue
            val iou = boxIou(
                label.left, label.top, label.right, label.bottom,
                face.left, face.top, face.right, face.bottom,
            )
            if (iou >= minIou) candidates.add(Candidate(face.faceId, label.name, iou, label.faceId))
        }
    }
    candidates.sortWith(
        compareByDescending<Candidate> { it.iou }.thenBy { it.faceId }.thenBy { it.labelFaceId },
    )
    val usedLabel = HashSet<String>()
    for (c in candidates) {
        if (c.faceId in claimed || c.labelFaceId in usedLabel) continue
        assigned[c.faceId] = c.name
        claimed.add(c.faceId)
        usedLabel.add(c.labelFaceId)
    }
    return assigned
}
