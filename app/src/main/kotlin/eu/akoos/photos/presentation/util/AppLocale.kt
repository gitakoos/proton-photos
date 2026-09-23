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

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The app's language as a [Locale]: the per-app language [LocaleOverride] applies, else the system's
 * first language. For text built outside the string resources (a country named from its ISO code, a
 * collation order) that has to read in the same language as the strings around it. [LocaleOverride]
 * swaps [LocalContext] for one with localized resources in the frame the app language changes, while
 * [LocalConfiguration] follows only once the configuration change lands, so the locale comes from the
 * context's resources.
 */
@SuppressLint("LocalContextConfigurationRead")
@Composable
@ReadOnlyComposable
fun appLocale(): Locale {
    // stringResource reads LocaleOverride's localized context; reading LocalConfiguration recomposes on changes.
    LocalConfiguration.current
    return LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
}
