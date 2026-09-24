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

import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_PARAMS_VERSION
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.REATTACH_MIN_IOU
import eu.akoos.photos.domain.usecase.ReattachFace
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.boxIou
import eu.akoos.photos.domain.usecase.isPortableFaceKey
import eu.akoos.photos.domain.usecase.matchReattachLabels

/*
 * The decisions a face snapshot import makes, kept pure so each rule runs in a plain JVM test: which
 * snapshot a device can take whole and which only by name, which rows a Merge adds, how a snapshot face
 * lands on a face this device already holds, and how the index's own encodings (the landmark string, the
 * `photoKey#index` face id) map onto the snapshot's.
 */

/** How a restore treats the face data this device already holds for the account. */
enum class FaceSnapshotMode {
    /** The snapshot's faces, names and curation replace this device's own for every cloud photo. The
     *  device-only photos, which no snapshot carries, keep theirs. */
    Replace,

    /** Faces are added only for the photos this device has not scanned. On the photos it has, the
     *  snapshot's names and curation land on the local face whose box overlaps, and the names this device
     *  confirmed win. */
    Merge,
}

/**
 * Whether a restore has to ask Merge or Replace. A Replace clears every face, scan marker and "not this
 * person" mark of the cloud photos, names or not, and lets go of the names still waiting to be put back
 * on this device's faces, so it goes ahead unasked only on a fresh device: no face of a cloud photo here
 * ([holdsCloudFaces]) and no names waiting ([reattachPending]). Otherwise the user chooses, Merge first.
 */
fun restoreNeedsChoice(holdsCloudFaces: Boolean, reattachPending: Boolean): Boolean =
    holdsCloudFaces || reattachPending

/**
 * The mode of an import that cannot ask: a Replace on a fresh device, where it loses nothing, and
 * otherwise a Merge, the answer the question starts from, so this device's own curation stays.
 */
fun defaultRestoreMode(needsChoice: Boolean): FaceSnapshotMode =
    if (needsChoice) FaceSnapshotMode.Merge else FaceSnapshotMode.Replace

/** What a snapshot's face pipeline lets an import take. */
enum class FaceSnapshotFit {
    /** The same pipeline and recognition model: faces, embeddings, names and curation all import. */
    Full,

    /** Another pipeline, model or embedding width: the vectors do not compare with this device's, so only
     *  the names and curation carry over, rebound to this device's own faces by where each face sat. */
    NamesOnly,
}

/**
 * The fit of a snapshot written under [header] on a device running face pipeline [localModelVersion]
 * with the recognition model [localModelSha] at [localEmbeddingDim] components. The digests count only
 * when both are present, the rule the v3 file follows: a blank digest (an unpinned model) cannot prove a
 * mismatch.
 */
internal fun faceSnapshotFit(
    header: MlSnapshotHeader.Faces,
    localModelVersion: Int = FACE_MODEL_VERSION,
    localModelSha: String = FaceModelAssets.EMBED_MODEL.sha256,
    localEmbeddingDim: Int = FACE_EMBEDDING_DIM,
): FaceSnapshotFit {
    val shaDiffers = header.faceModelSha256.isNotBlank() && localModelSha.isNotBlank() &&
        !header.faceModelSha256.equals(localModelSha, ignoreCase = true)
    val samePipeline = header.faceModelVersion == localModelVersion &&
        header.embeddingDim == localEmbeddingDim &&
        !shaDiffers
    return if (samePipeline) FaceSnapshotFit.Full else FaceSnapshotFit.NamesOnly
}

/** Whether the snapshot's people were grouped under other clustering parameters than this device's, so
 *  the import asks for a regroup here. */
internal fun snapshotNeedsRegroup(
    header: MlSnapshotHeader.Faces,
    localClusterParamsVersion: Int = FACE_CLUSTER_PARAMS_VERSION,
): Boolean = header.clusterParamsVersion != localClusterParamsVersion

/** Whether a snapshot photo's face rows are imported as they stand: always on Replace, and on Merge only
 *  for a photo this device has not scanned. */
internal fun importsFaceRows(mode: FaceSnapshotMode, scannedHere: Boolean): Boolean =
    mode == FaceSnapshotMode.Replace || !scannedHere

/** A face this device holds, as far as a merge decision reads it. */
internal data class LocalFaceCuration(
    /** Removed from People here. */
    val rejected: Boolean = false,
    /** The name confirmed for it here by hand. */
    val confirmedName: String? = null,
    /** The name of the named person it is grouped under here. */
    val groupedName: String? = null,
    /** The names it is marked "not this person" for here. */
    val blockedNames: Set<String> = emptySet(),
)

/** A snapshot face's names and curation. */
internal data class SnapshotFaceCuration(
    val personName: String?,
    val confirmedName: String?,
    val rejected: Boolean,
)

/** What a Merge does to a local face a snapshot face overlaps. */
internal sealed interface FaceMergeChange {
    /** Confirm the local face as [name]. */
    data class Confirm(val name: String) : FaceMergeChange

    /** Remove the local face from People. */
    data object Remove : FaceMergeChange

    /** Leave the local face as it is. */
    data object Keep : FaceMergeChange
}

/**
 * How a snapshot face's curation lands on the local face it overlaps. This device's own curation wins: a
 * face it removed stays removed, and a face it confirmed keeps its name and is never removed by the
 * snapshot, the rule the v3 import keeps. Otherwise a removal in the snapshot removes the face here, a
 * name confirmed in the snapshot is confirmed here, and the named person the snapshot grouped the face
 * under is taken only when this device has not grouped it under a named person of its own, since both
 * groupings are automatic. A name this device marked the face as not being is never put on it.
 */
internal fun mergeFaceCuration(local: LocalFaceCuration, snapshot: SnapshotFaceCuration): FaceMergeChange {
    if (local.rejected || local.confirmedName != null) return FaceMergeChange.Keep
    if (snapshot.rejected) return FaceMergeChange.Remove
    val confirmed = snapshot.confirmedName
    if (confirmed != null) {
        return if (confirmed in local.blockedNames) FaceMergeChange.Keep else FaceMergeChange.Confirm(confirmed)
    }
    val grouped = snapshot.personName ?: return FaceMergeChange.Keep
    return if (local.groupedName == null && grouped !in local.blockedNames) {
        FaceMergeChange.Confirm(grouped)
    } else {
        FaceMergeChange.Keep
    }
}

/** Whether a snapshot's "not [markName]" mark lands on the local face it overlaps, whose confirmed name
 *  once the merge is done is [localConfirmedName]: never against a face confirmed as that very name. */
internal fun notPersonMarkApplies(localConfirmedName: String?, markName: String): Boolean =
    localConfirmedName != markName

/** Whether a snapshot's cover choice for a name is taken: on Replace always, on Merge only where this
 *  device has not chosen a cover for that name. */
internal fun coverApplies(mode: FaceSnapshotMode, localHasCover: Boolean): Boolean =
    mode == FaceSnapshotMode.Replace || !localHasCover

/** What importing a snapshot's scan marker does to this device's marker for the same photo. */
internal sealed interface ScanMarkerChange {
    /** No marker here yet: add one carrying the snapshot's high-resolution flag. */
    data class Insert(val hiResScanned: Boolean) : ScanMarkerChange

    /** A marker here that the snapshot's high-resolution sweep outdoes. */
    data object RaiseHiRes : ScanMarkerChange

    /** A marker here already at least as far along. */
    data object Keep : ScanMarkerChange
}

/**
 * Every scan marker of a same-model snapshot is imported, a face-free photo's as much as any, so the
 * importing device never downloads and scans again a photo the snapshot already looked at. A marker this
 * device holds is only ever raised to the snapshot's high-resolution flag, never lowered.
 */
internal fun scanMarkerChange(localMarker: Boolean, snapshotHiRes: Boolean): ScanMarkerChange = when {
    !localMarker -> ScanMarkerChange.Insert(snapshotHiRes)
    snapshotHiRes -> ScanMarkerChange.RaiseHiRes
    else -> ScanMarkerChange.Keep
}

/**
 * Pairs [snapshot] faces with [local] faces on the same photos through [matchReattachLabels], the rule
 * that rebinds names after a model change (the same id first where that is safe, then the strongest box
 * overlap, each face paired once), with every pair then held to a box overlap of at least [minIou], so an
 * id alone never pairs two faces that sit apart. Returns snapshot face id to local face id.
 */
internal fun matchSnapshotFaces(
    snapshot: List<ReattachFace>,
    local: List<ReattachFace>,
    minIou: Float = REATTACH_MIN_IOU,
): Map<String, String> {
    if (snapshot.isEmpty() || local.isEmpty()) return emptyMap()
    // Each snapshot face travels as a label named by its own id, so the local face to name answer reads
    // back as the pairing itself.
    val labels = snapshot.map { ReattachLabel(it.faceId, it.photoKey, it.left, it.top, it.right, it.bottom, it.faceId) }
    val snapshotById = snapshot.associateBy { it.faceId }
    val localById = local.associateBy { it.faceId }
    val pairs = HashMap<String, String>()
    for ((localId, snapshotId) in matchReattachLabels(labels, local, minIou)) {
        val s = snapshotById[snapshotId] ?: continue
        val l = localById[localId] ?: continue
        val overlap = boxIou(s.left, s.top, s.right, s.bottom, l.left, l.top, l.right, l.bottom)
        if (overlap >= minIou) pairs[snapshotId] = localId
    }
    return pairs
}

/**
 * The face index's landmark string ("x,y;x,y;...", each number as Float.toString writes it) as
 * interleaved coordinates. Empty for an empty string, for any malformed pair and for more points than a
 * snapshot carries, so a damaged value never shifts a point into another point's role.
 */
internal fun landmarkPoints(encoded: String): FloatArray {
    if (encoded.isEmpty()) return FloatArray(0)
    val pairs = encoded.split(';')
    if (pairs.size > MlSnapshotFormat.MAX_LANDMARK_POINTS) return FloatArray(0)
    val points = FloatArray(pairs.size * 2)
    for ((i, pair) in pairs.withIndex()) {
        val comma = pair.indexOf(',')
        if (comma < 0 || pair.indexOf(',', comma + 1) >= 0) return FloatArray(0)
        points[2 * i] = pair.substring(0, comma).toFloatOrNull() ?: return FloatArray(0)
        points[2 * i + 1] = pair.substring(comma + 1).toFloatOrNull() ?: return FloatArray(0)
    }
    return points
}

/** [landmarkPoints] reversed, in the exact form the indexer writes, so a string it wrote comes back
 *  character for character. */
internal fun landmarkString(points: FloatArray): String {
    val builder = StringBuilder(points.size * 10)
    for (i in 0 until points.size / 2) {
        if (i > 0) builder.append(';')
        builder.append(points[2 * i]).append(',').append(points[2 * i + 1])
    }
    return builder.toString()
}

/** The detection index of a face id in the index's `photoKey#index` form, or null for any other id. The
 *  index has to be written the way the indexer writes it, so the id rebuilt from it is the same id. */
internal fun faceIndexIn(faceId: String, photoKey: String): Int? {
    if (faceId.length <= photoKey.length + 1 || !faceId.startsWith(photoKey) || faceId[photoKey.length] != '#') {
        return null
    }
    val digits = faceId.substring(photoKey.length + 1)
    val index = digits.toIntOrNull() ?: return null
    return if (index >= 0 && index.toString() == digits) index else null
}

/** A face id taken apart into its photo key and detection index, or null for an id not of that form. */
internal fun faceIdParts(faceId: String): Pair<String, Int>? {
    val hash = faceId.lastIndexOf('#')
    if (hash <= 0) return null
    val photoKey = faceId.substring(0, hash)
    return faceIndexIn(faceId, photoKey)?.let { photoKey to it }
}

/** Whether [value] fits a snapshot string field. */
internal fun fitsSnapshotString(value: String): Boolean =
    value.length <= MlSnapshotFormat.MAX_STRING_BYTES / 3 ||
        value.encodeToByteArray().size <= MlSnapshotFormat.MAX_STRING_BYTES

/** A photo key a snapshot carries: a cloud link id ([isPortableFaceKey]), within the string limit. A device
 *  key, a MediaStore uri or a path in the hidden area, never leaves this device. */
internal fun carriesPhotoKey(photoKey: String): Boolean =
    photoKey.isNotEmpty() && isPortableFaceKey(photoKey) && fitsSnapshotString(photoKey)

/** A person name a snapshot carries: not blank and within the string limit, else null. */
internal fun snapshotName(name: String?): String? = name?.takeIf { it.isNotBlank() && fitsSnapshotString(it) }
