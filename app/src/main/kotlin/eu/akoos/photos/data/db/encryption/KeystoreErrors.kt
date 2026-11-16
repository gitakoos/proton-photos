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

package eu.akoos.photos.data.db.encryption

import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * Tells a Keystore key that is broken for good (invalidated, unrecoverable, a blob an OS update no longer reads)
 * from a Keystore that did not answer this time (its service busy or restarting). The first can only be got back
 * from a recovery copy; the second is tried again.
 */
object KeystoreErrors {

    fun isPermanent(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_DEPTH) {
            when {
                // KeyPermanentlyInvalidatedException is an InvalidKeyException.
                e is InvalidKeyException -> return true
                e is UnrecoverableKeyException -> return true
                e is KeyStoreException -> return true
                e is AEADBadTagException -> return true
                e.javaClass.name == ANDROID_KEYSTORE_EXCEPTION -> return !isTransient(e)
            }
            e = e.cause
            depth++
        }
        return false
    }

    /** [android.security.KeyStoreException.isTransientFailure] (API 33), read without linking the class, so the
     *  rule runs in a unit test. Unknown answers transient: such a key is tried again rather than given up. */
    private fun isTransient(e: Throwable): Boolean =
        runCatching { e.javaClass.getMethod("isTransientFailure").invoke(e) as Boolean }.getOrDefault(true)

    private const val ANDROID_KEYSTORE_EXCEPTION = "android.security.KeyStoreException"
    private const val MAX_DEPTH = 8
}
