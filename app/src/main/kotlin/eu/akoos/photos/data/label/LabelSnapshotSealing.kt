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

package eu.akoos.photos.data.label

import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeOpenResult
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What opening a label snapshot file gave. */
internal sealed interface LabelSnapshotOpened {
    data class Snapshot(val value: LabelSnapshot) : LabelSnapshotOpened

    /** No key of the account opens it: another account's file, or a key this device lacks. */
    data object WrongKey : LabelSnapshotOpened

    /** Written by a newer version of the app. */
    data object TooNew : LabelSnapshotOpened

    /** Cut short, altered, or not a label snapshot. */
    data object Damaged : LabelSnapshotOpened
}

/**
 * A label snapshot in the same encrypted, signed envelope the machine-learning snapshots use, sealed to the
 * account's address key on top of Drive's own encryption. The labels are small, so the plaintext is held in
 * memory rather than staged in a scratch file.
 */
internal object LabelSnapshotSealing {

    /** Seals [snapshot] into [output], which is written to but never closed. */
    fun seal(snapshot: LabelSnapshot, crypto: EnvelopeCrypto, keys: EnvelopeSealKeys, output: OutputStream) {
        MlSnapshotEnvelope.seal(ByteArrayInputStream(LabelSnapshotCodec.encode(snapshot)), output, crypto, keys)
    }

    /** Opens the envelope in [input] and reads the snapshot in it. */
    fun open(input: InputStream, crypto: EnvelopeCrypto, keys: EnvelopeOpenKeys): LabelSnapshotOpened {
        val plain = ByteArrayOutputStream()
        return when (MlSnapshotEnvelope.open(input, plain, crypto, keys)) {
            is EnvelopeOpenResult.Opened -> read(plain.toByteArray())
            EnvelopeOpenResult.WrongKey -> LabelSnapshotOpened.WrongKey
            is EnvelopeOpenResult.UnsupportedVersion -> LabelSnapshotOpened.TooNew
            EnvelopeOpenResult.NotAnEnvelope, EnvelopeOpenResult.Corrupt -> LabelSnapshotOpened.Damaged
        }
    }

    private fun read(bytes: ByteArray): LabelSnapshotOpened {
        val version = LabelSnapshotCodec.versionOf(bytes) ?: return LabelSnapshotOpened.Damaged
        if (version > LabelSnapshot.VERSION) return LabelSnapshotOpened.TooNew
        val snapshot = LabelSnapshotCodec.decode(bytes) ?: return LabelSnapshotOpened.Damaged
        return LabelSnapshotOpened.Snapshot(snapshot)
    }
}
