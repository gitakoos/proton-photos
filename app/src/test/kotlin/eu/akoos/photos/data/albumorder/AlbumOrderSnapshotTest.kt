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

import eu.akoos.photos.data.mlsync.AlbumOrderDriveNaming
import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import eu.akoos.photos.domain.usecase.PhotoArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class AlbumOrderSnapshotTest {

    private fun copy(device: String, vararg albums: AlbumOrderSnapshotEntry) =
        AlbumOrderSnapshot(deviceId = device, platform = "android", deviceLabel = "Phone", createdAtMillis = 5L, albums = albums.toList())

    private fun state(
        modes: Map<String, AlbumModeState> = emptyMap(),
        orders: Map<String, AlbumArrangementState> = emptyMap(),
    ) = AlbumOrderSyncState(modes.toMutableMap(), orders.toMutableMap())

    // region the file format

    @Test
    fun `the JSON carries the short field names and leaves an absent half out`() {
        val snapshot = copy(
            DEVICE_A,
            AlbumOrderSnapshotEntry("album1", mode = "custom", modeAtMillis = 10L, order = listOf("p2", "p1"), orderAtMillis = 11L),
            AlbumOrderSnapshotEntry("album2", mode = "oldest", modeAtMillis = 12L),
        )

        val text = String(AlbumOrderSnapshotCodec.encode(snapshot), Charsets.UTF_8)

        assertEquals(
            """{"v":1,"d":"$DEVICE_A","p":"android","n":"Phone","c":5,"a":[""" +
                """{"k":"album1","m":"custom","mt":10,"o":["p2","p1"],"ot":11},{"k":"album2","m":"oldest","mt":12}]}""",
            text,
        )
        assertEquals(snapshot, AlbumOrderSnapshotCodec.decode(text.toByteArray()))
    }

    @Test
    fun `a newer format version is not read and its version is told`() {
        val newer = """{"v":2,"d":"x","n":"x","c":1,"a":[]}""".toByteArray()

        assertNull(AlbumOrderSnapshotCodec.decode(newer))
        assertEquals(2, AlbumOrderSnapshotCodec.versionOf(newer))
        assertNull(AlbumOrderSnapshotCodec.versionOf("not json".toByteArray()))
    }

    @Test
    fun `decoding drops a broken half, blank and repeated ids, and an entry with nothing left`() {
        val text = """{"v":1,"d":"x","n":"x","c":1,"extra":true,"a":[
            {"k":"a","m":"sideways","mt":1,"o":["p1","","p1","p2"],"ot":2},
            {"k":"b","m":"newest"},
            {"k":"","m":"newest","mt":3},
            {"k":"c","o":["p1"]}
        ]}""".toByteArray()

        val decoded = AlbumOrderSnapshotCodec.decode(text)!!

        assertEquals(listOf(AlbumOrderSnapshotEntry("a", order = listOf("p1", "p2"), orderAtMillis = 2L)), decoded.albums)
    }

    @Test
    fun `the file name carries the install id`() {
        val name = AlbumOrderDriveNaming.fileName(DEVICE_A)

        assertEquals("album-order-$DEVICE_A.pfpao", name)
        assertEquals(DEVICE_A, AlbumOrderDriveNaming.parse(name))
        assertNull(AlbumOrderDriveNaming.parse("labels-$DEVICE_A.pfplb"))
        assertNull(AlbumOrderDriveNaming.parse("album-order-nope.pfpao"))
        assertThrows(IllegalArgumentException::class.java) { AlbumOrderDriveNaming.fileName("nope") }
    }

    // endregion
    // region the merge

    @Test
    fun `the mode and the order each take the later time on their own`() {
        // Here the mode is newer and the order older than the copy's: the copy brings its order, not its mode.
        val held = state(
            modes = mapOf("album" to AlbumModeState("oldest", 20L)),
            orders = mapOf("album" to AlbumArrangementState(listOf("p1", "p2"), 5L)),
        )
        val incoming = copy(DEVICE_B, AlbumOrderSnapshotEntry("album", "custom", 10L, listOf("p2", "p1"), 15L))

        val changes = AlbumOrderCopyMerge.takeIn(held, incoming, readsAs = DEVICE_A, ownDeviceId = DEVICE_A)

        assertEquals(AlbumModeState("oldest", 20L), held.modes["album"])
        assertEquals(AlbumArrangementState(listOf("p2", "p1"), 15L), held.orders["album"])
        assertTrue(changes.modes.isEmpty())
        assertEquals(setOf("album"), changes.orders.keys)
    }

    @Test
    fun `an equal time goes to the larger device id and only when the value differs`() {
        val entry = AlbumOrderSnapshotEntry("album", "custom", 10L)

        val fromLarger = state(modes = mapOf("album" to AlbumModeState("newest", 10L)))
        AlbumOrderCopyMerge.takeIn(fromLarger, copy(DEVICE_B, entry), readsAs = DEVICE_A, ownDeviceId = DEVICE_A)
        assertEquals("custom", fromLarger.modes["album"]!!.mode)

        val fromSmaller = state(modes = mapOf("album" to AlbumModeState("newest", 10L)))
        AlbumOrderCopyMerge.takeIn(fromSmaller, copy(DEVICE_A, entry), readsAs = DEVICE_B, ownDeviceId = DEVICE_B)
        assertEquals("newest", fromSmaller.modes["album"]!!.mode)

        val same = state(modes = mapOf("album" to AlbumModeState("custom", 10L)))
        val changes = AlbumOrderCopyMerge.takeIn(same, copy(DEVICE_B, entry), readsAs = DEVICE_A, ownDeviceId = DEVICE_A)
        assertTrue(changes.isEmpty)
    }

    @Test
    fun `time zero only fills a gap`() {
        val held = state(modes = mapOf("a" to AlbumModeState("oldest", 0L)))
        val incoming = copy(
            DEVICE_A,
            AlbumOrderSnapshotEntry("a", "newest", 0L),
            AlbumOrderSnapshotEntry("b", "oldest", 0L),
        )

        AlbumOrderCopyMerge.takeIn(held, incoming, readsAs = DEVICE_B, ownDeviceId = DEVICE_B)

        assertEquals("oldest", held.modes["a"]!!.mode)
        assertEquals(AlbumModeState("oldest", 0L), held.modes["b"])
    }

    @Test
    fun `the install's own copy is passed over unless it reads as an earlier install`() {
        val incoming = copy(DEVICE_A, AlbumOrderSnapshotEntry("album", "custom", 10L))

        assertTrue(AlbumOrderCopyMerge.takeIn(state(), incoming, readsAs = DEVICE_A, ownDeviceId = DEVICE_A).isEmpty)

        val restored = state()
        AlbumOrderCopyMerge.takeIn(restored, incoming, readsAs = "earlier-install", ownDeviceId = DEVICE_A)
        assertEquals(AlbumModeState("custom", 10L), restored.modes["album"])
    }

    @Test
    fun `two devices' files merge to the same state in either order and taking one twice changes nothing`() {
        val fromA = copy(
            DEVICE_A,
            AlbumOrderSnapshotEntry("x", "custom", 30L, listOf("1", "2", "3"), 30L),
            AlbumOrderSnapshotEntry("y", "oldest", 5L),
        )
        val fromB = copy(
            DEVICE_B,
            AlbumOrderSnapshotEntry("x", "newest", 40L, listOf("3", "2", "1"), 20L),
            AlbumOrderSnapshotEntry("y", "newest", 6L),
        )

        val one = state()
        AlbumOrderCopyMerge.takeIn(one, fromA, readsAs = DEVICE_C, ownDeviceId = DEVICE_C)
        AlbumOrderCopyMerge.takeIn(one, fromB, readsAs = DEVICE_C, ownDeviceId = DEVICE_C)
        val other = state()
        AlbumOrderCopyMerge.takeIn(other, fromB, readsAs = DEVICE_C, ownDeviceId = DEVICE_C)
        AlbumOrderCopyMerge.takeIn(other, fromA, readsAs = DEVICE_C, ownDeviceId = DEVICE_C)

        assertEquals(one.modes, other.modes)
        assertEquals(one.orders, other.orders)
        assertEquals(AlbumModeState("newest", 40L), one.modes["x"])
        assertEquals(AlbumArrangementState(listOf("1", "2", "3"), 30L), one.orders["x"])
        assertEquals(AlbumModeState("newest", 6L), one.modes["y"])
        assertTrue(AlbumOrderCopyMerge.takeIn(one, fromA, readsAs = DEVICE_C, ownDeviceId = DEVICE_C).isEmpty)
    }

    @Test
    fun `the Drive copy is sorted by album and leaves out an album the listing no longer has`() {
        val held = state(
            modes = mapOf("zeta" to AlbumModeState("oldest", 1L), "gone" to AlbumModeState("custom", 2L)),
            orders = mapOf("alpha" to AlbumArrangementState(listOf("p"), 3L)),
        )

        val snapshot = AlbumOrderCopyMerge.forDrive(held, DEVICE_A, "Phone", 9L, albums = setOf("alpha", "zeta"))

        assertEquals(
            listOf(
                AlbumOrderSnapshotEntry("alpha", order = listOf("p"), orderAtMillis = 3L),
                AlbumOrderSnapshotEntry("zeta", mode = "oldest", modeAtMillis = 1L),
            ),
            snapshot.albums,
        )
        assertEquals(3, AlbumOrderCopyMerge.forDrive(held, DEVICE_A, "Phone", 9L).albums.size)
    }

    // endregion
    // region the round trip

    @Test
    fun `an order arranged on one device shows the same on another after a sealed round trip`() {
        // Device A arranges album x; device B has not seen photo "new" in A's order, which goes to the end there.
        val onA = state(
            modes = mapOf("x" to AlbumModeState("custom", 100L)),
            orders = mapOf("x" to AlbumArrangementState(listOf("p3", "p1", "p2"), 100L)),
        )
        val out = ByteArrayOutputStream()
        AlbumOrderSnapshotSealing.seal(AlbumOrderCopyMerge.forDrive(onA, DEVICE_A, "A", 101L), crypto, aliceSeal(), out)

        val opened = AlbumOrderSnapshotSealing.open(ByteArrayInputStream(out.toByteArray()), crypto, aliceOpen())
        val onB = state(modes = mapOf("x" to AlbumModeState("newest", 50L)))
        val changes = AlbumOrderCopyMerge.takeIn(
            onB,
            (opened as AlbumOrderSnapshotOpened.Snapshot).value,
            readsAs = DEVICE_B,
            ownDeviceId = DEVICE_B,
        )

        assertEquals(setOf("x"), changes.modes.keys)
        assertEquals(setOf("x"), changes.orders.keys)
        assertEquals("custom", onB.modes["x"]!!.mode)
        val photosOnB = listOf("p1" to 1L, "new" to 9L, "p2" to 2L, "p3" to 3L)
        assertEquals(
            listOf("p3", "p1", "p2", "new"),
            PhotoArrangement.ordered(photosOnB, onB.orders["x"]!!.order, { it.first }, { it.second }).map { it.first },
        )
    }

    @Test
    fun `a sealed copy does not open with another account's key and a newer format reads as too new`() {
        val out = ByteArrayOutputStream()
        AlbumOrderSnapshotSealing.seal(copy(DEVICE_A, AlbumOrderSnapshotEntry("x", "oldest", 1L)), crypto, aliceSeal(), out)
        val wrong = EnvelopeOpenKeys(decryptionKeys = listOf("mallory".toByteArray()), verificationKeys = listOf("pub:alice"))

        assertEquals(
            AlbumOrderSnapshotOpened.WrongKey,
            AlbumOrderSnapshotSealing.open(ByteArrayInputStream(out.toByteArray()), crypto, wrong),
        )

        val newer = ByteArrayOutputStream()
        MlSnapshotEnvelope.seal(ByteArrayInputStream("""{"v":9,"d":"x","n":"x","c":1}""".toByteArray()), newer, crypto, aliceSeal())
        assertEquals(
            AlbumOrderSnapshotOpened.TooNew,
            AlbumOrderSnapshotSealing.open(ByteArrayInputStream(newer.toByteArray()), crypto, aliceOpen()),
        )
    }

    // endregion

    private val crypto = FakeCrypto()

    private fun aliceSeal() = EnvelopeSealKeys(recipientPublicKey = "pub:alice", signingKey = "alice".toByteArray())

    private fun aliceOpen() = EnvelopeOpenKeys(decryptionKeys = listOf("alice".toByteArray()), verificationKeys = listOf("pub:alice"))

    /** The reversible OpenPGP stand-in the other snapshot tests use. */
    private class FakeCrypto : EnvelopeCrypto {
        private val random = Random(17)

        override fun generateSessionKey(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
            framed(recipientPublicKey.removePrefix("pub:").toByteArray(Charsets.UTF_8), sessionKey)

        override fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray? {
            val (recipient, sessionKey) = unframed(wrappedKey) ?: return null
            return if (recipient.contentEquals(decryptionKey)) sessionKey else null
        }

        override fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray =
            keyCheck(sessionKey) + xor(plain, sessionKey)

        override fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray? {
            if (cipher.size < CHECK_BYTES || !cipher.copyOf(CHECK_BYTES).contentEquals(keyCheck(sessionKey))) return null
            return xor(cipher.copyOfRange(CHECK_BYTES, cipher.size), sessionKey)
        }

        override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray = framed(signingKey, sha256(signingKey + data))

        override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
            val (signer, tag) = unframed(signature) ?: return false
            return "pub:${String(signer, Charsets.UTF_8)}" in verificationKeys && tag.contentEquals(sha256(signer + data))
        }

        private fun keyCheck(sessionKey: ByteArray): ByteArray = sha256(sessionKey).copyOf(CHECK_BYTES)

        private fun xor(data: ByteArray, key: ByteArray): ByteArray =
            ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

        private fun framed(field: ByteArray, rest: ByteArray): ByteArray = byteArrayOf(field.size.toByte()) + field + rest

        private fun unframed(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
            if (bytes.isEmpty()) return null
            val length = bytes[0].toInt() and 0xFF
            if (1 + length > bytes.size) return null
            return bytes.copyOfRange(1, 1 + length) to bytes.copyOfRange(1 + length, bytes.size)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private companion object {
            const val CHECK_BYTES = 4
        }
    }

    private companion object {
        const val DEVICE_A = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val DEVICE_B = "1b5f3c2e-2222-4a4a-9c9c-123456789abc"
        const val DEVICE_C = "2b5f3c2e-3333-4a4a-9c9c-123456789abc"
    }
}
