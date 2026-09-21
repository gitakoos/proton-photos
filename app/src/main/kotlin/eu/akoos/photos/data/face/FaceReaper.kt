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

package eu.akoos.photos.data.face

/**
 * Pure decision behind [FaceReapWorker]: which stored face photoKeys to reap because their photo
 * has left the library for good. This deletes biometric data, so the rule is deliberately
 * conservative on two axes:
 *
 *  - A key is reaped ONLY when its keyspace is fully TRUSTWORTHY this pass. A failed or empty scan
 *    must never be read as "the photos are gone"; it means "we do not know", so nothing is reaped.
 *  - A key is KEPT while the photo is still live OR still restorable from a trash. A trashed photo
 *    therefore keeps its faces (and their names) until it is permanently deleted.
 *
 * Key shape decides the keyspace: a device photoKey is a MediaStore `content://` URI; a cloud
 * photoKey is an opaque Drive linkId with no URI scheme. Anything else (a `file://` vault path, an
 * unknown scheme) cannot be authoritatively confirmed gone here, so it is always kept. The two
 * keyspaces carry independent trust: a failed cloud fetch never reaps a device face, and a failed
 * device scan never reaps a cloud face.
 */
object FaceReaper {

    fun facesToReap(
        faceKeys: Collection<String>,
        liveCloudLinkIds: Set<String>,
        cloudTrashLinkIds: Set<String>,
        cloudTrustworthy: Boolean,
        liveDeviceUris: Set<String>,
        deviceTrashUris: Set<String>,
        deviceTrustworthy: Boolean,
    ): Set<String> {
        val reap = HashSet<String>()
        for (key in faceKeys) {
            when {
                key.startsWith("content://") ->
                    if (deviceTrustworthy && key !in liveDeviceUris && key !in deviceTrashUris) {
                        reap += key
                    }
                key.contains("://") ->
                    Unit // file:// vault or any other scheme: not confirmable here, keep
                else ->
                    if (cloudTrustworthy && key !in liveCloudLinkIds && key !in cloudTrashLinkIds) {
                        reap += key
                    }
            }
        }
        return reap
    }
}
