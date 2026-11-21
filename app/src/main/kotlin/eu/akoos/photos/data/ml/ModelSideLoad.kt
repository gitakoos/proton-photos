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

package eu.akoos.photos.data.ml

import android.content.Context
import eu.akoos.photos.BuildConfig
import java.io.File

/**
 * The side-load directory a model manager reads extra model files from, or null when side-loading
 * is off. Only debug builds honour it: on older Android versions the app's external files folder
 * is writable by other apps, so a release build must never load a model from there.
 */
internal fun modelSideLoadDir(context: Context, directory: String): File? =
    sideLoadDirFor(BuildConfig.DEBUG, context.getExternalFilesDir(null), directory)

internal fun sideLoadDirFor(debug: Boolean, externalFiles: File?, directory: String): File? =
    if (debug) externalFiles?.let { File(it, directory) } else null
