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

package eu.akoos.photos.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The three on-device model sets that download over the network on opt-in. */
enum class ModelDownloadKind { FACE, OCR, SEMANTIC }

/** Where one kind's download stands right now. */
enum class ModelDownloadPhase { DOWNLOADING, DONE, FAILED }

/**
 * A single kind's live download: its [phase], and for a determinate download how many of
 * [totalBytes] have landed. OCR reports no byte total (a short two-file fetch), so it runs with
 * [totalBytes] 0 and the UI shows an open-ended spinner rather than a bar.
 */
data class ModelDownloadInfo(
    val phase: ModelDownloadPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * The shared, process-wide state of the on-device model downloads, written by [ModelDownloadService]
 * (which owns the actual fetch on a foreground service) and read by the settings screen so its per-
 * feature rows show live progress. A @Singleton so the one service instance and the settings view-model
 * see the same map; the service outlives any screen, so a download the user walked away from keeps
 * reporting here when they come back.
 *
 * Keyed by [ModelDownloadKind] so the three feature rows are independent: enabling face while a semantic
 * download runs does not disturb either row. Numbers only, nothing identifying.
 */
@Singleton
class ModelDownloadState @Inject constructor() {

    private val _status = MutableStateFlow<Map<ModelDownloadKind, ModelDownloadInfo>>(emptyMap())
    val status: StateFlow<Map<ModelDownloadKind, ModelDownloadInfo>> = _status.asStateFlow()

    /** Mark [kind] downloading, with the running byte total against [totalBytes] (0 for an indeterminate
     *  fetch). Called from the service's progress callback. */
    fun running(kind: ModelDownloadKind, downloadedBytes: Long, totalBytes: Long) {
        _status.update { it + (kind to ModelDownloadInfo(ModelDownloadPhase.DOWNLOADING, downloadedBytes, totalBytes)) }
    }

    fun done(kind: ModelDownloadKind) {
        _status.update { it + (kind to ModelDownloadInfo(ModelDownloadPhase.DONE)) }
    }

    fun failed(kind: ModelDownloadKind) {
        _status.update { it + (kind to ModelDownloadInfo(ModelDownloadPhase.FAILED)) }
    }

    /** Drop every still-downloading entry, called when the download service is torn down (a Stop, the
     *  daily foreground budget, or a process teardown): those downloads are not going to finish, so their
     *  rows must read as not-downloading rather than stay wedged at a frozen bar. A finished or failed
     *  entry is left as it is, so its row keeps showing the outcome. */
    fun clearActive() {
        _status.update { current -> current.filterValues { it.phase != ModelDownloadPhase.DOWNLOADING } }
    }
}
