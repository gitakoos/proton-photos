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

package eu.akoos.photos.data.sharedbackup

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The list of the backups on Drive read last for each account in this app session, so the list opens with it at
 * once while it is read again. Memory only: it goes with the process.
 */
@Singleton
class DriveCopiesMemory @Inject constructor() {
    private val lists = ConcurrentHashMap<String, List<DriveDeviceCopies>>()

    fun get(userId: String): List<DriveDeviceCopies>? = lists[userId]

    fun put(userId: String, devices: List<DriveDeviceCopies>) {
        lists[userId] = devices
    }

    /** Drops [deviceId] from the list kept for [userId], after its copies went to the Drive trash. */
    fun drop(userId: String, deviceId: String) {
        lists.computeIfPresent(userId) { _, devices -> devices.filterNot { it.deviceId == deviceId } }
    }
}
