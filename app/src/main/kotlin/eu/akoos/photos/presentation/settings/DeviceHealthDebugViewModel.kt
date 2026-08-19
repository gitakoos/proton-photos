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

package eu.akoos.photos.presentation.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.HealthSnapshot
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs the DEBUG-only device-health readout in Settings: exposes the live [HealthSnapshot] so the
 * card can render it and the verdict derived from it while the device state is simulated over adb.
 */
@HiltViewModel
class DeviceHealthDebugViewModel @Inject constructor(
    deviceHealth: DeviceHealthPolicy,
) : ViewModel() {
    val snapshot: StateFlow<HealthSnapshot> = deviceHealth.snapshot
}
