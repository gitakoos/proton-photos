package eu.akoos.photos.util

import android.os.Environment

/**
 * Single source of truth for the on-device folder structure Proton Photos owns.
 *
 *   Pictures/                  — default destination for image downloads + copies
 *   Pictures/Recovered/        — files restored from the Hidden vault
 *   Pictures/<AlbumName>/      — user-created manual local albums (images)
 *   Movies/                    — default destination for video downloads + copies
 *   Movies/<AlbumName>/        — manual local albums (videos)
 *
 * Previously each path was nested under `Proton Photos/`. That created a redundant
 * gallery folder (every device gallery surfaces "Proton Photos" as a separate album,
 * duplicating "Pictures"), so the layout was flattened to match DownloadPhotosUseCase.
 * Files written before this change keep living at `Pictures/Proton Photos/…` —
 * MediaStore observers still see them.
 *
 * Every write into MediaStore should go through one of these helpers so future relocations
 * (e.g. renaming the root) need a single touch point.
 */
object ProtonPhotosStorage {
    /** Kept for migration / legacy detection of pre-flat-layout files. New writes don't
     *  use it as a path segment any more — see DEFAULT_PICTURES below. */
    const val ROOT_NAME = "Proton Photos"

    /** Default download/copy folder for images. Flat: just "Pictures". */
    val DEFAULT_PICTURES: String
        get() = Environment.DIRECTORY_PICTURES

    /** Default download/copy folder for videos. Flat: just "Movies". */
    val DEFAULT_MOVIES: String
        get() = Environment.DIRECTORY_MOVIES

    /** Recovered (un-hidden) files land here so the user can find them easily. */
    val RECOVERED_PICTURES: String
        get() = "$DEFAULT_PICTURES/Recovered"
    val RECOVERED_MOVIES: String
        get() = "$DEFAULT_MOVIES/Recovered"

    /**
     * Returns the MediaStore `RELATIVE_PATH` for an image inside a user-created local album.
     * e.g. `albumPicturesPath("Trip 2026")` → `"Pictures/Trip 2026"` (flat layout, no
     * Proton-Photos prefix). Matches how DownloadPhotosUseCase routes album downloads.
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
