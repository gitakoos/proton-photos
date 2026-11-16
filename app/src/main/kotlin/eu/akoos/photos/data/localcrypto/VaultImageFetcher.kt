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

package eu.akoos.photos.data.localcrypto

import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.ContentMetadata
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import eu.akoos.photos.util.mimeFromPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File

/**
 * Loads an encrypted vault file for Coil from a decrypting stream, so every grid, reel, viewer and drawer that
 * hands Coil a vault `file://` uri keeps working unchanged. The source carries the decrypting provider's uri as
 * its content metadata, which is what a video frame grab and the Ultra HDR decoder reopen it by. A plaintext
 * vault file is left to Coil's own file fetcher.
 */
class VaultImageFetcher(private val file: File, private val options: Options) : Fetcher {

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val reader = VaultFiles.openReader(options.context, file)
        SourceResult(
            source = ImageSource(
                source = reader.inputStream().source().buffer(),
                context = options.context,
                metadata = ContentMetadata(VaultReadProvider.uriFor(file.name)),
            ),
            mimeType = mimeFromPath(file.name),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!VaultFiles.isInVault(options.context, data)) return null
            if (!VaultFiles.isEncrypted(data)) return null
            return VaultImageFetcher(data, options)
        }
    }
}
