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

package eu.akoos.photos.data.face

import eu.akoos.photos.domain.usecase.ReattachFace

/**
 * Intersection-over-union of two axis-aligned boxes given in a photo's 0..1 coordinate space, 0 when
 * they do not overlap or either has no area. Pure and standalone, so every face path that has to decide
 * whether two boxes are the same face shares one overlap rule.
 */
fun iou(
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

/**
 * Binds each carried removed-face box to at most one re-detected face in the same photo whose box
 * overlaps it by at least [minIou], strongest overlap first and each new face taken once, so two removed
 * boxes in one photo cannot both land on a single face. A model swap changes the positional face ids, so
 * this matches purely on geometry. Returns the ids of the re-detected faces to mark removed, and is pure
 * and deterministic, mirroring the overlap rule the named-face reattach uses.
 */
fun matchRejectedByGeometry(
    boxes: List<ReattachBox>,
    faces: List<ReattachFace>,
    minIou: Float,
): List<String> {
    if (boxes.isEmpty() || faces.isEmpty()) return emptyList()
    val facesByPhoto = faces.groupBy { it.photoKey }
    data class Overlap(val faceId: String, val iou: Float, val boxIndex: Int)
    val overlaps = ArrayList<Overlap>()
    boxes.forEachIndexed { boxIndex, box ->
        for (f in facesByPhoto[box.photoKey].orEmpty()) {
            val ov = iou(box.left, box.top, box.right, box.bottom, f.left, f.top, f.right, f.bottom)
            if (ov >= minIou) overlaps.add(Overlap(f.faceId, ov, boxIndex))
        }
    }
    overlaps.sortWith(compareByDescending<Overlap> { it.iou }.thenBy { it.faceId }.thenBy { it.boxIndex })
    val claimedFace = HashSet<String>()
    val usedBox = HashSet<Int>()
    val result = ArrayList<String>()
    for (o in overlaps) {
        if (o.faceId in claimedFace || o.boxIndex in usedBox) continue
        claimedFace.add(o.faceId)
        usedBox.add(o.boxIndex)
        result.add(o.faceId)
    }
    return result
}
