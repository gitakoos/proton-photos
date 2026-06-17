/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import androidx.compose.material3.AlertDialog
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
 * Unified error display for screens that previously used either snackbars (too brief
 * for multi-line backend errors) or inline red [Text] (no copy, no dismiss). Built on
 * Material 3 [AlertDialog] so it adopts the same elevation / scrim / focus trap as
 * [ConfirmDialog], and styled with the same `containerColor` + `TextButton` tokens
 * so they read as siblings in the design system.
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
@Composable
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.cardBg,
        titleContentColor = colors.fgPrimary,
        textContentColor = colors.fgDim,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = displayedMessage,
                    color = colors.fgDim,
                    fontSize = 13.sp,
                )
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(resolvedDismissLabel, color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            // Optional action + copy buttons live in the dismissButton slot so M3 keeps
            // the primary "OK" rightmost. The slot accepts a single composable, so we
            // pack both into a Row when present.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel, color = colors.accent, fontWeight = FontWeight.Medium)
                    }
                }
                if (onCopy != null) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(message))
                        onCopy()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = colors.fgDim,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.err_copy), color = colors.fgDim)
                    }
                }
            }
        },
    )
}
