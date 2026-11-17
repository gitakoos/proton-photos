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

package eu.akoos.photos.data.repository.drive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypted node keys of the signed-in account's own albums, by album link id, for the readers that open a
 * photo's XAttr under its album. The keys belong to one account: a lookup or store for another account
 * first zeroes and drops every key held, and [clear] does the same on sign-out, so no album key outlives
 * the session that decrypted it in a process that keeps running.
 */
@Singleton
class OwnerAlbumKeyCache @Inject constructor() {
    private val keys = HashMap<String, ByteArray>()
    private var owner: String? = null

    /** The key of [albumLinkId] decrypted for [userId], or null when none is held for that account. */
    @Synchronized
    fun get(userId: String, albumLinkId: String): ByteArray? {
        switchTo(userId)
        return keys[albumLinkId]
    }

    /** Holds [key], decrypted for [userId], as [albumLinkId]'s. */
    @Synchronized
    fun put(userId: String, albumLinkId: String, key: ByteArray) {
        switchTo(userId)
        keys.put(albumLinkId, key)?.takeIf { it !== key }?.fill(0)
    }

    /** Zeroes and drops every key held. */
    @Synchronized
    fun clear() {
        keys.values.forEach { it.fill(0) }
        keys.clear()
        owner = null
    }

    private fun switchTo(userId: String) {
        if (owner == userId) return
        clear()
        owner = userId
    }
}
