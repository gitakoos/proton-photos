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

/*
 * The asset descriptor and the size-plus-digest admission rule below are derived from mobile_ocr,
 * which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.face

/**
 * The one model file the face detector needs, pinned to the exact bytes this build was tested
 * against. Both numbers are checked: a truncated download has the right prefix and the wrong length,
 * and a substituted file has the right length and the wrong digest.
 */
data class FaceModelAsset(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /**
     * Whether this build knows the exact bytes it expects: a non-blank digest and a positive length.
     * While it is false the bytes cannot be verified, so the manager refuses to fetch over the
     * network and the only source is a developer's side-loaded copy.
     */
    val isPinned: Boolean get() = sha256.isNotBlank() && sizeBytes > 0L
}

/** Everything the face detector loads, and where from. */
object FaceModelAssets {

    /**
     * The SCRFD-500m detector: one ONNX network that finds where the faces are, not who they are. It
     * is not published yet, so it is left unpinned (a blank digest and a zero length). Filling both in
     * from the released asset flips the rail from side-load-only to a verified network fetch; nothing
     * else here has to change.
     */
    val MODEL = FaceModelAsset(
        fileName = "scrfd_500m.onnx",
        sizeBytes = 0L,
        sha256 = "",
    )

    /**
     * The buffalo_s recognition network: one ONNX model that turns an aligned 112x112 face crop into a
     * 512-d embedding, the vector later pieces compare to decide who is who. It rides the same release
     * and the same side-load directory as [MODEL] and is likewise left unpinned (a blank digest and a
     * zero length) until it is published; filling both in flips it from side-load-only to a verified
     * network fetch with nothing else here to change.
     */
    val EMBED_MODEL = FaceModelAsset(
        fileName = "w600k_mbf.onnx",
        sizeBytes = 0L,
        sha256 = "",
    )

    /** Whether [MODEL] is pinned to exact bytes. Until it is, the rail is side-load only. */
    val isPinned: Boolean get() = MODEL.isPinned

    /** What a consent prompt would quote, so the figure on screen is the figure that goes over the wire. */
    val TOTAL_DOWNLOAD_BYTES: Long get() = MODEL.sizeBytes

    /**
     * Release the model asset is published under. Its own tag rather than an app release: the model
     * changes on its own schedule and every app version that expects these exact bytes points at the
     * same immutable asset.
     */
    const val RELEASE_TAG = "face-models-v1"

    /** Where [MODEL] is fetched from; a file name appends directly to it. */
    const val BASE_URL = "https://github.com/gitakoos/proton-photos/releases/download/$RELEASE_TAG/"

    /** Sub-directory holding the model, under app-private storage and under the side-load root. */
    const val DIRECTORY = "face"

    fun downloadUrl(asset: FaceModelAsset): String = BASE_URL + asset.fileName
}

/** What a candidate model file on disk turned out to be. */
enum class FaceModelCheck {
    /** The bytes are exactly the ones this build expects, or (while unpinned) a present dev copy. */
    Ok,

    /** Nothing is there. */
    Missing,

    /** Present but the wrong length, which is what a truncated or interrupted write looks like. */
    WrongSize,

    /** The right length and the wrong content. */
    WrongHash,
}

/**
 * Whether [asset] may be loaded from a file whose length is [actualSize].
 *
 * While [asset] is not pinned the exact bytes are unknown, so nothing can be proved beyond the file
 * being present and non-empty: that is the deliberate side-load-only state a developer drops a copy
 * into. Once the asset is pinned the full rule applies, the exact byte count and then the exact
 * SHA-256, so a truncated fetch or a swapped file is rejected.
 *
 * [actualSha256] is a lambda because hashing several megabytes is worth skipping whenever the cheap
 * length check has already settled the question, and because the digest is only ever consulted for a
 * file that is otherwise admissible.
 */
fun checkFaceModel(
    asset: FaceModelAsset,
    present: Boolean,
    actualSize: Long,
    actualSha256: () -> String?,
): FaceModelCheck = when {
    !present -> FaceModelCheck.Missing
    !asset.isPinned -> if (actualSize > 0L) FaceModelCheck.Ok else FaceModelCheck.WrongSize
    actualSize != asset.sizeBytes -> FaceModelCheck.WrongSize
    !asset.sha256.equals(actualSha256(), ignoreCase = true) -> FaceModelCheck.WrongHash
    else -> FaceModelCheck.Ok
}
