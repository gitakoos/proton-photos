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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule the refresh's fast path answers through [resolveNameRefresh]: has this photo been renamed
 * somewhere else, and may the stored name be replaced? Both directions are visible to the user. Skip
 * a real rename and the old caption stays forever, since nothing else on that path reconsiders a
 * name. Accept a failed decrypt and a good caption is blanked everywhere at once.
 *
 * The decrypt is passed in as a lambda so the tests can also assert the thing that keeps this cheap:
 * it is not called unless the digests disagree.
 *
 * Pure strings and a lambda → no DI, no DB, no crypto.
 */
class NameRefreshTest {

    @Test
    fun `a name whose ciphertext did not move is left alone without decrypting`() {
        // The common case, and the one the whole digest exists for. Running PGP per row here is what
        // the fast path is avoiding, so an unchanged photo must not reach the decrypt at all.
        var decryptCalls = 0
        val result = resolveNameRefresh(
            storedFingerprint = FINGERPRINT,
            freshFingerprint = FINGERPRINT,
        ) { decryptCalls++; "anything" }

        assertNull("an unchanged digest means an unchanged name", result)
        assertEquals("nothing may be decrypted when the digests agree", 0, decryptCalls)
    }

    @Test
    fun `a rename made elsewhere replaces the stored name and records the new digest`() {
        val result = resolveNameRefresh(
            storedFingerprint = FINGERPRINT,
            freshFingerprint = OTHER_FINGERPRINT,
        ) { "Sunset over the lake.jpg" }

        assertEquals("Sunset over the lake.jpg", result?.displayName)
        // The new digest is stored alongside the name, so the next walk compares free again rather
        // than decrypting this row forever.
        assertEquals(OTHER_FINGERPRINT, result?.fingerprint)
    }

    @Test
    fun `a blank decrypt never overwrites a good name`() {
        // Name decryption yields an empty string when the crypto fails, so a blank result is a
        // failure report, not a name. Writing it would erase the caption everywhere the row is read.
        assertNull(
            resolveNameRefresh(FINGERPRINT, OTHER_FINGERPRINT) { "" },
        )
        assertNull(
            resolveNameRefresh(FINGERPRINT, OTHER_FINGERPRINT) { "   " },
        )
    }

    @Test
    fun `a failed decrypt never overwrites a good name`() {
        // Returning null leaves the row untouched, digest included, so it stays marked for a recheck
        // and the next pass retries instead of treating one transient failure as the answer.
        assertNull(
            resolveNameRefresh(FINGERPRINT, OTHER_FINGERPRINT) { null },
        )
    }

    @Test
    fun `a row with no stored digest is rechecked rather than trusted`() {
        // Every row starts here after the column is added, and this single pass is what repairs the
        // names that already drifted. Treating a missing digest as agreement would leave them stale.
        var decryptCalls = 0
        val result = resolveNameRefresh(
            storedFingerprint = null,
            freshFingerprint = FINGERPRINT,
        ) { decryptCalls++; "the real name.jpg" }

        assertEquals("a missing digest must force the check", 1, decryptCalls)
        assertEquals("the real name.jpg", result?.displayName)
        assertEquals(FINGERPRINT, result?.fingerprint)
    }

    @Test
    fun `a recheck that lands on the same name still records the digest`() {
        // What a re-armored but unchanged blob looks like: one decrypt confirms the name, and
        // recording the digest keeps that cost to once rather than every walk.
        val result = resolveNameRefresh(null, FINGERPRINT) { "holiday.jpg" }

        assertEquals("holiday.jpg", result?.displayName)
        assertEquals(FINGERPRINT, result?.fingerprint)
    }

    @Test
    fun `no name on the wire leaves the stored one standing`() {
        // A detail response without a name gives nothing to compare against, so there is no
        // question to answer and nothing to spend a decrypt on.
        var decryptCalls = 0
        val result = resolveNameRefresh(FINGERPRINT, null) { decryptCalls++; "x" }

        assertNull(result)
        assertEquals(0, decryptCalls)
    }

    @Test
    fun `the digest is stable per ciphertext and separates different ones`() {
        // The two properties the comparison rests on. A digest that moved on its own would decrypt
        // every row every walk; one that collided would swallow a rename.
        assertEquals(nameFingerprint(ARMORED_NAME), nameFingerprint(ARMORED_NAME))
        assertNotEquals(nameFingerprint(ARMORED_NAME), nameFingerprint(ARMORED_RENAMED))
        assertNull("no ciphertext, no digest", nameFingerprint(null))
        // Single-character drift still separates, which is what a re-encrypted name looks like.
        assertNotEquals(nameFingerprint("a"), nameFingerprint("b"))
        assertNotEquals(nameFingerprint(""), nameFingerprint("a"))
    }

    private companion object {
        val ARMORED_NAME = "-----BEGIN PGP MESSAGE-----\nwcBMA1Nx4Dcaoriginal\n-----END PGP MESSAGE-----"
        val ARMORED_RENAMED = "-----BEGIN PGP MESSAGE-----\nwcBMA1Nx4Dcarenamed0\n-----END PGP MESSAGE-----"
        val FINGERPRINT: String = nameFingerprint(ARMORED_NAME)!!
        val OTHER_FINGERPRINT: String = nameFingerprint(ARMORED_RENAMED)!!
    }
}
