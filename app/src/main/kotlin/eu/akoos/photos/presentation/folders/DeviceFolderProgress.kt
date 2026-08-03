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

package eu.akoos.photos.presentation.folders

/** A long device-folder operation the screen reports in its progress pill. */
enum class DeviceFolderOperation { Hiding, Restoring, BackingUp }

/**
 * Which of a device folder's long operations the screen's single progress pill is showing.
 *
 * Backing the folder up and moving it in or out of the vault are independent: the drawer gates only
 * the back-up rows on a running back-up, so a hide can start beside one. Nothing about that is wrong
 * until both draw, in the same corner, over each other: the labels differ in length, so part of the
 * covered pill stays tappable and its X stops an operation the user cannot see.
 *
 * One answer here is what keeps the pill's progress and its cancel talking about the same work. The
 * vault outranks the back-up, matching the timeline's chain: a hide moves files off the device, and
 * its stop is the one that has to stay reachable.
 */
object DeviceFolderProgress {

    fun operation(
        vaultRunning: Boolean,
        vaultRestoring: Boolean,
        backupRunning: Boolean,
    ): DeviceFolderOperation? = when {
        vaultRunning && vaultRestoring -> DeviceFolderOperation.Restoring
        vaultRunning -> DeviceFolderOperation.Hiding
        backupRunning -> DeviceFolderOperation.BackingUp
        else -> null
    }
}
