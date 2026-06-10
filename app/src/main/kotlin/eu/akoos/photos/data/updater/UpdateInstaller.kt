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

package eu.akoos.photos.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OS-side install plumbing for the self-updater. Owns the FileProvider URI conversion
 * + the "Install unknown apps" permission gate — both of which Android tightened in 8.0
 * (Oreo) and again in 10 (Q's scoped storage). The caller is always an Activity, since
 * both intents require a running UI surface.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether the OS will allow this app to install APKs. On Android 8+ the user has to
     * grant "Install unknown apps" specifically to our package (it's per-source, not
     * device-wide). Pre-O the global "Unknown sources" toggle covers everything, so the
     * check is moot — we return true. minSdk is 26 so the >= branch always wins in
     * practice; keeping the version guard makes the intent obvious to future readers.
     */
    fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Intent that opens the OS "Install unknown apps" page for our package. The caller
     * launches it with an [androidx.activity.result.ActivityResultLauncher] and re-checks
     * [canInstall] on resume — if the user toggled it on, kick off the install; if they
     * backed out, leave the dialog showing so they can retry without losing progress.
     */
    fun buildPermissionRequestIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Hands the APK to the system installer. We route the file through our FileProvider
     * (declared in AndroidManifest.xml) so the system installer process gets a
     * content:// URI it can read across the StrictMode file-URI boundary that Android 7+
     * enforces. The FLAG_GRANT_READ_URI_PERMISSION temporarily extends the read grant to
     * whichever process the system picks to handle the install.
     */
    fun buildInstallIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.updater.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
