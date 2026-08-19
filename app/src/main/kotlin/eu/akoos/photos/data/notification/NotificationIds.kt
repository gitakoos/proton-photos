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

package eu.akoos.photos.data.notification

/**
 * The single allocation ledger for every system notification id the app posts under.
 *
 * A notification id is process-wide: two posters sharing one id overwrite each other, and a cancel
 * from either tears down whatever the other put in the shade. That includes foreground-service
 * notifications, where the collision also decides whether the service keeps running. Declaring the
 * ids next to the code that posts them cannot catch a clash, so every fixed id lives here and each
 * call site reads it from here.
 *
 * Adding an id means adding a public `const val` below. `NotificationIdsTest` reads the constants
 * reflectively and fails on a duplicate or on anything that lands inside [ALBUM_DOWNLOAD_RANGE].
 */
object NotificationIds {

    /** Upload progress on the backup worker's foreground notification. */
    const val SYNC_WORKER = 4243

    /** The keep-alive backup service's permanent foreground notification. */
    const val BACKGROUND_SYNC_SERVICE = 4244

    /**
     * The delete-after-backup consent prompt. Stable across upgrades: it is the only handle on a
     * post an older build left in the shade, and nothing else can dismiss it.
     */
    const val DELETE_CONSENT = 4245

    /** The "a newer version exists" one-shot from the background release check. */
    const val UPDATE_AVAILABLE = 4246

    /** The screenshot overlay service's foreground notification. */
    const val SCREENSHOT_OVERLAY = 4247

    /** The cloud metadata-save worker's foreground notification. */
    const val METADATA_EDIT = 4248

    /**
     * Reserved for album downloads, which take one id per run so two concurrent downloads keep
     * separate entries in the shade instead of overwriting each other. No fixed id may fall in here.
     */
    val ALBUM_DOWNLOAD_RANGE: IntRange =
        ALBUM_DOWNLOAD_FIRST until (ALBUM_DOWNLOAD_FIRST + ALBUM_DOWNLOAD_SPAN)

    /**
     * The id an album-download run posts under, derived from [seed] (the worker's UUID hash) and
     * always inside [ALBUM_DOWNLOAD_RANGE]. `mod` rather than `abs`, because `abs(Int.MIN_VALUE)`
     * stays negative and would land the run outside the reserved block.
     */
    fun albumDownload(seed: Int): Int = ALBUM_DOWNLOAD_FIRST + seed.mod(ALBUM_DOWNLOAD_SPAN)

    private const val ALBUM_DOWNLOAD_FIRST = 5_000
    private const val ALBUM_DOWNLOAD_SPAN = 50_000
}
