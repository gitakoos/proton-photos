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

package eu.akoos.photos.presentation.gallery

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import eu.akoos.photos.domain.usecase.CloudMetadataSaveController
import javax.inject.Inject

/**
 * Backs the timeline's cloud-metadata-save drawer. The batch itself lives in the app-scoped
 * [CloudMetadataSaveController], so the progress the editor kicked off keeps running after the editor
 * closes; this only relays the controller's live view to the gallery and forwards the two actions the
 * drawer offers.
 */
@HiltViewModel
class CloudSaveDrawerViewModel @Inject constructor(
    private val controller: CloudMetadataSaveController,
) : ViewModel() {
    val ui: StateFlow<CloudMetadataSaveController.SaveUi?> = controller.ui
    fun dismiss() = controller.dismiss()
    fun cancel() = controller.cancel()
}
