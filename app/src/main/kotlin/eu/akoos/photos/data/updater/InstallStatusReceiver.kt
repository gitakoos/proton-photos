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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Terminal callback of the update install session committed by [UpdateInstaller.installViaSession].
 * Not exported: it is only ever reached through the PendingIntent handed to that session, which
 * runs with this app's own identity.
 *
 * A silent update reports [PackageInstaller.STATUS_SUCCESS] and nothing else happens here. The
 * OS falls back to [PackageInstaller.STATUS_PENDING_USER_ACTION] whenever a precondition for the
 * silent path stops holding — a raised targetSdk floor in a future Android release being the one
 * guaranteed to bite eventually — so the confirmation screen it hands back is forwarded, never
 * discarded.
 *
 * Reaches the singleton bus through a Hilt EntryPoint since a BroadcastReceiver is not injected.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InstallStatusEntryPoint {
        fun installSessionEvents(): InstallSessionEvents
        fun updateNotifier(): UpdateNotifier
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, UNKNOWN_STATUS)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val outcome = when (verdictForStatus(status)) {
            InstallStatusVerdict.PENDING_USER_ACTION -> {
                val confirmation = confirmationIntent(intent)
                if (confirmation != null) {
                    InstallOutcome.PendingUserAction(confirmation)
                } else {
                    InstallOutcome.Failed(message)
                }
            }
            InstallStatusVerdict.SUCCESS -> InstallOutcome.Success
            InstallStatusVerdict.FAILURE -> InstallOutcome.Failed(message)
        }

        runCatching {
            val entryPoint = EntryPointAccessors
                .fromApplication(context.applicationContext, InstallStatusEntryPoint::class.java)
            // The running version is now the one the notice offered. Cancelling from here rather
            // than from a UI collector also covers the install that lands with nothing listening.
            if (outcome is InstallOutcome.Success) entryPoint.updateNotifier().cancel()
            val events = entryPoint.installSessionEvents()
            if (!events.publish(outcome) && outcome is InstallOutcome.PendingUserAction) {
                // No UI is attached to launch the confirmation, and dropping it would strand the
                // update half-staged. A receiver has no task of its own, hence NEW_TASK.
                context.startActivity(
                    Intent(outcome.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }.onFailure {
            Log.w(TAG, "Install status $status not delivered: ${it.message}")
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        private const val TAG = "install_status_receiver"
        private const val UNKNOWN_STATUS = Int.MIN_VALUE
        const val ACTION_INSTALL_STATUS = "eu.akoos.photos.action.INSTALL_STATUS"
    }
}
