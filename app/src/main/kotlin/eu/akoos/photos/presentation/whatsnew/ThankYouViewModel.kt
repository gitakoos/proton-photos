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

package eu.akoos.photos.presentation.whatsnew

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.util.ThankYouController
import javax.inject.Inject

/**
 * Thin view-model over the singleton [ThankYouController]. Every instance (the NavGraph host that
 * renders the dialog, and the About screen's hidden preview trigger) delegates to the same controller,
 * so raising the popup from one place shows it wherever it is rendered.
 */
@HiltViewModel
class ThankYouViewModel @Inject constructor(
    private val controller: ThankYouController,
) : ViewModel() {
    val visible = controller.visible

    suspend fun shouldShow() = controller.shouldShow()

    fun showReal(thenWhatsNew: Boolean) = controller.showReal(thenWhatsNew)

    fun showPreview(thenWhatsNew: Boolean) = controller.showPreview(thenWhatsNew)

    /** Hide the popup and return true when What's New should open next. */
    fun dismiss(): Boolean = controller.dismiss()
}
