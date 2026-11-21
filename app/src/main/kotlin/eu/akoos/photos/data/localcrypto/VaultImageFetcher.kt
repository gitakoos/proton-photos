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
 * Loads an encrypted file of one of the encrypted folders ([LocalFiles.Area]: the vault, the thumbnail and
 * full-size caches, the offline copies) for Coil from a decrypting stream, so every grid, reel, viewer and drawer
 * that hands Coil such a `file://` uri keeps working unchanged. The source carries the decrypting provider's uri
 * as its content metadata, which is what a video frame grab and the Ultra HDR decoder reopen it by. A plaintext
 * file is left to Coil's own file fetcher. A cache file that no longer opens is deleted, so it is fetched again.
 */
class VaultImageFetcher(private val file: File, private val area: LocalFiles.Area, private val options: Options) : Fetcher {

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val reader = try {
            VaultFiles.openReader(options.context, file)
        } catch (e: Exception) {
            if (area.regenerable) CacheFiles.dropIfUnreadable(file, e)
            throw e
        }
        SourceResult(
            source = ImageSource(
                source = reader.inputStream().source().buffer(),
                context = options.context,
                metadata = ContentMetadata(VaultReadProvider.uriFor(area, file.name)),
            ),
            mimeType = mimeFromPath(file.name),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val area = LocalFiles.areaOf(options.context, data) ?: return null
            if (!LocalFiles.isEncrypted(data)) return null
            return VaultImageFetcher(data, area, options)
        }
    }
}
