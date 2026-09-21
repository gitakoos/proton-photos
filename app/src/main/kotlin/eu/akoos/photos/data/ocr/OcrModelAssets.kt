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
 * The asset descriptors and the size-plus-digest admission rule below are derived from mobile_ocr,
 * which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

/**
 * One model file the text reader needs, pinned to the exact bytes this build was tested against.
 * Both numbers are checked: a truncated download has the right prefix and the wrong length, and a
 * substituted file has the right length and the wrong digest.
 */
data class OcrModelAsset(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * A stage of the reader that can be put on the device on its own.
 *
 * The split is deliberate. Finding where the words are costs 4.5 MB; turning those regions into
 * characters costs another 8.7 MB, and a caller that only wants outlines should never be made to
 * pay for the second. Each component is resolved, verified and fetched as a unit.
 */
enum class OcrModelComponent {

    /** The DB network that says where the words are. */
    Detection,

    /** The recognition network, the angle classifier and the alphabet they decode against. */
    Recognition,
}

/** Everything the text reader downloads, and where from. */
object OcrModelAssets {

    /** The DB text-detection network. It finds where the words are, not what they say. */
    val DETECTION = OcrModelAsset(
        fileName = "det.onnx",
        sizeBytes = 4_748_769L,
        sha256 = "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924",
    )

    /** The SVTR network that turns one cropped line into characters, trained on the Latin script (45
     *  languages including Hungarian) rather than the full multilingual set, so its alphabet is small
     *  and its accuracy on Latin text high. Reads the full Latin accent set, the older multilingual
     *  network could not represent every Hungarian letter. */
    val RECOGNITION = OcrModelAsset(
        fileName = "rec_latin.onnx",
        sizeBytes = 8_064_539L,
        sha256 = "995b0f5f28d2073896a78c03b5b863eae6af3744bafa0245b8522beea6994927",
    )

    /** The classifier that says whether a cropped line is upside down. */
    val CLASSIFICATION = OcrModelAsset(
        fileName = "cls.onnx",
        sizeBytes = 582_663L,
        sha256 = "f4bb53707100c5f3d59ba834eb05bb400369f20aed35d4b26807b1bfadd2a70e",
    )

    /**
     * The alphabet [RECOGNITION] emits, one character per line. The network answers in indices into
     * this list, so a mismatched file does not fail loudly: it reads every photo as the wrong
     * characters. It is pinned and verified exactly like the networks are.
     */
    val DICTIONARY = OcrModelAsset(
        fileName = "ppocrv5_latin_dict.txt",
        sizeBytes = 2_616L,
        sha256 = "ccbcc45730b3fbbd9050c5bc74db6a99067141ef1035e3d14889a84a6b9b1aff",
    )

    /** What [component] is made of, in the order the files are fetched. */
    fun assetsOf(component: OcrModelComponent): List<OcrModelAsset> = when (component) {
        OcrModelComponent.Detection -> listOf(DETECTION)
        OcrModelComponent.Recognition -> listOf(CLASSIFICATION, DICTIONARY, RECOGNITION)
    }

    /** How many bytes [component] costs on a device that has none of it. */
    fun downloadBytesOf(component: OcrModelComponent): Long = assetsOf(component).sumOf { it.sizeBytes }

    /** Every file the reader can ask for, with no duplicates. */
    val REQUIRED: List<OcrModelAsset> = OcrModelComponent.entries.flatMap { assetsOf(it) }

    /** What the consent prompt quotes, so the figure on screen is the figure that goes over the wire. */
    val TOTAL_DOWNLOAD_BYTES: Long = REQUIRED.sumOf { it.sizeBytes }

    /**
     * Release the model assets are published under. Its own tag rather than an app release: the
     * models change on their own schedule and every app version that expects these exact bytes
     * points at the same immutable assets.
     */
    const val RELEASE_TAG = "v1"

    /** Where [REQUIRED] is fetched from; a file name appends directly to it. */
    const val BASE_URL = "https://github.com/gitakoos/ocr-models/releases/download/$RELEASE_TAG/"

    /** Sub-directory holding the models, under app-private storage and under the side-load root. */
    const val DIRECTORY = "ocr"

    fun downloadUrl(asset: OcrModelAsset): String = BASE_URL + asset.fileName
}

/** What a candidate model file on disk turned out to be. */
enum class OcrModelCheck {
    /** The bytes are exactly the ones this build expects. */
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
 * [actualSha256] is a lambda because hashing several megabytes is worth skipping whenever the cheap
 * length check has already settled the question, and because the digest is only ever consulted for
 * a file that is otherwise admissible.
 */
fun checkOcrModel(
    asset: OcrModelAsset,
    present: Boolean,
    actualSize: Long,
    actualSha256: () -> String?,
): OcrModelCheck = when {
    !present -> OcrModelCheck.Missing
    actualSize != asset.sizeBytes -> OcrModelCheck.WrongSize
    !asset.sha256.equals(actualSha256(), ignoreCase = true) -> OcrModelCheck.WrongHash
    else -> OcrModelCheck.Ok
}
