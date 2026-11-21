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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Lock
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
 * What the first 2.6.0 preview announced. The head entry of the list below lists only the cards added since, so a
 * preview reader does not meet the same cards twice; the 2.6.0 release itself is these cards followed
 * by the later previews' cards, without the "Includes" card.
 */
internal val WhatsNew260FirstPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.Bookmark, R.string.whats_new_260_labels_title, R.string.whats_new_260_labels_body),
        WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_260_compress_title, R.string.whats_new_260_compress_body),
        WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_260_vault_title, R.string.whats_new_260_vault_body),
        WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_albums_title, R.string.whats_new_260_albums_body),
        WhatsNewFeature(Icons.Default.Public, R.string.whats_new_260_shortcuts_title, R.string.whats_new_260_shortcuts_body),
        WhatsNewFeature(Icons.Default.Search, R.string.whats_new_260_search_title, R.string.whats_new_260_search_body),
        WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_picker_title, R.string.whats_new_260_picker_body),
        WhatsNewFeature(Icons.Default.Checklist, R.string.whats_new_260_selection_title, R.string.whats_new_260_selection_body),
        WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_settings_title, R.string.whats_new_260_settings_body),
        WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_260_grouping_title, R.string.whats_new_260_grouping_body),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_details_title, R.string.whats_new_260_details_body),
        WhatsNewFeature(Icons.Default.Crop, R.string.whats_new_260_frame_title, R.string.whats_new_260_frame_body),
        WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_drive_title, R.string.whats_new_260_drive_body),
    ),
    moreRes = R.string.whats_new_260_more,
)

/**
 * What the second 2.6.0 preview added to the first. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the first preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260SecondPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.SaveAlt, R.string.whats_new_260_autosave_title, R.string.whats_new_260_autosave_body),
        WhatsNewFeature(Icons.Default.Public, R.string.whats_new_260_worldmap_title, R.string.whats_new_260_worldmap_body),
        WhatsNewFeature(Icons.Default.Schedule, R.string.whats_new_260_time_title, R.string.whats_new_260_time_body),
        WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_addalbum_title, R.string.whats_new_260_addalbum_body),
        WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_260_duplicates_title, R.string.whats_new_260_duplicates_body),
        WhatsNewFeature(Icons.Default.Checklist, R.string.whats_new_260_dragselect_title, R.string.whats_new_260_dragselect_body),
        WhatsNewFeature(Icons.Default.BatteryChargingFull, R.string.whats_new_260_charging_title, R.string.whats_new_260_charging_body),
        WhatsNewFeature(Icons.Default.Today, R.string.whats_new_260_onthisday_title, R.string.whats_new_260_onthisday_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_scans_title, R.string.whats_new_260_scans_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Notifications, R.string.whats_new_260_notif_title, R.string.whats_new_260_notif_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_tiles_title, R.string.whats_new_260_tiles_body, WhatsNewCategory.Improved),
    ),
)

/**
 * What the third 2.6.0 preview added to the second. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the second preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260ThirdPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.Brush, R.string.whats_new_260_editor_title, R.string.whats_new_260_editor_body),
        WhatsNewFeature(Icons.Default.TextFields, R.string.whats_new_260_texttools_title, R.string.whats_new_260_texttools_body),
        WhatsNewFeature(Icons.Default.Crop, R.string.whats_new_260_frame2_title, R.string.whats_new_260_frame2_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_260_videoedit_title, R.string.whats_new_260_videoedit_body, WhatsNewCategory.Improved),
    ),
)

/**
 * What the fourth 2.6.0 preview added to the third. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the third preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260FourthPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_group_title, R.string.whats_new_260_group_body, WhatsNewCategory.Improved),
    ),
)

/**
 * What the fifth 2.6.0 preview added to the fourth. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the fourth preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260FifthPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.SaveAlt, R.string.whats_new_260_savedrive_title, R.string.whats_new_260_savedrive_body),
        WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_mirror_title, R.string.whats_new_260_mirror_body),
        WhatsNewFeature(Icons.Default.Visibility, R.string.whats_new_260_viewerlayout_title, R.string.whats_new_260_viewerlayout_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Schedule, R.string.whats_new_260_bigscan_title, R.string.whats_new_260_bigscan_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_groupfix_title, R.string.whats_new_260_groupfix_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_260_trashpick_title, R.string.whats_new_260_trashpick_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Place, R.string.whats_new_260_mappins_title, R.string.whats_new_260_mappins_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_personpage_title, R.string.whats_new_260_personpage_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Notifications, R.string.whats_new_260_backuppill_title, R.string.whats_new_260_backuppill_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Crop, R.string.whats_new_260_copyfix_title, R.string.whats_new_260_copyfix_body, WhatsNewCategory.Improved),
    ),
)

/**
 * What the sixth 2.6.0 preview added to the fifth. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the fifth preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260SixthPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_sdk_title, R.string.whats_new_260_sdk_body),
        WhatsNewFeature(Icons.Default.PhoneAndroid, R.string.whats_new_260_setas_title, R.string.whats_new_260_setas_body),
        WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_folderimport_title, R.string.whats_new_260_folderimport_body),
        WhatsNewFeature(Icons.Default.AutoAwesome, R.string.whats_new_260_mlsetup_title, R.string.whats_new_260_mlsetup_body),
        WhatsNewFeature(Icons.Default.TextFields, R.string.whats_new_260_longpress_title, R.string.whats_new_260_longpress_body),
        WhatsNewFeature(Icons.Default.Visibility, R.string.whats_new_260_nothumb_title, R.string.whats_new_260_nothumb_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.NoAccounts, R.string.whats_new_260_external_title, R.string.whats_new_260_external_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Place, R.string.whats_new_260_nullisland_title, R.string.whats_new_260_nullisland_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_likeness_title, R.string.whats_new_260_likeness_body, WhatsNewCategory.Improved),
    ),
)

/**
 * What the seventh 2.6.0 preview added to the sixth. The head entry of the list below lists only the cards added
 * since, so a preview reader does not meet the same cards twice; the 2.6.0 release follows the sixth preview's
 * cards with these, then the later previews' cards.
 */
internal val WhatsNew260SeventhPreview = WhatsNewRelease(
    version = "2.6.0",
    hero = null,
    features = listOf(
        WhatsNewFeature(Icons.Default.PlayCircle, R.string.whats_new_260_videoseek_title, R.string.whats_new_260_videoseek_body),
        WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_260_cloudcompress_title, R.string.whats_new_260_cloudcompress_body),
        WhatsNewFeature(Icons.Default.SaveAlt, R.string.whats_new_260_sharebackup_title, R.string.whats_new_260_sharebackup_body),
        WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_260_foldermove_title, R.string.whats_new_260_foldermove_body),
        WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_drivecopies_title, R.string.whats_new_260_drivecopies_body),
        WhatsNewFeature(Icons.Default.Computer, R.string.whats_new_260_desktopsync_title, R.string.whats_new_260_desktopsync_body),
        WhatsNewFeature(Icons.Default.Palette, R.string.whats_new_260_glass_title, R.string.whats_new_260_glass_body),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_facefix_title, R.string.whats_new_260_facefix_body),
        WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_260_detailsheet_title, R.string.whats_new_260_detailsheet_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Today, R.string.whats_new_260_multiedit_title, R.string.whats_new_260_multiedit_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_personmonths_title, R.string.whats_new_260_personmonths_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_timelinestay_title, R.string.whats_new_260_timelinestay_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Checklist, R.string.whats_new_260_setup_title, R.string.whats_new_260_setup_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_folderbackfill_title, R.string.whats_new_260_folderbackfill_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.CleaningServices, R.string.whats_new_260_freeupcheck_title, R.string.whats_new_260_freeupcheck_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.CloudOff, R.string.whats_new_260_removedstays_title, R.string.whats_new_260_removedstays_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_peoplepics_title, R.string.whats_new_260_peoplepics_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Speed, R.string.whats_new_260_coldstart_title, R.string.whats_new_260_coldstart_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.AddAPhoto, R.string.whats_new_260_newphotos_title, R.string.whats_new_260_newphotos_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_260_namessafe_title, R.string.whats_new_260_namessafe_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Badge, R.string.whats_new_260_namecase_title, R.string.whats_new_260_namecase_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Favorite, R.string.whats_new_260_favsync_title, R.string.whats_new_260_favsync_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Today, R.string.whats_new_260_copydate_title, R.string.whats_new_260_copydate_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Widgets, R.string.whats_new_260_widgetopen_title, R.string.whats_new_260_widgetopen_body, WhatsNewCategory.Improved),
        WhatsNewFeature(Icons.Default.Build, R.string.whats_new_260_smallfixes_title, R.string.whats_new_260_smallfixes_body, WhatsNewCategory.Improved),
    ),
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
    WhatsNewRelease(
        version = "2.6.0",
        hero = null,
        features = listOf(
            WhatsNewFeature(Icons.Default.Update, R.string.whats_new_260_intro_title, R.string.whats_new_260_intro_body),
            WhatsNewFeature(Icons.Default.Lock, R.string.whats_new_260_encrypt_title, R.string.whats_new_260_encrypt_body),
            WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_encryptsettings_title, R.string.whats_new_260_encryptsettings_body),
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_260_arrange_title, R.string.whats_new_260_arrange_body),
            WhatsNewFeature(Icons.Default.DragHandle, R.string.whats_new_260_folderorder_title, R.string.whats_new_260_folderorder_body),
            WhatsNewFeature(Icons.Default.Schedule, R.string.whats_new_260_newestsort_title, R.string.whats_new_260_newestsort_body),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_albumlistsync_title, R.string.whats_new_260_albumlistsync_body),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_albumsbox_title, R.string.whats_new_260_albumsbox_body),
            WhatsNewFeature(Icons.Default.Checklist, R.string.whats_new_260_albumpicker_title, R.string.whats_new_260_albumpicker_body),
            WhatsNewFeature(Icons.Default.AddAPhoto, R.string.whats_new_260_pendingalbums_title, R.string.whats_new_260_pendingalbums_body),
            WhatsNewFeature(Icons.Default.VisibilityOff, R.string.whats_new_260_hideperson_title, R.string.whats_new_260_hideperson_body),
            WhatsNewFeature(Icons.Default.AutoAwesome, R.string.whats_new_260_themes_title, R.string.whats_new_260_themes_body),
            WhatsNewFeature(Icons.Default.Search, R.string.whats_new_260_themesearch_title, R.string.whats_new_260_themesearch_body),
            WhatsNewFeature(Icons.Default.Apps, R.string.whats_new_260_appicon_title, R.string.whats_new_260_appicon_body),
            WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_260_disguise_title, R.string.whats_new_260_disguise_body),
            WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_settingssync_title, R.string.whats_new_260_settingssync_body),
            WhatsNewFeature(Icons.Default.Computer, R.string.whats_new_260_otherpeople_title, R.string.whats_new_260_otherpeople_body),
            WhatsNewFeature(Icons.Default.Search, R.string.whats_new_260_othersearch_title, R.string.whats_new_260_othersearch_body),
            WhatsNewFeature(Icons.Default.Bookmark, R.string.whats_new_260_labelsearch_title, R.string.whats_new_260_labelsearch_body),
            WhatsNewFeature(Icons.Default.Search, R.string.whats_new_260_quotedsearch_title, R.string.whats_new_260_quotedsearch_body),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_folderbox_title, R.string.whats_new_260_folderbox_body),
            WhatsNewFeature(Icons.Default.GroupAdd, R.string.whats_new_260_multiperson_title, R.string.whats_new_260_multiperson_body),
            WhatsNewFeature(Icons.Default.Cancel, R.string.whats_new_260_cancelone_title, R.string.whats_new_260_cancelone_body),
            WhatsNewFeature(Icons.Default.Build, R.string.whats_new_260_sdkswitches_title, R.string.whats_new_260_sdkswitches_body),
            WhatsNewFeature(Icons.Default.Lock, R.string.whats_new_260_fastcrypto_title, R.string.whats_new_260_fastcrypto_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Lock, R.string.whats_new_260_settingslocked_title, R.string.whats_new_260_settingslocked_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_notsaved_title, R.string.whats_new_260_notsaved_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_260_guesthide_title, R.string.whats_new_260_guesthide_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.VisibilityOff, R.string.whats_new_260_hiddenkept_title, R.string.whats_new_260_hiddenkept_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Lock, R.string.whats_new_260_hiddentip_title, R.string.whats_new_260_hiddentip_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.VisibilityOff, R.string.whats_new_260_hideonce_title, R.string.whats_new_260_hideonce_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_manualdetails_title, R.string.whats_new_260_manualdetails_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_backupswitch_title, R.string.whats_new_260_backupswitch_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_syncsooner_title, R.string.whats_new_260_syncsooner_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Refresh, R.string.whats_new_260_pullsync_title, R.string.whats_new_260_pullsync_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_revisionfix_title, R.string.whats_new_260_revisionfix_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Speed, R.string.whats_new_260_backupslist_title, R.string.whats_new_260_backupslist_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.ZoomIn, R.string.whats_new_260_mapstreet_title, R.string.whats_new_260_mapstreet_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Place, R.string.whats_new_260_mappinsview_title, R.string.whats_new_260_mappinsview_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Public, R.string.whats_new_260_mapfilegps_title, R.string.whats_new_260_mapfilegps_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_smoothgrid_title, R.string.whats_new_260_smoothgrid_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Speed, R.string.whats_new_260_screensfaster_title, R.string.whats_new_260_screensfaster_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_peoplefast_title, R.string.whats_new_260_peoplefast_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Speed, R.string.whats_new_260_collectionfast_title, R.string.whats_new_260_collectionfast_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Update, R.string.whats_new_260_lighterrefresh_title, R.string.whats_new_260_lighterrefresh_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.BatteryChargingFull, R.string.whats_new_260_scanbackground_title, R.string.whats_new_260_scanbackground_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Fullscreen, R.string.whats_new_260_immersive_title, R.string.whats_new_260_immersive_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Computer, R.string.whats_new_260_facematch_title, R.string.whats_new_260_facematch_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Badge, R.string.whats_new_260_handkept_title, R.string.whats_new_260_handkept_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.GroupAdd, R.string.whats_new_260_mergefix_title, R.string.whats_new_260_mergefix_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_scanloop_title, R.string.whats_new_260_scanloop_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_excludedfaces_title, R.string.whats_new_260_excludedfaces_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Today, R.string.whats_new_260_calendar_title, R.string.whats_new_260_calendar_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_syncnow_title, R.string.whats_new_260_syncnow_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_triggerkeep_title, R.string.whats_new_260_triggerkeep_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.SaveAlt, R.string.whats_new_260_savekeep_title, R.string.whats_new_260_savekeep_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.CleaningServices, R.string.whats_new_260_freeupbackup_title, R.string.whats_new_260_freeupbackup_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_260_editwhole_title, R.string.whats_new_260_editwhole_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_260_editformat_title, R.string.whats_new_260_editformat_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_sdkblocks_title, R.string.whats_new_260_sdkblocks_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_260_videolocation_title, R.string.whats_new_260_videolocation_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_samephoto_title, R.string.whats_new_260_samephoto_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhoneAndroid, R.string.whats_new_260_samename_title, R.string.whats_new_260_samename_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_260_compressnote_title, R.string.whats_new_260_compressnote_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhoneAndroid, R.string.whats_new_260_mirrorcheck_title, R.string.whats_new_260_mirrorcheck_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_260_dupmemory_title, R.string.whats_new_260_dupmemory_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_260_dupfreeze_title, R.string.whats_new_260_dupfreeze_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Notifications, R.string.whats_new_260_undobar_title, R.string.whats_new_260_undobar_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Notifications, R.string.whats_new_260_snackbars_title, R.string.whats_new_260_snackbars_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_260_editedcopy_title, R.string.whats_new_260_editedcopy_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_albumrefresh_title, R.string.whats_new_260_albumrefresh_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_albumrename_title, R.string.whats_new_260_albumrename_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_newalbumpicker_title, R.string.whats_new_260_newalbumpicker_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_260_trash999_title, R.string.whats_new_260_trash999_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Visibility, R.string.whats_new_260_hidealbumsync_title, R.string.whats_new_260_hidealbumsync_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_260_sdkdefault_title, R.string.whats_new_260_sdkdefault_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.CloudDownload, R.string.whats_new_260_searchdownload_title, R.string.whats_new_260_searchdownload_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Palette, R.string.whats_new_260_sharedpage_title, R.string.whats_new_260_sharedpage_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Refresh, R.string.whats_new_260_emptyshared_title, R.string.whats_new_260_emptyshared_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_260_zippassword_title, R.string.whats_new_260_zippassword_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Campaign, R.string.whats_new_260_newslinks_title, R.string.whats_new_260_newslinks_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Search, R.string.whats_new_260_searchtop_title, R.string.whats_new_260_searchtop_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.AddAPhoto, R.string.whats_new_260_newphotobackup_title, R.string.whats_new_260_newphotobackup_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Today, R.string.whats_new_260_stripdate_title, R.string.whats_new_260_stripdate_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Palette, R.string.whats_new_260_systemtheme_title, R.string.whats_new_260_systemtheme_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_260_videocount_title, R.string.whats_new_260_videocount_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_260_dupselectall_title, R.string.whats_new_260_dupselectall_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_260_importlive_title, R.string.whats_new_260_importlive_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_260_gridthumbs_title, R.string.whats_new_260_gridthumbs_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_260_editsave_title, R.string.whats_new_260_editsave_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Brush, R.string.whats_new_260_highlights_title, R.string.whats_new_260_highlights_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_260_viewerdelete_title, R.string.whats_new_260_viewerdelete_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Widgets, R.string.whats_new_260_widgetupright_title, R.string.whats_new_260_widgetupright_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_albumnames_title, R.string.whats_new_260_albumnames_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_260_firstscan_title, R.string.whats_new_260_firstscan_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Refresh, R.string.whats_new_260_offlinerefresh_title, R.string.whats_new_260_offlinerefresh_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.Sync, R.string.whats_new_260_emptyfile_title, R.string.whats_new_260_emptyfile_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_260_anyzip_title, R.string.whats_new_260_anyzip_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_260_importalbums_title, R.string.whats_new_260_importalbums_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_260_sentcopy_title, R.string.whats_new_260_sentcopy_body, WhatsNewCategory.Improved),
            WhatsNewFeature(Icons.Default.NoAccounts, R.string.whats_new_260_signoutfail_title, R.string.whats_new_260_signoutfail_body, WhatsNewCategory.Improved),
        ),
    ),
    // 2.5.0 STABLE: the whole release since 2.4.0, consolidated to its main features so someone
    // updating from 2.4.0 meets 2.5.0 as a tight set of cards rather than every preview's delta.
    // All cards are New here, so no section labels show; the closing line covers the smaller changes.
    WhatsNewRelease(
        version = "2.5.0",
        hero = null,
        features = listOf(
            WhatsNewFeature(Icons.Default.Face, R.string.whats_new_250_faces_title, R.string.whats_new_250_faces_body),
            WhatsNewFeature(Icons.Default.NoAccounts, R.string.whats_new_250_noaccount_title, R.string.whats_new_250_noaccount_body),
            WhatsNewFeature(Icons.Default.Search, R.string.whats_new_250_search_title, R.string.whats_new_250_search_body),
            WhatsNewFeature(Icons.Default.Public, R.string.whats_new_250_map_title, R.string.whats_new_250_map_body),
            WhatsNewFeature(Icons.Default.SwapVert, R.string.whats_new_250_import_title, R.string.whats_new_250_import_body),
            WhatsNewFeature(Icons.Default.Brush, R.string.whats_new_250_editor_title, R.string.whats_new_250_editor_body),
            WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_250_video_title, R.string.whats_new_250_video_body),
            WhatsNewFeature(Icons.Default.Edit, R.string.whats_new_250_details_title, R.string.whats_new_250_details_body),
            WhatsNewFeature(Icons.Default.TextFields, R.string.whats_new_250_text_title, R.string.whats_new_250_text_body),
            WhatsNewFeature(Icons.Default.Difference, R.string.whats_new_250_duplicates_title, R.string.whats_new_250_duplicates_body),
            WhatsNewFeature(Icons.Default.HdrOn, R.string.whats_new_250_hdr_title, R.string.whats_new_250_hdr_body),
            WhatsNewFeature(Icons.Default.DeleteSweep, R.string.whats_new_250_trash_title, R.string.whats_new_250_trash_body),
            WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_250_privateshare_title, R.string.whats_new_250_privateshare_body),
            WhatsNewFeature(Icons.Default.PhoneAndroid, R.string.whats_new_250_defaultgallery_title, R.string.whats_new_250_defaultgallery_body),
            WhatsNewFeature(Icons.Default.Palette, R.string.whats_new_250_themes_title, R.string.whats_new_250_themes_body),
            WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_250_compression_title, R.string.whats_new_250_compression_body),
            WhatsNewFeature(Icons.Default.Settings, R.string.whats_new_250_settingssearch_title, R.string.whats_new_250_settingssearch_body),
        ),
        moreRes = R.string.whats_new_250_more,
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

/** Looks up a release by its version string, falling back to the newest for an unknown one. */
internal fun whatsNewReleaseFor(version: String?): WhatsNewRelease =
    WhatsNewReleases.firstOrNull { it.version == version } ?: LatestWhatsNewRelease

/** The release's own headline, used as the one-line summary in the version list. */
internal val WhatsNewRelease.headlineRes: Int get() = hero?.titleRes ?: features.first().titleRes
