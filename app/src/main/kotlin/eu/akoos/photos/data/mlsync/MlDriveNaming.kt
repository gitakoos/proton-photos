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

/** The folders inside the app's Drive folder, with how each names a file's writing install. */
enum class DriveDataFolder(val folderName: String, private val parseDeviceId: (String) -> String?) {
    /** The face and search snapshots. */
    ML(MlDriveNaming.ML_FOLDER_NAME, { MlDriveNaming.parse(it)?.deviceId }),

    /** The labels. */
    LABELS(MlDriveNaming.LABELS_FOLDER_NAME, { LabelDriveNaming.parse(it) }),
    ;

    /** The install id [fileName] carries, or null for a name this folder does not write. */
    fun deviceIdOf(fileName: String): String? = parseDeviceId(fileName)
}

/** The two snapshot files every install keeps in the Drive "ML data" folder. */
enum class MlDriveFileKind(val prefix: String) {
    /** The face-recognition snapshot. */
    FACES("faces"),

    /** The semantic-search snapshot. */
    SEARCH("search"),
}

/** A snapshot file name taken apart: which snapshot it holds and which install wrote it. */
data class MlDriveFileName(val kind: MlDriveFileKind, val deviceId: String)

/**
 * One file in the Drive "ML data" folder, as [MlDriveStore.list] reports it. The uids are the Drive
 * SDK's opaque strings for My files nodes and are never written to sync_state or photo_listing.
 */
data class MlDriveFile(
    val nodeUid: String,
    val name: String,
    /** Plaintext size the uploading install declared, or the stored size when it declared none. */
    val sizeBytes: Long,
    /** The revision a download reads and the next upload of this file replaces. */
    val activeRevisionUid: String,
    /** When the server stored the active revision, epoch millis. */
    val serverModifiedAtMs: Long,
    /** When the server created the file, epoch millis. */
    val serverCreatedAtMs: Long,
    /** Modification time the uploading install claimed for the active revision, epoch millis. */
    val claimedModifiedAtMs: Long?,
)

/** A file picked for import, with the snapshot kind and the writing install read from its name. */
data class MlDriveImport(
    val kind: MlDriveFileKind,
    val deviceId: String,
    val file: MlDriveFile,
)

/**
 * The fixed names behind the Drive copy of the machine-learning data. Every install writes exactly
 * `faces-<deviceId>.pfpml` and `search-<deviceId>.pfpml`, where the device id is the install's random
 * UUID, and replaces them as new revisions; a name never changes once written. Pure, so it is
 * unit-tested directly.
 */
object MlDriveNaming {
    /** The app's folder at the top of My files. */
    const val APP_FOLDER_NAME = "Photos for Proton"

    /** The folder inside [APP_FOLDER_NAME] that holds every install's snapshots. */
    const val ML_FOLDER_NAME = "ML data"

    /** The folder inside [APP_FOLDER_NAME] that holds every install's labels. */
    const val LABELS_FOLDER_NAME = "Labels"

    /** Extension of a snapshot file. */
    const val FILE_EXTENSION = ".pfpml"

    /** Media type a snapshot file is uploaded with. */
    const val MEDIA_TYPE = "application/octet-stream"

    private const val DEVICE_LABEL_MAX_LENGTH = 40
    private const val FALLBACK_DEVICE_LABEL = "Android"

    /** Lower-case hex, the form [java.util.UUID.toString] produces. */
    private val DEVICE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val WHITESPACE = Regex("\\s+")

    /** Oldest first: the claimed time, then the server time, then the uid so the order is total. */
    private val OLDEST_FIRST: Comparator<MlDriveImport> = compareBy<MlDriveImport>(
        { it.file.claimedModifiedAtMs },
        { it.file.serverModifiedAtMs },
        { it.file.nodeUid },
    )

    /** True for a well-formed device id. */
    fun isDeviceId(value: String): Boolean = DEVICE_ID.matches(value)

    /** The file name [kind]'s snapshot of install [deviceId] is stored under. */
    fun fileName(kind: MlDriveFileKind, deviceId: String): String {
        require(isDeviceId(deviceId)) { "Not a device id" }
        return "${kind.prefix}-$deviceId$FILE_EXTENSION"
    }

    /** Takes a name written by [fileName] apart, or null for any other name. */
    fun parse(name: String): MlDriveFileName? {
        val stem = name.removeSuffix(FILE_EXTENSION)
        if (stem.length == name.length) return null
        for (kind in MlDriveFileKind.entries) {
            val deviceId = stem.removePrefix("${kind.prefix}-")
            if (deviceId.length != stem.length && isDeviceId(deviceId)) {
                return MlDriveFileName(kind, deviceId)
            }
        }
        return null
    }

    /**
     * The snapshots to restore from a folder listing: for every install and kind, its newest file.
     * Newest means the latest time the writing install claimed; equal claims fall back to the time the
     * server stored the revision. Files whose names [parse] rejects are ignored, and the files of
     * [ownDeviceId] only count when [includeOwnDevice] is set. Returned newest first.
     */
    fun selectForImport(
        files: List<MlDriveFile>,
        ownDeviceId: String,
        includeOwnDevice: Boolean = false,
    ): List<MlDriveImport> =
        files
            .mapNotNull { file -> parse(file.name)?.let { MlDriveImport(it.kind, it.deviceId, file) } }
            .filter { includeOwnDevice || it.deviceId != ownDeviceId }
            .groupBy { it.kind to it.deviceId }
            .map { (_, candidates) -> candidates.maxWith(OLDEST_FIRST) }
            .sortedWith(OLDEST_FIRST.reversed())

    /** A short label for the restore list: the model name with its whitespace tidied, never blank. */
    fun deviceLabel(model: String?): String =
        model.orEmpty()
            .trim()
            .replace(WHITESPACE, " ")
            .take(DEVICE_LABEL_MAX_LENGTH)
            .trimEnd()
            .ifEmpty { FALLBACK_DEVICE_LABEL }
}
