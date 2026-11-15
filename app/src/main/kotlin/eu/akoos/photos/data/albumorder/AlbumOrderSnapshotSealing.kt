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

package eu.akoos.photos.data.albumorder

import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeOpenResult
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What opening an album-order file gave. */
internal sealed interface AlbumOrderSnapshotOpened {
    data class Snapshot(val value: AlbumOrderSnapshot) : AlbumOrderSnapshotOpened

    /** No key of the account opens it. */
    data object WrongKey : AlbumOrderSnapshotOpened

    /** Written by a newer version of the app. */
    data object TooNew : AlbumOrderSnapshotOpened

    /** Cut short, altered, or not an album-order list. */
    data object Damaged : AlbumOrderSnapshotOpened
}

/**
 * The album orders in the same encrypted, signed envelope the labels use, sealed to the account's address key on
 * top of Drive's own encryption. The plaintext is held in memory.
 */
internal object AlbumOrderSnapshotSealing {

    /** Seals [snapshot] into [output], which is written to but never closed. */
    fun seal(snapshot: AlbumOrderSnapshot, crypto: EnvelopeCrypto, keys: EnvelopeSealKeys, output: OutputStream) {
        MlSnapshotEnvelope.seal(ByteArrayInputStream(AlbumOrderSnapshotCodec.encode(snapshot)), output, crypto, keys)
    }

    /** Opens the envelope in [input] and reads the album orders in it. */
    fun open(input: InputStream, crypto: EnvelopeCrypto, keys: EnvelopeOpenKeys): AlbumOrderSnapshotOpened {
        val plain = ByteArrayOutputStream()
        return when (MlSnapshotEnvelope.open(input, plain, crypto, keys)) {
            is EnvelopeOpenResult.Opened -> read(plain.toByteArray())
            EnvelopeOpenResult.WrongKey -> AlbumOrderSnapshotOpened.WrongKey
            is EnvelopeOpenResult.UnsupportedVersion -> AlbumOrderSnapshotOpened.TooNew
            EnvelopeOpenResult.NotAnEnvelope, EnvelopeOpenResult.Corrupt -> AlbumOrderSnapshotOpened.Damaged
        }
    }

    private fun read(bytes: ByteArray): AlbumOrderSnapshotOpened {
        val version = AlbumOrderSnapshotCodec.versionOf(bytes) ?: return AlbumOrderSnapshotOpened.Damaged
        if (version > AlbumOrderSnapshot.VERSION) return AlbumOrderSnapshotOpened.TooNew
        val snapshot = AlbumOrderSnapshotCodec.decode(bytes) ?: return AlbumOrderSnapshotOpened.Damaged
        return AlbumOrderSnapshotOpened.Snapshot(snapshot)
    }
}
