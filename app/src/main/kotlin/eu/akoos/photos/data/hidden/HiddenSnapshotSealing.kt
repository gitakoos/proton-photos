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

import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeOpenResult
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What opening a hidden-list file gave. */
internal sealed interface HiddenSnapshotOpened {
    data class Snapshot(val value: HiddenSnapshot) : HiddenSnapshotOpened

    /** No key of the account opens it. */
    data object WrongKey : HiddenSnapshotOpened

    /** Written by a newer version of the app. */
    data object TooNew : HiddenSnapshotOpened

    /** Cut short, altered, or not a hidden list. */
    data object Damaged : HiddenSnapshotOpened
}

/**
 * A hidden list in the same encrypted, signed envelope the labels use, sealed to the account's address key on top
 * of Drive's own encryption. The list is small, so the plaintext is held in memory.
 */
internal object HiddenSnapshotSealing {

    /** Seals [snapshot] into [output], which is written to but never closed. */
    fun seal(snapshot: HiddenSnapshot, crypto: EnvelopeCrypto, keys: EnvelopeSealKeys, output: OutputStream) {
        MlSnapshotEnvelope.seal(ByteArrayInputStream(HiddenSnapshotCodec.encode(snapshot)), output, crypto, keys)
    }

    /** Opens the envelope in [input] and reads the hidden list in it. */
    fun open(input: InputStream, crypto: EnvelopeCrypto, keys: EnvelopeOpenKeys): HiddenSnapshotOpened {
        val plain = ByteArrayOutputStream()
        return when (MlSnapshotEnvelope.open(input, plain, crypto, keys)) {
            is EnvelopeOpenResult.Opened -> read(plain.toByteArray())
            EnvelopeOpenResult.WrongKey -> HiddenSnapshotOpened.WrongKey
            is EnvelopeOpenResult.UnsupportedVersion -> HiddenSnapshotOpened.TooNew
            EnvelopeOpenResult.NotAnEnvelope, EnvelopeOpenResult.Corrupt -> HiddenSnapshotOpened.Damaged
        }
    }

    private fun read(bytes: ByteArray): HiddenSnapshotOpened {
        val version = HiddenSnapshotCodec.versionOf(bytes) ?: return HiddenSnapshotOpened.Damaged
        if (version > HiddenSnapshot.VERSION) return HiddenSnapshotOpened.TooNew
        val snapshot = HiddenSnapshotCodec.decode(bytes) ?: return HiddenSnapshotOpened.Damaged
        return HiddenSnapshotOpened.Snapshot(snapshot)
    }
}
