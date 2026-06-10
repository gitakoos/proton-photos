/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import eu.akoos.photos.data.crypto.DriveCryptoHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Decision/selection-logic coverage for [AlbumCryptoChain] — the single source of truth
 * for "which key decrypts what" in the album-sharing chain. Real PGP is never exercised:
 * [DriveCryptoHelper] is mocked and every key is a byte-array sentinel, so the assertions
 * are about ROUTING (which bytes get returned / passed where) and BRANCH SELECTION, not
 * cryptographic correctness.
 *
 * Note: this class is documented as stateless with no caching, so there is no in-class
 * cache-hit short-circuit to assert. The genuine short-circuit in [selectPhotoParentKey]
 * is the shared-context precedence (a passed album key wins over the context's own copy),
 * which `shared-with-me prefers passed album key over context copy` covers.
 */
class AlbumCryptoChainTest {

    private lateinit var cryptoHelper: DriveCryptoHelper
    private lateinit var chain: AlbumCryptoChain

    // Distinct sentinels — content, not identity, is what the routing assertions check.
    private val rootKey = byteArrayOf(1, 1, 1)
    private val albumKey = byteArrayOf(2, 2, 2)
    private val shareKey = byteArrayOf(3, 3, 3)
    private val contextAlbumKey = byteArrayOf(4, 4, 4)

    @Before
    fun setUp() {
        cryptoHelper = mockk()
        chain = AlbumCryptoChain(cryptoHelper)
    }

    private fun sharingContext(albumKeyBytes: ByteArray = contextAlbumKey) =
        AlbumCryptoChain.SharingContext(
            albumLinkId = "album-link",
            sharingShareId = "share-id",
            sharedShareKeyBytes = shareKey,
            albumKeyBytes = albumKeyBytes,
        )

    // ─── selectPhotoParentKey: owner-side ─────────────────────────────────────

    @Test
    fun `own album prefers the album key over root`() {
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = albumKey,
            photoParentLinkId = "photos-root",
            photosRootLinkId = "photos-root",
            sharingContext = null,
        )
        assertSame(albumKey, result)
    }

    @Test
    fun `own album with no album key falls back to root for a root-parented legacy photo`() {
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = null,
            photoParentLinkId = "photos-root",
            photosRootLinkId = "photos-root",
            sharingContext = null,
        )
        assertSame(rootKey, result)
    }

    @Test
    fun `own album with no album key falls back to root when parent id is null`() {
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = null,
            photoParentLinkId = null,
            photosRootLinkId = "photos-root",
            sharingContext = null,
        )
        assertSame(rootKey, result)
    }

    @Test
    fun `own album with no album key and a non-root parent yields null`() {
        // Neither the album key nor the legacy-root rule applies, so the caller must
        // treat this as a hard cannot-decrypt rather than guess a key.
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = null,
            photoParentLinkId = "some-other-folder",
            photosRootLinkId = "photos-root",
            sharingContext = null,
        )
        assertNull(result)
    }

    // ─── selectPhotoParentKey: shared-with-me ─────────────────────────────────

    @Test
    fun `shared-with-me uses the album key never the recipient root`() {
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = albumKey,
            photoParentLinkId = "owner-photos-root",
            photosRootLinkId = "recipient-photos-root",
            sharingContext = sharingContext(),
        )
        assertSame(albumKey, result)
    }

    @Test
    fun `shared-with-me prefers passed album key over context copy`() {
        // Both are present; the explicitly-passed album key wins (the short-circuit).
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = albumKey,
            photoParentLinkId = null,
            photosRootLinkId = null,
            sharingContext = sharingContext(albumKeyBytes = contextAlbumKey),
        )
        assertSame(albumKey, result)
    }

    @Test
    fun `shared-with-me with no passed album key falls back to context album key not root`() {
        val result = chain.selectPhotoParentKey(
            rootLinkKeyBytes = rootKey,
            albumKeyBytes = null,
            photoParentLinkId = "owner-photos-root",
            photosRootLinkId = "recipient-photos-root",
            sharingContext = sharingContext(albumKeyBytes = contextAlbumKey),
        )
        // Must be the context's album key — falling through to rootKey here would surface
        // the recipient's own root, which never decrypts the owner-encrypted payload.
        assertSame(contextAlbumKey, result)
        assertTrue(result !== rootKey)
    }

    // ─── decryptAlbumKey: routing + failure propagation ───────────────────────

    @Test
    fun `decryptAlbumKey returns the helper plaintext on success`() = runTest {
        val plaintext = byteArrayOf(9, 8, 7)
        every { cryptoHelper.decryptNodeKey(any(), any(), any()) } returns plaintext

        val result = chain.decryptAlbumKey("node-key", "node-passphrase", albumKey)

        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `decryptAlbumKey routes its arguments unchanged to the crypto helper`() = runTest {
        val nodeKeySlot = slot<String>()
        val passphraseSlot = slot<String>()
        val parentKeySlot = slot<ByteArray>()
        every {
            cryptoHelper.decryptNodeKey(capture(nodeKeySlot), capture(passphraseSlot), capture(parentKeySlot))
        } returns byteArrayOf(0)

        chain.decryptAlbumKey("the-node-key", "the-passphrase", rootKey)

        verify(exactly = 1) { cryptoHelper.decryptNodeKey(any(), any(), any()) }
        assertTrue(nodeKeySlot.captured == "the-node-key")
        assertTrue(passphraseSlot.captured == "the-passphrase")
        assertArrayEquals(rootKey, parentKeySlot.captured)
    }

    @Test
    fun `decryptAlbumKey swallows a helper failure and returns null`() = runTest {
        every { cryptoHelper.decryptNodeKey(any(), any(), any()) } throws RuntimeException("bad passphrase")

        val result = chain.decryptAlbumKey("node-key", "node-passphrase", albumKey, contextHint = "unit-test")

        // Failure is contained here (logged + null) so callers never have to wrap the call.
        assertNull(result)
    }
}
