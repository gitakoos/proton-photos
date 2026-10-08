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

package eu.akoos.photos.presentation.util

import androidx.annotation.StringRes
import eu.akoos.photos.R

/** One row of the language picker: the per-app language tag and its label. */
data class AppLanguage(val tag: String, @StringRes val labelRes: Int)

/** The languages the app can be switched to, in picker order. Settings and onboarding share this list. */
object AppLanguages {
    val all: List<AppLanguage> = listOf(
        AppLanguage("system", R.string.settings_language_system),
        AppLanguage("en", R.string.language_en),
        AppLanguage("de", R.string.language_de),
        AppLanguage("es", R.string.language_es),
        AppLanguage("fr", R.string.language_fr),
        AppLanguage("hu", R.string.language_hu),
        AppLanguage("it", R.string.language_it),
        AppLanguage("nl", R.string.language_nl),
        AppLanguage("pl", R.string.language_pl),
        AppLanguage("sk", R.string.language_sk),
        AppLanguage("sl", R.string.language_sl),
        AppLanguage("cs", R.string.language_cs),
        AppLanguage("tr", R.string.language_tr),
    )
}
