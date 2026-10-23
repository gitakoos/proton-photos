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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.R
import eu.akoos.photos.data.sdk.SdkRoute
import eu.akoos.photos.data.sdk.SdkRouting
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.util.SdkDiagnostics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Switches that send parts of the app through Proton's Drive SDK instead of the app's own requests, one
 * per [SdkRoute], all off until turned on. The page also counts, for this run of the app, how many
 * operations went through the SDK and how many fell back to the usual way after an SDK failure.
 */
@Composable
fun SdkSettingsScreen(
    onBack: () -> Unit,
    viewModel: SdkSettingsViewModel = hiltViewModel(),
) {
    val switchedOn by viewModel.switchedOn.collectAsStateWithLifecycle()
    // The counts are read again whenever an SDK call or a fallback is counted.
    val activity by SdkDiagnostics.changes.collectAsStateWithLifecycle()
    val counts = remember(activity) {
        SdkRoute.entries.associateWith { SdkDiagnostics.sdkCount(it.label) to SdkDiagnostics.fallbackCount(it.label) }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.sdk_settings_title), onBack = onBack) {
        Text(
            stringResource(R.string.sdk_settings_intro),
            color = FgMute, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        SettingsCard {
            SdkRoute.entries.forEachIndexed { index, route ->
                if (index > 0) RowDivider()
                ToggleRow(
                    label = stringResource(route.titleRes()),
                    description = stringResource(
                        R.string.sdk_route_activity,
                        stringResource(route.descriptionRes()),
                        counts.getValue(route).first,
                        counts.getValue(route).second,
                    ),
                    checked = route in switchedOn,
                    onCheckedChange = { viewModel.setSwitchedOn(route, it) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun SdkRoute.titleRes(): Int = when (this) {
    SdkRoute.TAGS -> R.string.sdk_route_tags
    SdkRoute.TRASH -> R.string.sdk_route_trash
    SdkRoute.DOWNLOAD -> R.string.sdk_route_download
    SdkRoute.UPLOAD -> R.string.sdk_route_upload
}

private fun SdkRoute.descriptionRes(): Int = when (this) {
    SdkRoute.TAGS -> R.string.sdk_route_tags_desc
    SdkRoute.TRASH -> R.string.sdk_route_trash_desc
    SdkRoute.DOWNLOAD -> R.string.sdk_route_download_desc
    SdkRoute.UPLOAD -> R.string.sdk_route_upload_desc
}

@HiltViewModel
class SdkSettingsViewModel @Inject constructor(
    private val routing: SdkRouting,
) : ViewModel() {
    val switchedOn: StateFlow<Set<SdkRoute>> =
        routing.switchedOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setSwitchedOn(route: SdkRoute, on: Boolean) {
        viewModelScope.launch { routing.setSwitchedOn(route, on) }
    }
}
