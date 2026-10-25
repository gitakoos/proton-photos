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

package eu.akoos.photos.presentation.common

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/**
 * A single question/answer pair, both referencing string resources. [labels] are the strings filled into the
 * answer's placeholders in order: the names of the screens and buttons it points at, read from the same
 * resources the app shows, so the answer always names them as they appear; [questionLabels] do the same for the
 * question.
 */
data class FaqEntry(
    @StringRes val question: Int,
    @StringRes val answer: Int,
    val labels: List<Int> = emptyList(),
    val questionLabels: List<Int> = emptyList(),
)

/** A titled group of [FaqEntry] rows. */
data class FaqGroup(@StringRes val title: Int, val entries: List<FaqEntry>)

/** Shared FAQ content rendered identically in onboarding and in Settings. */
val faqGroups: List<FaqGroup> = listOf(
    FaqGroup(
        R.string.faq_section_backup,
        listOf(
            FaqEntry(R.string.faq_q_cloud_icons, R.string.faq_a_cloud_icons),
            FaqEntry(
                R.string.faq_q_backup, R.string.faq_a_backup,
                listOf(R.string.sync_open_settings, R.string.settings_sync_wifi_only, R.string.gallery_tab_photos),
            ),
            FaqEntry(
                R.string.faq_q_folders, R.string.faq_a_folders,
                listOf(
                    R.string.sync_open_settings, R.string.settings_backup_everything,
                    R.string.settings_backup_folders, R.string.gallery_tab_photos,
                ),
            ),
            FaqEntry(
                R.string.faq_q_free_space, R.string.faq_a_free_space,
                listOf(R.string.settings_delete_after_backup, R.string.sync_open_settings, R.string.delete_multi_remove_device),
            ),
            FaqEntry(
                R.string.faq_q_mirror_local, R.string.faq_a_mirror_local,
                questionLabels = listOf(R.string.settings_mirror_strip_local),
            ),
            FaqEntry(R.string.faq_q_existing, R.string.faq_a_existing),
        ),
    ),
    FaqGroup(
        R.string.faq_section_albums,
        listOf(
            FaqEntry(
                R.string.faq_q_albums, R.string.faq_a_albums,
                listOf(R.string.sync_open_settings, R.string.settings_backup_folders, R.string.sync_folder_album_label),
            ),
            FaqEntry(
                R.string.faq_q_album_create, R.string.faq_a_album_create,
                listOf(
                    R.string.gallery_tab_albums, R.string.albums_new_album,
                    R.string.gallery_add_to_album, R.string.album_add_photos,
                ),
            ),
            FaqEntry(
                R.string.faq_q_album_share, R.string.faq_a_album_share,
                listOf(R.string.albums_share_button, R.string.gallery_tab_shared, R.string.gallery_tab_albums),
            ),
            FaqEntry(R.string.faq_q_album_contribute, R.string.faq_a_album_contribute),
            FaqEntry(
                R.string.faq_q_folders_albums, R.string.faq_a_folders_albums,
                listOf(
                    R.string.album_card_folder_badge, R.string.albums_filter_cloud,
                    R.string.albums_filter_local, R.string.gallery_tab_albums,
                ),
            ),
            FaqEntry(
                R.string.faq_q_album_delete, R.string.faq_a_album_delete,
                listOf(R.string.album_remove_from_album, R.string.settings_recently_deleted),
            ),
        ),
    ),
    FaqGroup(
        R.string.faq_section_where,
        listOf(
            FaqEntry(
                R.string.faq_q_where_ml, R.string.faq_a_where_ml,
                listOf(R.string.settings_more_title, R.string.settings_ai_section, R.string.gallery_tab_photos),
            ),
            FaqEntry(
                R.string.faq_q_where_lock, R.string.faq_a_where_lock,
                listOf(
                    R.string.settings_more_title, R.string.settings_privacy_security, R.string.settings_app_lock,
                    R.string.settings_hidden_photos, R.string.sel_label_hide,
                ),
            ),
            FaqEntry(
                R.string.faq_q_where_drive, R.string.faq_a_where_drive,
                listOf(R.string.settings_more_title, R.string.drive_backup_section, R.string.drive_backup_auto),
            ),
            FaqEntry(
                R.string.faq_q_where_trash, R.string.faq_a_where_trash,
                listOf(R.string.settings_recently_deleted, R.string.gallery_tab_photos),
            ),
            FaqEntry(
                R.string.faq_q_no_account, R.string.faq_a_no_account,
                listOf(R.string.sign_in_continue_without_account),
            ),
        ),
    ),
)

/**
 * Standalone accordion of the shared [faqGroups]: a small caps title per group over a card of rows. Each row is a
 * tappable question header with a rotating chevron; the answer expands under it via [AnimatedVisibility].
 * Kept free of the internal settings.components composables so onboarding and Settings can both embed it.
 */
@Composable
fun FaqAccordion(modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        faqGroups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) Spacer(Modifier.height(20.dp))
            Text(
                stringResource(group.title).uppercase(),
                color = colors.fgMute,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(14.dp)),
            ) {
                group.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(0.5.dp)
                                .background(colors.cardBorder),
                        )
                    }
                    FaqRow(entry)
                }
            }
        }
    }
}

@Composable
private fun FaqRow(entry: FaqEntry) {
    val colors = AppColors.current
    var expanded by rememberSaveable(entry.question) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val questionLabels = entry.questionLabels.map { stringResource(it) }
            Text(
                text = if (questionLabels.isEmpty()) {
                    stringResource(entry.question)
                } else {
                    stringResource(entry.question, *questionLabels.toTypedArray())
                },
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer(rotationZ = if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            val labels = entry.labels.map { stringResource(it) }
            Text(
                text = if (labels.isEmpty()) stringResource(entry.answer) else stringResource(entry.answer, *labels.toTypedArray()),
                color = colors.fgMute,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
