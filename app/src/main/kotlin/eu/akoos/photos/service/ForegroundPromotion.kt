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


package eu.akoos.photos.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Promotes a data-sync service to the foreground. Called as the first thing a service does, before
 * dependency injection, with a bare placeholder notification the service replaces once its real one is
 * ready: a service started through `startForegroundService` has only seconds to reach this call, and a
 * cold process start plus the injection graph can eat that on a slow device. A refused promotion is
 * left to the service's own start handler, which falls back to stopping.
 */
internal fun Service.startForegroundEarly(notificationId: Int, notification: Notification) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, notification)
        }
    }
}
