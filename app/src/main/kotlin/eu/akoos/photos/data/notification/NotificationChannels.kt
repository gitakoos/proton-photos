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

package eu.akoos.photos.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Registers the channel [id] once, with the settings every channel in the app shares.
 *
 * Idempotent: an already-registered channel is left alone, so a user who changed its importance or
 * sound in system settings keeps that choice. [description] is required because it is what the
 * system shows under the channel name, and a channel without one reads as unexplained.
 *
 * [silent] drops the sound and vibration, for the channels that only ever carry ongoing progress or
 * a service's own presence.
 */
fun ensureNotificationChannel(
    context: Context,
    id: String,
    name: String,
    description: String,
    importance: Int,
    silent: Boolean,
) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(id) != null) return
    val channel = NotificationChannel(id, name, importance).apply {
        this.description = description
        setShowBadge(false)
        if (silent) {
            setSound(null, null)
            enableVibration(false)
        }
    }
    nm.createNotificationChannel(channel)
}
