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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.StatusSynced

private val cardShape = RoundedCornerShape(12.dp)

/**
 * One central place to see, grant and revoke every permission the app uses, each with a short
 * reason. Runtime permissions (media, location, notifications) are requested in-app; special-access
 * permissions (all-files, manage-media, install-from-this-source) and any revoke route open the
 * relevant system page — Android never lets an app drop its own granted permission programmatically,
 * so "Manage" always hands off to the system settings.
 *
 * Grant state is re-read on every ON_RESUME so a change made on a system page shows the moment the
 * user returns. Rows that don't apply to the running OS version are simply not shown.
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = AppColors.current

    // Bump on resume so the grant-state reads below recompute after returning from a system page.
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Runtime-permission launchers; the result callback refreshes the row states.
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }

    // Re-evaluated whenever refreshTick changes.
    val mediaGranted = remember(refreshTick) { hasMediaPermission(context) }
    val locationGranted = remember(refreshTick) { hasMediaLocation(context) }
    val allFilesGranted = remember(refreshTick) { hasAllFilesAccess() }
    val manageMediaGranted = remember(refreshTick) { hasManageMedia(context) }
    val notificationsGranted = remember(refreshTick) { hasNotificationsPermission(context) }
    val installGranted = remember(refreshTick) { hasInstallPackages(context) }

    SettingsSubPageScaffold(title = stringResource(R.string.permissions_title), onBack = onBack) {
        Text(
            stringResource(R.string.permissions_intro),
            color = FgMute, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, cardShape)
                .border(0.5.dp, colors.cardBorder, cardShape),
        ) {
            val rows = buildList<@Composable () -> Unit> {
                // Photos & videos — the core permission, shown on every OS version.
                add {
                    PermissionRow(
                        icon = Icons.Default.PhotoLibrary,
                        title = stringResource(R.string.permissions_photos_title),
                        why = stringResource(R.string.permissions_photos_why),
                        granted = mediaGranted,
                    ) {
                        if (mediaGranted) openAppDetails(context)
                        else mediaLauncher.launch(mediaPermissionList())
                    }
                }
                // Photo location (ACCESS_MEDIA_LOCATION) — API 29+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add {
                    PermissionRow(
                        icon = Icons.Default.LocationOn,
                        title = stringResource(R.string.permissions_location_title),
                        why = stringResource(R.string.permissions_location_why),
                        granted = locationGranted,
                    ) {
                        if (locationGranted) openAppDetails(context)
                        else locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                }
                // All files access (MANAGE_EXTERNAL_STORAGE) — API 30+, system page only.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add {
                    PermissionRow(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(R.string.permissions_allfiles_title),
                        why = stringResource(R.string.permissions_allfiles_why),
                        granted = allFilesGranted,
                    ) { openAllFilesSettings(context) }
                }
                // Manage media (MANAGE_MEDIA) — API 31+, system page only.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add {
                    PermissionRow(
                        icon = Icons.Default.PermMedia,
                        title = stringResource(R.string.permissions_managemedia_title),
                        why = stringResource(R.string.permissions_managemedia_why),
                        granted = manageMediaGranted,
                    ) { openManageMediaSettings(context) }
                }
                // Notifications (POST_NOTIFICATIONS) — API 33+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add {
                    PermissionRow(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.permissions_notifications_title),
                        why = stringResource(R.string.permissions_notifications_why),
                        granted = notificationsGranted,
                    ) {
                        if (notificationsGranted) openAppDetails(context)
                        else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                // Install updates (REQUEST_INSTALL_PACKAGES) — system page only.
                add {
                    PermissionRow(
                        icon = Icons.Default.SystemUpdate,
                        title = stringResource(R.string.permissions_install_title),
                        why = stringResource(R.string.permissions_install_why),
                        granted = installGranted,
                    ) { openInstallSettings(context) }
                }
            }
            rows.forEachIndexed { i, row ->
                row()
                if (i < rows.lastIndex) HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    thickness = 0.5.dp,
                    color = colors.cardBorder,
                )
            }
        }

        // Always-on network access — stated for transparency, no toggle (it can't be revoked
        // per-app and the app is useless offline).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Cloud, null, tint = FgMute, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.permissions_network_note),
                color = FgMute, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    why: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = FgDim, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(why, color = FgMute, fontSize = 12.sp, lineHeight = 16.sp)
        }
        // State + action affordance: a green "Granted" (tap to manage/revoke) or an accent "Allow".
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = StatusSynced, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.permissions_state_granted), color = StatusSynced, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                stringResource(R.string.permissions_action_allow),
                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Grant-state checks ───────────────────────────────────────────────────────

private fun hasMediaPermission(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        mediaPermissionList().all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** The media read permissions appropriate for the running OS. */
private fun mediaPermissionList(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaLocation(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private fun hasManageMedia(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

private fun hasNotificationsPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun hasInstallPackages(context: android.content.Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

// ── System-page hand-offs ────────────────────────────────────────────────────

private fun openAppDetails(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openAllFilesSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val packaged = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Some OEM builds reject the per-app variant; fall back to the global list.
    runCatching { context.startActivity(packaged) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun openManageMediaSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openAppDetails(context) }
}

private fun openInstallSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openAppDetails(context) }
}
