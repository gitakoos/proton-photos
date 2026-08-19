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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import eu.akoos.photos.presentation.theme.Bg2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor

/**
 * Unified error display for screens that would otherwise use either a snackbar (too brief
 * for multi-line backend errors) or inline red [Text] (no copy, no dismiss). Built as a
 * Material 3 [ModalBottomSheet] (a bottom drawer), the same surface as [ConfirmDialog] and
 * the other confirmations, styled with the same container + button tokens so they read as
 * siblings in the design system.
 *
 * Contract:
 *  - [message] is expected to already be passed through
 *    `eu.akoos.photos.util.sanitizeErrorMessage` (or its `redact` alias) by the
 *    caller. This composable does NOT redact — keeping that contract caller-side
 *    means an unsanitized server payload is impossible to surface here by accident.
 *  - Long messages (> 200 chars) collapse to a preview + "Show more" toggle so the
 *    dialog never grows tall enough to push the action row off-screen.
 *  - The body sits in a scrollable [Column] capped at 280.dp so even fully expanded
 *    multi-paragraph errors get a scrollbar instead of clipping.
 *
 * Button slots (right-aligned in the action row):
 *  - Optional action button (e.g. "Retry") on the left of the row.
 *  - Optional "Copy" button — clipboard write happens here; the [onCopy] callback
 *    runs after the write so the caller can show a "Copied" snackbar.
 *  - Dismiss button always last, matching M3's primary-action-rightmost convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION") // The suspend Clipboard API is unnecessary for a synchronous copy in a click handler.
fun ErrorPopup(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String? = null,
) {
    val colors = AppColors.current
    val resolvedDismissLabel = dismissLabel ?: stringResource(R.string.err_dismiss_ok)
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message) { mutableStateOf(false) }

    // Long-message handling: collapse to first 200 chars + ellipsis + "Show more"
    // toggle. The 200-char threshold mirrors the sanitizer's own cap (post-redaction)
    // — anything longer than that here arrived pre-truncated already, but keeping
    // the toggle path means future callers that bypass the sanitizer don't blow up
    // the dialog height.
    val isLong = message.length > 200
    val displayedMessage = if (isLong && !expanded) {
        message.substring(0, 200) + "…"
    } else {
        message
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = displayedMessage, color = colors.fgDim, fontSize = 14.sp)
                if (isLong) {
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (expanded) stringResource(R.string.err_show_less)
                                else stringResource(R.string.err_show_more),
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onCopy != null) {
                    SecondaryButton(
                        stringResource(R.string.err_copy),
                        { clipboard.setText(AnnotatedString(message)); onCopy() },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ContentCopy,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    SecondaryButton(actionLabel, onAction, modifier = Modifier.weight(1f))
                }
                PrimaryButton(resolvedDismissLabel, onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}
