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

package eu.akoos.photos.presentation.news

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.api.model.NewsItem
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors

/** Opening an entry hands it off to the same list on the website, anchored to its id. */
private const val NEWS_WEBSITE_URL = "https://www.photosforproton.eu/news/"

/** The orange an important entry's title is drawn in, on either theme. */
private val NewsImportantColor = Color(0xFFE8833A)

/**
 * The news screen: messages from the developer, plus one clear way to write back. Reached from
 * Settings and from the dot on the settings icon. Opening it marks everything currently listed as
 * seen, so the dot clears the moment the user has actually looked.
 *
 * Feedback sits at the top because it is the thing most people come here to do. The button opens the
 * feedback thread directly, and a quieter line under it points anyone not in that server to the
 * invite first, so the direct link has somewhere to land.
 */
@Composable
fun NewsScreen(onBack: () -> Unit) {
    val vm: NewsViewModel = hiltViewModel()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val newsEnabled by vm.enabled.collectAsStateWithLifecycle()
    // The entries that were new when this screen opened, so their "New" markers stay for the whole
    // visit (the view model marks the feed read on open, which is what clears the settings dot).
    val newAtOpen by vm.newAtOpen.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val context = LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().background(colors.pageBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(floatingHeaderContentTopPadding()))

            inbox.feedbackUrl?.let { feedbackUrl ->
                SettingsCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        Text(
                            stringResource(R.string.news_feedback_heading),
                            color = colors.fgPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.news_feedback_body),
                            color = colors.fgMute,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.news_feedback_note),
                            color = colors.fgMute,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { openUrl(feedbackUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.news_feedback_button),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        inbox.feedbackJoinUrl?.let { joinUrl ->
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.news_feedback_join),
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openUrl(joinUrl) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                        inbox.feedbackEmail?.let { email ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.news_feedback_email, email),
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openUrl("mailto:$email") }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // The switch sits up here rather than below the messages: with a long feed it would
            // otherwise be an entire scroll away, so it stays reachable the moment the screen opens.
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.news_show_title),
                    description = stringResource(R.string.news_show_desc),
                    checked = newsEnabled,
                    onCheckedChange = vm::setNewsEnabled,
                )
            }

            // A line between the controls above and the messages below.
            Spacer(Modifier.height(24.dp))
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(0.5.dp).background(colors.line),
            )
            Spacer(Modifier.height(24.dp))

            if (inbox.items.isEmpty()) {
                Text(
                    stringResource(R.string.news_empty),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                SettingsCard {
                    inbox.items.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        NewsEntry(
                            item,
                            isNew = item.id in newAtOpen,
                            onOpenItem = { openUrl(NEWS_WEBSITE_URL + "#" + it.id) },
                            onOpenLink = ::openUrl,
                        )
                    }
                }
            }

            Spacer(Modifier.height(navBottom + 24.dp))
        }

        FloatingHeader(
            title = stringResource(R.string.news_title),
            onBack = onBack,
        )
    }
}

@Composable
private fun NewsEntry(
    item: NewsItem,
    isNew: Boolean,
    onOpenItem: (NewsItem) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val colors = AppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenItem(item) }
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                item.title,
                color = if (item.important) NewsImportantColor else colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            if (isNew) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        stringResource(R.string.news_new_badge),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text("#${item.id}", color = colors.fgMute, fontSize = 11.5.sp)
        }
        if (item.date.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(item.date, color = colors.fgMute, fontSize = 12.sp)
        }
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(item.body, color = colors.fgDim, fontSize = 14.sp, lineHeight = 20.sp)
        }
        item.link?.takeIf { it.isNotBlank() }?.let { link ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.news_read_more),
                color = colors.accent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onOpenLink(link) }.padding(vertical = 2.dp),
            )
        }
    }
}
