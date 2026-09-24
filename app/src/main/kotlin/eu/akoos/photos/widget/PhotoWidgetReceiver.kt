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

package eu.akoos.photos.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PhotoWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = PhotoWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Exported for the launcher, so any app can send ids here. Only the ids the host binds to this
        // provider are drawn or queued; a made-up id or another app's widget is dropped first.
        val ownIds = WidgetIdGuard.ownIds(context, appWidgetIds)
        super.onUpdate(context, appWidgetManager, ownIds)
        // Refresh each (re-)added widget now. The periodic refresh is registered at the user's
        // chosen interval by the config screen's save() and persists across reboot via WorkManager,
        // so we must NOT re-register it here: calling enqueueOrReplace on every system onUpdate
        // (e.g. after a reboot or host refresh) would reset the user's interval back to the default.
        ownIds.forEach { id ->
            PhotoWidgetUpdateWorker.enqueueImmediate(context, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (WidgetIdGuard.isOwn(context, appWidgetId)) {
            super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // A delete for an id the host still binds here did not come from the host, and acting on it
        // would wipe a live widget's settings, so only ids the host has already let go of are cleaned.
        val deletedIds = WidgetIdGuard.deletedIds(context, appWidgetIds)
        super.onDeleted(context, deletedIds)
        if (deletedIds.isEmpty()) return
        deletedIds.forEach { id -> PhotoWidgetUpdateWorker.cancel(context, id) }
        // goAsync keeps the receiver alive for the off-main cleanup; the scope is
        // created and cancelled within this broadcast so it never outlives it. The Glance base
        // onDeleted may already have taken the broadcast async, in which case goAsync returns null,
        // so guard the finish (calling it on a null result crashed the delete flow).
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                deletedIds.forEach { id -> PhotoWidgetUpdater.cleanup(context, id) }
            } finally {
                pending?.finish()
                scope.cancel()
            }
        }
    }
}
