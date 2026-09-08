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

package eu.akoos.photos.presentation.whatsnew

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DensitySmall
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.graphics.vector.ImageVector
import eu.akoos.photos.R

/** One feature highlight fed into the pager: its icon chip, strings, and which section it sits under. */
internal class WhatsNewFeature(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val category: WhatsNewCategory = WhatsNewCategory.New,
)

/**
 * Which section a feature sits under, mirroring the release notes' split so a reader sees at a glance
 * what is brand new versus what got better. A release whose cards are all one category shows no
 * section labels, so older entries render exactly as before.
 */
internal enum class WhatsNewCategory(val titleRes: Int) {
    New(R.string.whats_new_section_new),
    Improved(R.string.whats_new_section_improved),
}

/**
 * A release's headline item, drawn as a taller card on a page of its own because it needs more than
 * a title and one line. Kept as an enum rather than a composable reference so the catalog stays
 * plain data.
 */
internal enum class WhatsNewHero(val titleRes: Int) {
    Hide(R.string.whats_new_hide_title),
    AlbumOrder(R.string.whats_new_albums_title),
}

/**
 * Everything one release announced: an optional headline card, then the regular cards in order,
 * then an optional closing line for the smaller changes that did not earn a card.
 *
 * [moreRes] belongs to the release rather than the screen. It is drawn on the last page, so a
 * single shared line would have followed whichever release the reader opened and told someone
 * browsing 2.3.9 about changes that shipped later.
 */
internal data class WhatsNewRelease(
    val version: String,
    val hero: WhatsNewHero?,
    val features: List<WhatsNewFeature>,
    val moreRes: Int? = null,
)

/**
 * What each release announced, newest first. The head of this list is what the post-update screen
 * shows, and the whole list is what Settings offers to browse.
 *
 * **A feature belongs to the release that INTRODUCED it, and appears exactly once.** The shipped
 * screens did not work that way: 2.4.0 re-showed two of 2.3.9's cards and the 2.4.1 work carried
 * five of 2.4.0's forward, because there was one flat list rather than a per-release one. The sets
 * below were recovered from the tags (`git show v2.3.9:...WhatsNewScreen.kt`) and then split so
 * each card sits under the version it actually arrived in. Nothing earlier than 2.3.9 is listed
 * because that is the release the screen itself was added in.
 *
 * When adding a release, put it at the head with only its own new cards. `WhatsNewCatalogTest`
 * fails if a card is repeated from an older entry.
 */
internal val WhatsNewReleases: List<WhatsNewRelease> = listOf(
    // NOTE for the 2.5.0 STABLE release: prepend WhatsNew250EarlierPreviews (below) to this card's
    // features so a user updating from 2.4.0 meets the whole of 2.5.0 at once. Each PREVIEW keeps only
    // its own new cards, so this card lists the newest preview's cards alone. Smaller changes are their
    // own cards too rather than a closing line, so they are swiped through and actually read.
    WhatsNewRelease(
        version = "2.5.0",
        hero = null,
        features = listOf(
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_import_title, R.string.whats_new_import_body, WhatsNewCategory.New),
            WhatsNewFeature(Icons.Default.Email, R.string.whats_new_share_email_title, R.string.whats_new_share_email_body, WhatsNewCategory.New),
            WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_video_faces_title, R.string.whats_new_video_faces_body, WhatsNewCategory.New),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_faces_rework_title, R.string.whats_new_faces_rework_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.NoAccounts, R.string.whats_new_guest_title, R.string.whats_new_guest_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_dup_nobackup_title, R.string.whats_new_dup_nobackup_body, WhatsNewCategory.Improved),
        ),
    ),
    WhatsNewRelease(
        version = "2.4.1",
        hero = WhatsNewHero.AlbumOrder,
        features = listOf(
            WhatsNewFeature(Icons.Default.Crop, R.string.whats_new_crop_title, R.string.whats_new_crop_body),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_metadata_title, R.string.whats_new_metadata_body),
            WhatsNewFeature(Icons.Default.TextFields, R.string.whats_new_text_title, R.string.whats_new_text_body),
            WhatsNewFeature(Icons.Default.HdrOn, R.string.whats_new_hdr_title, R.string.whats_new_hdr_body),
            WhatsNewFeature(Icons.Default.GroupAdd, R.string.whats_new_shared_add_title, R.string.whats_new_shared_add_body),
            WhatsNewFeature(Icons.Default.Checklist, R.string.whats_new_selection_title, R.string.whats_new_selection_body),
            WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_freeup_review_title, R.string.whats_new_freeup_review_body),
            WhatsNewFeature(Icons.Default.Campaign, R.string.whats_new_news_title, R.string.whats_new_news_body),
            WhatsNewFeature(Icons.Default.Bookmark, R.string.whats_new_viewer_place_title, R.string.whats_new_viewer_place_body),
            WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_album_guard_title, R.string.whats_new_album_guard_body),
        ),
        moreRes = R.string.whats_new_more_241,
    ),
    WhatsNewRelease(
        version = "2.4.0",
        hero = WhatsNewHero.Hide,
        features = listOf(
            WhatsNewFeature(Icons.Default.CleaningServices, R.string.whats_new_freeup_title, R.string.whats_new_freeup_body),
            WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_compress_title, R.string.whats_new_compress_body),
            WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_seamless_title, R.string.whats_new_seamless_body),
            WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_scrubber_title, R.string.whats_new_scrubber_body),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_album_title, R.string.whats_new_album_body),
        ),
        moreRes = R.string.whats_new_more_240,
    ),
    WhatsNewRelease(
        version = "2.3.9",
        hero = null,
        features = listOf(
            WhatsNewFeature(Icons.Default.ContentCopy, R.string.whats_new_dup_title, R.string.whats_new_dup_body),
            WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_offline_title, R.string.whats_new_offline_body),
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_activity_title, R.string.whats_new_activity_body),
            WhatsNewFeature(Icons.Default.PlayCircle, R.string.whats_new_motion_title, R.string.whats_new_motion_body),
        ),
    ),
)

/** The release the post-update screen announces, and the one Settings opens by default. */
internal val LatestWhatsNewRelease: WhatsNewRelease get() = WhatsNewReleases.first()

/**
 * The cards that shipped across the earlier 2.5.0 preview builds (test1 / test2 / test3). They are held
 * out of the 2.5.0 card above so each PREVIEW announces only what THAT preview added; prepend them to the
 * 2.5.0 card's features for the STABLE release, where someone updating from 2.4.0 meets all of 2.5.0
 * at once. Referencing the strings here also keeps them from reading as unused until then.
 */
@Suppress("unused")
internal val WhatsNew250EarlierPreviews: List<WhatsNewFeature> = listOf(
    WhatsNewFeature(Icons.Default.Face, R.string.whats_new_faces_title, R.string.whats_new_faces_body, WhatsNewCategory.New),
    WhatsNewFeature(Icons.Default.PhoneAndroid, R.string.whats_new_localonly_title, R.string.whats_new_localonly_body, WhatsNewCategory.New),
    WhatsNewFeature(Icons.Default.Public, R.string.whats_new_map_title, R.string.whats_new_map_body, WhatsNewCategory.Improved),
    WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_dupfinder_title, R.string.whats_new_dupfinder_body, WhatsNewCategory.Improved),
    WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_albumcover_title, R.string.whats_new_albumcover_body, WhatsNewCategory.Improved),
    WhatsNewFeature(Icons.Default.DensitySmall, R.string.whats_new_denser_title, R.string.whats_new_denser_body, WhatsNewCategory.Improved),
    WhatsNewFeature(Icons.Default.Brush, R.string.whats_new_editor_title, R.string.whats_new_editor_body),
    WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_collage_title, R.string.whats_new_collage_body),
    WhatsNewFeature(Icons.Default.SaveAlt, R.string.whats_new_export_title, R.string.whats_new_export_body),
    WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_video_title, R.string.whats_new_video_body),
    WhatsNewFeature(Icons.Default.AutoAwesome, R.string.whats_new_smooth_title, R.string.whats_new_smooth_body),
    WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_dupreview_title, R.string.whats_new_dupreview_body),
    WhatsNewFeature(Icons.Default.Schedule, R.string.whats_new_filedate_title, R.string.whats_new_filedate_body),
)

/** Looks up a release by its version string, falling back to the newest for an unknown one. */
internal fun whatsNewReleaseFor(version: String?): WhatsNewRelease =
    WhatsNewReleases.firstOrNull { it.version == version } ?: LatestWhatsNewRelease

/** The release's own headline, used as the one-line summary in the version list. */
internal val WhatsNewRelease.headlineRes: Int get() = hero?.titleRes ?: features.first().titleRes
