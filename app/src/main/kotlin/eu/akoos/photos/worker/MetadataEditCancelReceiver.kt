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


package eu.akoos.photos.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.domain.usecase.CloudMetadataSaveController

/**
 * Backing receiver for the metadata-edit notification's "Cancel" action. It goes through
 * [CloudMetadataSaveController.cancel], which stops the drain AND drops every edit whose corrected copy
 * was not uploaded yet, so those are never picked up by a later batch; an edit whose copy is already in
 * Drive is finished instead. Cancelling the worker alone would leave every row in place for the next
 * run to resume.
 *
 * Reaches the @Singleton controller via a Hilt EntryPoint since a BroadcastReceiver is not itself injected.
 */
class MetadataEditCancelReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MetadataEditCancelEntryPoint {
        fun cloudMetadataSaveController(): CloudMetadataSaveController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_METADATA_EDIT) return
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                MetadataEditCancelEntryPoint::class.java,
            ).cloudMetadataSaveController().cancel()
        }.onFailure { Log.w(TAG, "Metadata edit cancel failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "metadata_edit_cancel"
        const val ACTION_CANCEL_METADATA_EDIT = "eu.akoos.photos.action.CANCEL_METADATA_EDIT"
    }
}
