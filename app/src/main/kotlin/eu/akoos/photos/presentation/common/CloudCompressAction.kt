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

package eu.akoos.photos.presentation.common

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CloudCompressQueue
import eu.akoos.photos.domain.usecase.compressibleLinkIds
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CloudCompressAccess {
    fun cloudCompressQueue(): CloudCompressQueue
}

/**
 * "Compress on Drive" for the photos and videos among [items] that live only on Drive, null when there are
 * none (or nobody is signed in). The tap asks first, saying what happens to each original, then queues them
 * for the background compression and says it started. Works from a selection of any size: the queue is
 * written in slices off the screen's thread.
 */
@Composable
fun rememberCloudCompressAction(items: Collection<GalleryItem>, isSignedIn: Boolean): SelectionAction? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<List<GalleryItem>?>(null) }
    val list = remember(items) { items.toList() }
    val count = remember(list) { compressibleLinkIds(list).size }

    asking?.let { pending ->
        val n = remember(pending) { compressibleLinkIds(pending).size }
        val leftOut = pending.size - n
        val body = pluralStringResource(R.plurals.cloud_compress_confirm_body, n, n) +
            (if (leftOut > 0) "\n\n" + pluralStringResource(R.plurals.cloud_compress_confirm_left_out, leftOut, leftOut) else "") +
            "\n\n" + stringResource(R.string.cloud_compress_confirm_stop)
        ConfirmSheet(
            title = stringResource(R.string.cloud_compress_confirm_title),
            message = body,
            confirmLabel = stringResource(R.string.cloud_compress_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                asking = null
                val queue = EntryPointAccessors.fromApplication(context.applicationContext, CloudCompressAccess::class.java)
                    .cloudCompressQueue()
                if (queue.enqueue(pending) > 0) {
                    scope.launch {
                        val waits = queue.waitsForWifi()
                        Toast.makeText(
                            context,
                            context.getString(if (waits) R.string.cloud_compress_waiting_wifi else R.string.cloud_compress_started),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onDismiss = { asking = null },
        )
    }

    if (!isSignedIn || count == 0) return null
    return SelectionAction(
        icon = Icons.Default.Compress,
        label = stringResource(R.string.cloud_compress_title),
        onClick = { asking = list },
    )
}
