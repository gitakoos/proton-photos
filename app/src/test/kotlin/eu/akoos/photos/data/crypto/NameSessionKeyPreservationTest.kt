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

package eu.akoos.photos.data.crypto

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.EncryptedPacket
import me.proton.core.crypto.common.pgp.PGPCrypto
import me.proton.core.crypto.common.pgp.PGPHeader
import me.proton.core.crypto.common.pgp.PacketType
import me.proton.core.crypto.common.pgp.SessionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Locks the rule that renaming a link must reuse the Name's existing session key (#88). A share
 * stores that session key re-encrypted under the share key as `NameKeyPacket` and no endpoint ever
 * refreshes it, so a rename that mints a fresh one silently blinds every recipient.
 *
 * Real PGP never runs here: [PGPCrypto] is mocked and every key/packet is a byte-array sentinel, so
 * the assertions are about which session-key INSTANCE reaches the data packet and the new PKESK,
 * and about the primitives that must never be reached.
 */
class NameSessionKeyPreservationTest {

    private lateinit var pgp: PGPCrypto
    private lateinit var helper: DriveCryptoHelper

    private val oldName = "-----BEGIN PGP MESSAGE-----old-----END PGP MESSAGE-----"
    private val pkeskA = byteArrayOf(0xC1.toByte(), 1)
    private val pkeskB = byteArrayOf(0xC1.toByte(), 2)
    private val seipd = byteArrayOf(0xD2.toByte(), 9)
    private val parentKey = byteArrayOf(1, 1, 1)
    private val signerKey = byteArrayOf(2, 2, 2)
    private val parentPublicKey = "-----BEGIN PGP PUBLIC KEY BLOCK-----parent"
    private val dataPacket = byteArrayOf(70, 71)
    private val keyPacket = byteArrayOf(80, 81)

    /** Identity matters, so this exact instance is what the assertions chase through the call. */
    private val storedSessionKey = SessionKey(byteArrayOf(5, 5, 5, 5))

    @Before
    fun setUp() {
        pgp = mockk()
        val cryptoContext = mockk<CryptoContext>()
        every { cryptoContext.pgpCrypto } returns pgp
        helper = DriveCryptoHelper(cryptoContext, mockk(), mockk())

        every { pgp.getEncryptedPackets(oldName) } returns listOf(
            EncryptedPacket(pkeskA, PacketType.Key),
            EncryptedPacket(seipd, PacketType.Data),
        )
        every { pgp.decryptSessionKey(pkeskA, parentKey) } returns storedSessionKey
        every { pgp.encryptAndSignData(any(), any<SessionKey>(), any(), any()) } returns dataPacket
        every { pgp.encryptSessionKey(any(), any()) } returns keyPacket
        every { pgp.getArmored(any(), PGPHeader.Message) } returns "ARMORED"
    }

    private fun rename(newName: String = "Holiday 2026") = helper.renameNamePreservingSessionKey(
        oldNameArmored = oldName,
        oldDecryptKeyBytes = parentKey,
        newPlaintextName = newName,
        parentPublicKeyArmored = parentPublicKey,
        signerKeyBytes = signerKey,
    )

    @Test
    fun `rename encrypts the new name under the stored session key instance`() {
        val sessionKeySlot = slot<SessionKey>()
        val plaintextSlot = slot<ByteArray>()
        every {
            pgp.encryptAndSignData(capture(plaintextSlot), capture(sessionKeySlot), any(), any())
        } returns dataPacket

        rename("Holiday 2026")

        assertSame(storedSessionKey, sessionKeySlot.captured)
        assertArrayEquals("Holiday 2026".toByteArray(Charsets.UTF_8), plaintextSlot.captured)
    }

    @Test
    fun `rename never generates a new session key`() {
        rename()

        // The whole defect in one assertion: a fresh session key orphans every stored NameKeyPacket.
        verify(exactly = 0) { pgp.generateNewSessionKey() }
    }

    @Test
    fun `rename writes the new PKESK for the same session key instance`() {
        val sessionKeySlot = slot<SessionKey>()
        val publicKeySlot = slot<String>()
        every { pgp.encryptSessionKey(capture(sessionKeySlot), capture(publicKeySlot)) } returns keyPacket

        rename()

        assertSame(storedSessionKey, sessionKeySlot.captured)
        assertSame(parentPublicKey, publicKeySlot.captured)
    }

    @Test
    fun `rename joins the new key packet ahead of the new data packet`() {
        val combinedSlot = slot<ByteArray>()
        every { pgp.getArmored(capture(combinedSlot), PGPHeader.Message) } returns "ARMORED"

        rename()

        assertArrayEquals(keyPacket + dataPacket, combinedSlot.captured)
    }

    @Test
    fun `rename walks past a PKESK the key cannot open`() {
        every { pgp.getEncryptedPackets(oldName) } returns listOf(
            EncryptedPacket(pkeskA, PacketType.Key),
            EncryptedPacket(pkeskB, PacketType.Key),
            EncryptedPacket(seipd, PacketType.Data),
        )
        every { pgp.decryptSessionKey(pkeskA, parentKey) } throws RuntimeException("not for this key")
        every { pgp.decryptSessionKey(pkeskB, parentKey) } returns storedSessionKey
        val sessionKeySlot = slot<SessionKey>()
        every {
            pgp.encryptAndSignData(any(), capture(sessionKeySlot), any(), any())
        } returns dataPacket

        rename()

        assertSame(storedSessionKey, sessionKeySlot.captured)
    }

    @Test
    fun `rename throws when no PKESK opens instead of falling back to a fresh session key`() {
        every { pgp.decryptSessionKey(pkeskA, parentKey) } throws RuntimeException("not for this key")

        assertThrows(IllegalStateException::class.java) { rename() }

        verify(exactly = 0) { pgp.generateNewSessionKey() }
        verify(exactly = 0) { pgp.encryptAndSignText(any(), any(), any(), any()) }
    }

    @Test
    fun `rename throws when the stored name carries no PKESK at all`() {
        every { pgp.getEncryptedPackets(oldName) } returns listOf(EncryptedPacket(seipd, PacketType.Data))

        assertThrows(IllegalArgumentException::class.java) { rename() }

        verify(exactly = 0) { pgp.generateNewSessionKey() }
    }

    @Test
    fun `re-targeting a name to another parent keeps the same session key`() {
        // changeNameRecipient shares the core, so the copy pipeline is covered by the same rule.
        val sessionKeySlot = slot<SessionKey>()
        val publicKeySlot = slot<String>()
        every { pgp.encryptSessionKey(capture(sessionKeySlot), capture(publicKeySlot)) } returns keyPacket

        helper.changeNameRecipient(
            oldNameArmored = oldName,
            oldDecryptKeyBytes = parentKey,
            newPlaintextName = "Holiday 2026",
            targetPublicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----album",
            signerKeyBytes = signerKey,
        )

        assertSame(storedSessionKey, sessionKeySlot.captured)
        assertSame("-----BEGIN PGP PUBLIC KEY BLOCK-----album", publicKeySlot.captured)
        verify(exactly = 0) { pgp.generateNewSessionKey() }
    }
}
