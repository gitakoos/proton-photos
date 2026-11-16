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

package eu.akoos.photos.data.hidden

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A copy that has reached the storage, not only the page cache, before the caller goes on to delete the source.
 * A reveal removes the vault file right after its copy reads back whole; a power cut in between must not leave
 * the photo nowhere.
 */
internal object DurableCopy {
    /** Copies [input] into [out] and returns, with the byte count, once [sync] has forced every byte to the storage. */
    fun copy(input: InputStream, out: FileOutputStream, sync: (FileDescriptor) -> Unit = { it.sync() }): Long {
        val copied = input.copyTo(out)
        out.flush()
        sync(out.fd)
        return copied
    }

    /** Forces [dir]'s entries (a file just created in it) to the storage where the platform allows it. */
    fun syncDir(dir: java.io.File) = eu.akoos.photos.data.localcrypto.syncDirectory(dir)
}
