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

package eu.akoos.photos.presentation.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.ocr.OcrModelAssets
import eu.akoos.photos.data.semantic.SemanticModelAssets
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.onboarding.components.ToggleCard
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.formatBytes

/**
 * The on-device machine-learning features, each with its own switch and the size of the model it
 * fetches. Nothing downloads on this step: the choices are applied when the wizard finishes.
 */
@Composable
internal fun MlIntroStep(
    face: Boolean,
    semantic: Boolean,
    ocr: Boolean,
    onFaceChange: (Boolean) -> Unit,
    onSemanticChange: (Boolean) -> Unit,
    onOcrChange: (Boolean) -> Unit,
) {
    val colors = AppColors.current
    Column {
        StepHeader(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.onboarding_ml_title),
            subtitle = stringResource(R.string.onboarding_ml_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        ToggleCard(
            label = stringResource(R.string.settings_ai_face_toggle),
            description = featureDescription(R.string.settings_ai_face_desc, FaceModelAssets.TOTAL_DOWNLOAD_BYTES),
            checked = face,
            onChange = onFaceChange,
        )
        Spacer(Modifier.height(10.dp))
        ToggleCard(
            label = stringResource(R.string.settings_ai_semantic),
            description = featureDescription(R.string.settings_ai_semantic_desc, SemanticModelAssets.TOTAL_DOWNLOAD_BYTES),
            checked = semantic,
            onChange = onSemanticChange,
        )
        Spacer(Modifier.height(10.dp))
        ToggleCard(
            label = stringResource(R.string.settings_ai_ocr),
            description = featureDescription(R.string.settings_ai_ocr_desc, OcrModelAssets.TOTAL_DOWNLOAD_BYTES),
            checked = ocr,
            onChange = onOcrChange,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_ml_note),
            color = colors.fgMute,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun featureDescription(descriptionRes: Int, modelBytes: Long): String =
    stringResource(descriptionRes) + "\n" + stringResource(R.string.onboarding_ml_size, formatBytes(modelBytes))
