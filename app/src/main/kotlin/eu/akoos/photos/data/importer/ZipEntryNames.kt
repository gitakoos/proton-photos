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

package eu.akoos.photos.data.importer

import androidx.annotation.RequiresApi
import dalvik.system.ZipPathValidator
import java.io.File
import java.util.zip.ZipException

/**
 * The entry names an import walk accepts. From Android 14 the zip stream refuses, for the whole process, any
 * name that starts with `/` or steps up with `..`, and a Dropbox download carries a `/` entry of its own, so
 * such a zip would read as damaged at its first entry. An import never writes an entry under its own name
 * (staging uses fresh temp files), so a walk marked [lenient] reads every name and skips the `..` ones
 * itself; every other zip read in the process keeps the platform's rule.
 */
internal object ZipEntryNames {

    private val lenientThread = ThreadLocal<Boolean>()

    fun <T> lenient(block: () -> T): T {
        val outer = lenientThread.get()
        lenientThread.set(true)
        try {
            return block()
        } finally {
            if (outer == null) lenientThread.remove() else lenientThread.set(outer)
        }
    }

    /** True for a name with a `..` step, which an import skips rather than reads. */
    fun climbsOut(name: String): Boolean = name.replace('\\', '/').split('/').any { it == ".." }

    /** The platform's rule: a name that starts with `/` or has a `..` step is refused. */
    internal fun refusedByPlatformRule(name: String): Boolean {
        if (name.startsWith("/")) return true
        if (!name.contains("..")) return false
        var file: File? = File(name)
        while (file != null) {
            if (file.name == "..") return true
            file = file.parentFile
        }
        return false
    }

    internal fun refuses(name: String, lenient: Boolean = lenientThread.get() == true): Boolean =
        !lenient && refusedByPlatformRule(name)

    @RequiresApi(34)
    fun install() {
        ZipPathValidator.setCallback(
            object : ZipPathValidator.Callback {
                override fun onZipEntryAccess(path: String) {
                    if (refuses(path)) throw ZipException("Invalid zip entry path")
                }
            },
        )
    }
}
