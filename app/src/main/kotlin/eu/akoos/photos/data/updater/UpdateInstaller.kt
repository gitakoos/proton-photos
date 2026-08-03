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

package eu.akoos.photos.data.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OS-side install plumbing for the self-updater. Owns the PackageInstaller session that applies
 * an update without a confirmation dialog, the FileProvider URI conversion behind the legacy
 * intent fallback, and the "Install unknown apps" permission gate — the latter two being what
 * Android tightened in 8.0 (Oreo) and again in 10 (Q's scoped storage).
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
     * Applies [apkFile] through a PackageInstaller session, which is the only route that can
     * update this app without the system's confirmation screen.
     *
     * The OS waives that screen for a session whose owner asks for
     * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED], declares
     * `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, holds a granted `REQUEST_INSTALL_PACKAGES`, is
     * installing itself, and ships an APK whose targetSdk clears the floor that Android raises
     * with each release. Any of those slipping turns the commit into
     * [PackageInstaller.STATUS_PENDING_USER_ACTION] rather than a failure, so the confirmation
     * path stays wired up permanently, not as an edge case.
     *
     * Returns synchronously once the session is committed; the terminal result lands on
     * [InstallSessionEvents] via [InstallStatusReceiver].
     */
    fun installViaSession(apkFile: File): InstallOutcome {
        // Signer identity first: nothing is staged for an APK that is not ours.
        if (!verifyApkSignature(apkFile)) return InstallOutcome.SignatureMismatch
        if (!isCandidateNewerThanInstalled(apkFile)) return InstallOutcome.NotNewer

        val packageInstaller = context.packageManager.packageInstaller
        var sessionId = INVALID_SESSION_ID
        var session: PackageInstaller.Session? = null
        var committed = false
        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Update ownership keeps later silent updates routed through this installer.
                    setRequestUpdateOwnership(true)
                }
            }
            sessionId = packageInstaller.createSession(params)
            val open = packageInstaller.openSession(sessionId)
            session = open
            open.openWrite(SESSION_APK_NAME, 0L, apkFile.length()).use { output ->
                apkFile.inputStream().use { input -> input.copyTo(output) }
                open.fsync(output)
            }
            open.commit(statusIntentSender(sessionId))
            committed = true
            InstallOutcome.Committed
        } catch (t: Throwable) {
            InstallOutcome.Failed(t.message)
        } finally {
            runCatching { session?.close() }
            // An uncommitted session keeps its staged bytes in the installer until abandoned.
            if (!committed && sessionId != INVALID_SESSION_ID) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
    }

    /**
     * Compares the archive's versionCode against the installed one. Android refuses a downgrade
     * with an opaque installer error, so the refusal is made here where the caller can name it.
     * Fail-closed: an unreadable archive counts as not newer.
     */
    private fun isCandidateNewerThanInstalled(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, 0)
            val candidate = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return false
            isCandidateNewer(
                installedVersionCode = PackageInfoCompat.getLongVersionCode(installed),
                candidateVersionCode = PackageInfoCompat.getLongVersionCode(candidate),
            )
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Callback channel for the committed session. The OS writes its status extras into this
     * Intent, which is why the PendingIntent has to be mutable on Android 12+. The request code
     * carries the session id so two sessions can never collapse onto one PendingIntent.
     */
    private fun statusIntentSender(sessionId: Int): IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java)
            .setAction(InstallStatusReceiver.ACTION_INSTALL_STATUS)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            STATUS_REQUEST_CODE_BASE + sessionId,
            intent,
            flags,
        ).intentSender
    }

    /**
     * Hands the APK to the system installer. Last-resort fallback for when a session cannot be
     * created, written or committed. We route the file through our FileProvider (declared in
     * AndroidManifest.xml) so the system installer process gets a content:// URI it can read
     * across the StrictMode file-URI boundary that Android 7+ enforces. The
     * FLAG_GRANT_READ_URI_PERMISSION temporarily extends the read grant to whichever process
     * the system picks to handle the install.
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

    /**
     * True only if [apkFile] is signed by the exact same certificate(s) as this installed app.
     * Blocks installing a tampered/substituted APK (e.g. a MITM on the GitHub download) since
     * the system installer would otherwise reject a mismatched signer with an opaque error
     * mid-flow. Fail-closed: any parse/lookup error returns false (don't install).
     */
    fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val installed = signerSha256(installedSigners())
            val candidate = signerSha256(apkSigners(apkFile.absolutePath))
            installed.isNotEmpty() && installed == candidate
        } catch (t: Throwable) {
            false
        }
    }

    private fun installedSigners(): Array<Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
    }

    private fun apkSigners(path: String): Array<Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.signatures ?: emptyArray()
        }
    }

    private fun signerSha256(signatures: Array<Signature>): Set<String> =
        signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()

    private companion object {
        const val INVALID_SESSION_ID = -1
        /** Name of the single entry written into the session; a label, not a filesystem path. */
        const val SESSION_APK_NAME = "update.apk"
        /** Offset by the session id so concurrent sessions get distinct PendingIntents. */
        const val STATUS_REQUEST_CODE_BASE = 9310
    }
}

/**
 * Outcome of an update install. A type rather than a Boolean so the caller can separate a
 * deliberately blocked install (wrong signer, not actually newer) from a plumbing failure that
 * still deserves the legacy intent fallback.
 */
sealed class InstallOutcome {
    /** Session committed. The terminal result arrives on [InstallSessionEvents]. */
    data object Committed : InstallOutcome()

    /** The OS applied the update. */
    data object Success : InstallOutcome()

    /** The OS wants its confirmation screen; [intent] has to be launched to continue. */
    data class PendingUserAction(val intent: Intent) : InstallOutcome()

    /** The archive is not signed by this app's certificate. */
    data object SignatureMismatch : InstallOutcome()

    /** The archive's versionCode does not rank above the installed one. */
    data object NotNewer : InstallOutcome()

    /** Anything else. The caller may retry through [UpdateInstaller.buildInstallIntent]. */
    data class Failed(val message: String?) : InstallOutcome()
}

/** Coarse reading of `PackageInstaller.EXTRA_STATUS`, kept free of Android types so it is pure. */
enum class InstallStatusVerdict {
    PENDING_USER_ACTION,
    SUCCESS,
    FAILURE,
}

/**
 * Maps a session status int onto a verdict. Every value other than the two known-good ones is a
 * failure, including a missing extra, so an unrecognised future status never reads as success.
 */
internal fun verdictForStatus(status: Int): InstallStatusVerdict = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallStatusVerdict.PENDING_USER_ACTION
    PackageInstaller.STATUS_SUCCESS -> InstallStatusVerdict.SUCCESS
    else -> InstallStatusVerdict.FAILURE
}

/** An update may only move the versionCode forward; equal or lower is refused. */
internal fun isCandidateNewer(installedVersionCode: Long, candidateVersionCode: Long): Boolean =
    candidateVersionCode > installedVersionCode
