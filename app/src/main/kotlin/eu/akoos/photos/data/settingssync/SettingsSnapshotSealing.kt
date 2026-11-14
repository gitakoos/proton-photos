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

package eu.akoos.photos.data.settingssync

import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeOpenResult
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What opening a settings file gave. */
internal sealed interface SettingsSnapshotOpened {
    data class Snapshot(val value: SettingsSnapshot) : SettingsSnapshotOpened

    /** No key of the account opens it. */
    data object WrongKey : SettingsSnapshotOpened

    /** Written by a newer version of the app. */
    data object TooNew : SettingsSnapshotOpened

    /** Cut short, altered, or not a settings copy. */
    data object Damaged : SettingsSnapshotOpened
}

/**
 * A settings copy in the same encrypted, signed envelope the labels and the hidden list use, sealed to the account's
 * address key on top of Drive's own encryption. The copy is small, so the plaintext is held in memory.
 */
internal object SettingsSnapshotSealing {

    /** Seals [snapshot] into [output], which is written to but never closed. */
    fun seal(snapshot: SettingsSnapshot, crypto: EnvelopeCrypto, keys: EnvelopeSealKeys, output: OutputStream) {
        MlSnapshotEnvelope.seal(ByteArrayInputStream(SettingsSnapshotCodec.encode(snapshot)), output, crypto, keys)
    }

    /** Opens the envelope in [input] and reads the settings copy in it. */
    fun open(input: InputStream, crypto: EnvelopeCrypto, keys: EnvelopeOpenKeys): SettingsSnapshotOpened {
        val plain = ByteArrayOutputStream()
        return when (MlSnapshotEnvelope.open(input, plain, crypto, keys)) {
            is EnvelopeOpenResult.Opened -> read(plain.toByteArray())
            EnvelopeOpenResult.WrongKey -> SettingsSnapshotOpened.WrongKey
            is EnvelopeOpenResult.UnsupportedVersion -> SettingsSnapshotOpened.TooNew
            EnvelopeOpenResult.NotAnEnvelope, EnvelopeOpenResult.Corrupt -> SettingsSnapshotOpened.Damaged
        }
    }

    private fun read(bytes: ByteArray): SettingsSnapshotOpened {
        val version = SettingsSnapshotCodec.versionOf(bytes) ?: return SettingsSnapshotOpened.Damaged
        if (version > SettingsSnapshot.VERSION) return SettingsSnapshotOpened.TooNew
        val snapshot = SettingsSnapshotCodec.decode(bytes) ?: return SettingsSnapshotOpened.Damaged
        return SettingsSnapshotOpened.Snapshot(snapshot)
    }
}
