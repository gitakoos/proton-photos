package me.proton.photos.util

import android.os.Environment

/**
 * Single source of truth for the on-device folder structure Proton Photos owns.
 *
 *   Pictures/Proton Photos/                 — default destination for image downloads + copies
 *   Pictures/Proton Photos/Recovered/       — files restored from the Hidden vault
 *   Pictures/Proton Photos/<AlbumName>/     — user-created manual local albums (images)
 *   Movies/Proton Photos/                   — default destination for video downloads + copies
 *   Movies/Proton Photos/<AlbumName>/       — manual local albums (videos)
 *
 * Every write into MediaStore should go through one of these helpers so future relocations
 * (e.g. renaming the root) need a single touch point.
 */
object ProtonPhotosStorage {
    /** The visible folder name used everywhere. Keep human-readable — appears in MediaStore. */
    const val ROOT_NAME = "Proton Photos"

    /** Default download/copy folder for images. e.g. "Pictures/Proton Photos". */
    val DEFAULT_PICTURES: String
        get() = "${Environment.DIRECTORY_PICTURES}/$ROOT_NAME"

    /** Default download/copy folder for videos. e.g. "Movies/Proton Photos". */
    val DEFAULT_MOVIES: String
        get() = "${Environment.DIRECTORY_MOVIES}/$ROOT_NAME"

    /** Recovered (un-hidden) files land here so the user can find them easily. */
    val RECOVERED_PICTURES: String
        get() = "$DEFAULT_PICTURES/Recovered"
    val RECOVERED_MOVIES: String
        get() = "$DEFAULT_MOVIES/Recovered"

    /**
     * Returns the MediaStore `RELATIVE_PATH` for an image inside a user-created local album.
     * e.g. `albumPicturesPath("Trip 2026")` → `"Pictures/Proton Photos/Trip 2026"`.
     */
    fun albumPicturesPath(albumName: String): String = "$DEFAULT_PICTURES/${sanitize(albumName)}"
    fun albumMoviesPath(albumName: String): String = "$DEFAULT_MOVIES/${sanitize(albumName)}"

    /** Strips path separators and trims so an album name can be used as a folder segment. */
    fun sanitize(name: String): String = name
        .trim()
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace(Regex("\\s+"), " ")
}
