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

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops every photo the placed widgets hold when an account signs out.
 *
 * A CLOUD_* widget's cached bitmap is a decrypted cloud photo drawn on the launcher, outside the app
 * and its lock, and with the account gone nothing can redraw it, so it has to go on every way out of
 * a session: the Settings sign-out and a session the server ended alike. Both reach
 * [eu.akoos.photos.domain.repository.DrivePhotoRepository.clearCacheForSignOut], which runs this.
 *
 * The Settings sign-out also cancels the widgets' updates as it starts
 * ([PhotoWidgetUpdateWorker.cancelAll]); once the account is no longer the signed-in one, this registers
 * their periodic updates again, so a device widget keeps changing photos after the sign-out.
 *
 * Sign-out only. Sweeping the bitmaps on any other cache clear would blank the launcher while the
 * CLOUD_* modes still need that account to draw the next one.
 */
@Singleton
class PhotoWidgetSignOut @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
) {

    /** Drops the widgets' photos for [userId]'s sign-out, and gives the updates back once it has left. */
    suspend fun clear(userId: UserId) = withContext(Dispatchers.IO) {
        context.cacheDir
            .listFiles { f: File -> f.name.startsWith(PhotoWidgetUpdater.CACHE_FILE_PREFIX) }
            ?.forEach { it.delete() }
        PhotoWidget.clearBitmapCache()
        // Drop the state still pointing at the swept bitmaps so each widget redraws its placeholder
        // now rather than on its next natural tick.
        val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(PhotoWidget::class.java)
        for (glanceId in glanceIds) {
            runCatching {
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().also { mp ->
                        mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                        mp.remove(PhotoWidgetKeys.CURRENT_URI)
                    }
                }
                PhotoWidget().update(context, glanceId)
            }
        }
        // Not while the account is still the signed-in one, as in the Settings sign-out's first pass: an
        // update then could still draw its photos. Its pass after the account is disabled gets here.
        if (runCatching { accountManager.getPrimaryUserId().first() }.getOrNull() != userId) {
            runCatching { PhotoWidgetUpdateWorker.rescheduleAll(context) }
        }
    }
}
