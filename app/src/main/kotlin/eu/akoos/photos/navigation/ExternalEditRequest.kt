/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.navigation

/**
 * Carries an external photo/video that arrived via ACTION_EDIT or ACTION_VIEW intent.
 * MainActivity extracts the values from intent.data / intent.type / a ContentResolver
 * display-name query and hands the request to NavGraph, which routes either to the
 * viewer (ACTION_VIEW, the "Open with" chooser path) or to the editor (ACTION_EDIT,
 * the "Edit with" chooser path).
 *
 * Save flow for external entries is always copy-to-MediaStore (the user's gallery),
 * never in-place overwrite — the foreign URI may be read-only and our app should never
 * mutate files we didn't create.
 */
data class ExternalEditRequest(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** True when the launching intent was ACTION_VIEW (system file managers, gallery
     *  apps tapping "Open with"). Routed to the photo viewer in read-only mode.
     *  False when the intent was ACTION_EDIT, in which case we go straight to the
     *  editor for that media type. */
    val isViewOnly: Boolean,
)
