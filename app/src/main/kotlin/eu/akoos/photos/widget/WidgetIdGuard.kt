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
import android.content.ComponentName
import android.content.Context

/** The provider component the host binds a widget id to, as the host reports it. */
internal data class WidgetProviderName(val packageName: String?, val className: String?)

/**
 * Whether a widget's provider is this app's photo widget receiver. Both halves of the component have
 * to match, and a provider missing either one (no widget behind the id) is never ours.
 */
internal fun isOwnWidget(
    providerPackage: String?,
    providerClass: String?,
    ownPackage: String,
    ownReceiverClass: String,
): Boolean = providerPackage != null && providerClass != null &&
    providerPackage == ownPackage && providerClass == ownReceiverClass

/**
 * Whether [appWidgetId] names one of this app's own widgets. The invalid id is refused before
 * [providerOf] is asked, and an id the host holds no widget for has no provider to match.
 */
internal fun isOwnWidgetId(
    appWidgetId: Int,
    ownPackage: String,
    ownReceiverClass: String,
    providerOf: (Int) -> WidgetProviderName?,
): Boolean {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
    val provider = providerOf(appWidgetId) ?: return false
    return isOwnWidget(provider.packageName, provider.className, ownPackage, ownReceiverClass)
}

/** The ids an update may draw and queue work for: this app's own widgets only. */
internal fun ownWidgetIds(appWidgetIds: IntArray, isOwn: (Int) -> Boolean): IntArray =
    appWidgetIds.filter(isOwn).toIntArray()

/**
 * The ids a delete may clean up. The host unbinds a widget before it tells the provider the widget is
 * gone, so an id still bound to this provider is a live widget, and a delete naming it did not come
 * from the host. Wiping its settings and its bitmap would only break a widget the user still has.
 */
internal fun deletedWidgetIds(appWidgetIds: IntArray, isOwn: (Int) -> Boolean): IntArray =
    appWidgetIds.filter { it != AppWidgetManager.INVALID_APPWIDGET_ID && !isOwn(it) }.toIntArray()

/**
 * Checks widget ids against the host before anything acts on them.
 *
 * The configuration activity and the receiver are both exported, since the launcher has to reach
 * them, so any app can hand them an id. An id is ours only when the host binds it to
 * [PhotoWidgetReceiver]: a made-up id, another app's widget and one the host no longer holds are not.
 */
object WidgetIdGuard {

    fun isOwn(context: Context, appWidgetId: Int): Boolean {
        val own = ComponentName(context, PhotoWidgetReceiver::class.java)
        return isOwnWidgetId(appWidgetId, own.packageName, own.className) { id ->
            runCatching {
                AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider
                    ?.let { WidgetProviderName(it.packageName, it.className) }
            }.getOrNull()
        }
    }

    fun ownIds(context: Context, appWidgetIds: IntArray): IntArray =
        ownWidgetIds(appWidgetIds) { isOwn(context, it) }

    fun deletedIds(context: Context, appWidgetIds: IntArray): IntArray =
        deletedWidgetIds(appWidgetIds) { isOwn(context, it) }
}
