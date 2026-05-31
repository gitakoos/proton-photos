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

package eu.akoos.photos.presentation.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.rememberDebouncedAction
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Tap target from the main Settings Account row. Shows the user's name + email plus
 * their Proton Drive storage band (we don't have a server side plan name surfaced
 * cheaply, but cloudMaxBytes is a clean proxy for "Free 5 GB / Plus 200 GB / etc."
 * and matches what the user sees on proton.me). Two quick links open the Proton
 * web surfaces in the user's default browser via a plain ACTION_VIEW — no in app
 * webview, no fingerprinting we'd add, the user's browser owns TLS verification.
 *
 * Sign out lives here too (collapsing the duplicate button from the original tiny
 * Account row on the parent Settings page; we keep the row tappable but the button
 * moves into this dedicated surface).
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val context = LocalContext.current

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_account_section),
        onBack = onBack,
    ) {
        // ── Profile card ───────────────────────────────────────────────────
        // Big avatar + name + email. No avatar URL is exposed by ProtonCore in
        // the user surface, so the initial letter style scales up the same
        // gradient we use on the parent Settings page header.
        SettingsCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(96.dp).background(
                        Brush.linearGradient(
                            listOf(colors.accent, colors.accent2),
                            Offset.Zero,
                            Offset(220f, 220f),
                        ),
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.userDisplayName.firstOrNull()?.uppercaseChar()?.toString()
                            ?: state.userEmail.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    state.userDisplayName.ifEmpty { state.userEmail },
                    color = colors.fgPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.userDisplayName.isNotEmpty() && state.userEmail.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.userEmail,
                        color = colors.fgMute,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Storage row ────────────────────────────────────────────────────
        // Mirrors ProtonStorageRow on Settings; cloudMaxBytes is the closest
        // proxy for the user's plan tier without a paid-plan API call.
        CollapsibleSection(label = stringResource(R.string.settings_account_storage)) {
            SettingsCard {
                ProtonStorageRow(state = state)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Web links ──────────────────────────────────────────────────────
        // Account.proton.me for plan / billing / privacy controls (including
        // the telemetry opt out the Privacy page references). drive.proton.me
        // for the full Drive web client. Plain ACTION_VIEW lets the user's
        // default browser handle TLS + cookies — no embedded webview, no
        // Custom Tabs dependency, no fingerprinting hooks we control.
        CollapsibleSection(label = stringResource(R.string.settings_account_links)) {
            SettingsCard {
                AccountLinkRow(
                    label = stringResource(R.string.settings_account_link_manage),
                    description = stringResource(R.string.settings_account_link_manage_desc),
                    onClick = { openExternalUrl(context, "https://account.proton.me/drive/dashboard") },
                )
                RowDivider()
                AccountLinkRow(
                    label = stringResource(R.string.settings_account_link_drive_web),
                    description = stringResource(R.string.settings_account_link_drive_web_desc),
                    onClick = { openExternalUrl(context, "https://drive.proton.me/photos") },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Sign out ───────────────────────────────────────────────────────
        var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { showSignOutDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_sign_out),
                        color = colors.errorColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_sign_out_desc),
                        color = colors.fgMute,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (showSignOutDialog) {
            ConfirmDialog(
                title = stringResource(R.string.sign_out_dialog_title),
                message = stringResource(R.string.sign_out_dialog_message),
                confirmLabel = stringResource(R.string.sign_out_dialog_confirm),
                dismissLabel = stringResource(R.string.sign_out_dialog_cancel),
                onConfirm = {
                    showSignOutDialog = false
                    onSignOut()
                },
                onDismiss = { showSignOutDialog = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun AccountLinkRow(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val debounced = rememberDebouncedAction { onClick() }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = debounced)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 12.sp)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = colors.fgMute,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Hand the URL to whichever browser the user has set as default. ACTION_VIEW
 * with FLAG_ACTIVITY_NEW_TASK because the Activity context is a Hilt aware
 * AppCompatActivity host that already has the right task affinity — we don't
 * want to bring the browser INTO our task stack.
 */
private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
