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

package eu.akoos.photos.data.semantic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.presentation.search.shouldFetchSearchTextModel
import eu.akoos.photos.service.ModelDownloadService
import eu.akoos.photos.util.ModelDownloadKind
import eu.akoos.photos.util.ModelDownloadPhase
import eu.akoos.photos.util.ModelDownloadState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets the search text model onto the phone when content search is available only through embeddings that
 * another device brought: the query is embedded here, but no photo is, so the image model is never needed.
 * The fetch runs on the same foreground download service and shows the same progress notification as the
 * switch's own download, and it never uses mobile data.
 */
@Singleton
class SemanticTextModelProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadState: ModelDownloadState,
) {
    private val modelManager by lazy { SemanticModelManager(context) }

    /** Starts the text model fetch when [shouldFetchSearchTextModel] says so. Cheap to call repeatedly. */
    suspend fun requestIfNeeded(searchAvailable: Boolean, unmetered: Boolean) {
        if (!searchAvailable || !unmetered) return
        val running = downloadState.status.value[ModelDownloadKind.SEMANTIC_TEXT]?.phase == ModelDownloadPhase.DOWNLOADING
        val present = modelManager.textModelFile() != null
        if (shouldFetchSearchTextModel(searchAvailable, present, running, unmetered)) {
            ModelDownloadService.start(context, ModelDownloadKind.SEMANTIC_TEXT)
        }
    }
}
