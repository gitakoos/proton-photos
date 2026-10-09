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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** The zip a forced sign-out leaves for the vault, and the sealing of the password it is made with. */
class VaultZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val password = "correct horse".toCharArray()

    private fun entry(name: String, bytes: ByteArray): VaultZipEntry {
        val f = tmp.newFile("src_" + name.replace('/', '_'))
        f.writeBytes(bytes)
        return VaultZipEntry(f, name, 1_700_000_000_000L)
    }

    private fun zipOf(entries: List<VaultZipEntry>): File {
        val out = File(tmp.root, "out.zip")
        VaultZip.write(entries, password, out)
        return out
    }

    @Test
    fun `a written zip verifies with the password and holds every file`() {
        val entries = listOf(entry("a.jpg", ByteArray(5000) { it.toByte() }), entry("b.mp4", ByteArray(70_000) { (it * 7).toByte() }))
        val zip = zipOf(entries)
        assertTrue(VaultZip.verify(zip, password, entries))
    }

    @Test
    fun `a wrong password does not verify`() {
        val entries = listOf(entry("a.jpg", ByteArray(2000) { it.toByte() }))
        val zip = zipOf(entries)
        assertFalse(VaultZip.verify(zip, "wrong password".toCharArray(), entries))
    }

    @Test
    fun `a damaged zip does not verify`() {
        val entries = listOf(entry("a.jpg", ByteArray(20_000) { it.toByte() }))
        val zip = zipOf(entries)
        val bytes = zip.readBytes()
        bytes[bytes.size / 3] = (bytes[bytes.size / 3].toInt() xor 0x55).toByte()
        zip.writeBytes(bytes)
        assertFalse(VaultZip.verify(zip, password, entries))
    }

    @Test
    fun `a zip missing a file does not verify`() {
        val kept = entry("a.jpg", ByteArray(100))
        val zip = zipOf(listOf(kept))
        assertFalse(VaultZip.verify(zip, password, listOf(kept, entry("b.jpg", ByteArray(100)))))
    }

    @Test
    fun `names inside one zip stay unique and keep their extension`() {
        val names = VaultZip.uniqueNames(listOf("IMG.jpg", "img.JPG", "IMG.jpg", "", "a/b.png")) { "photo_$it" }
        assertEquals(listOf("IMG.jpg", "img (2).JPG", "IMG (3).jpg", "photo_3", "a_b.png"), names)
    }

    @Test
    fun `the sealed password opens only with its own key`() {
        val gen = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = gen.generateKey()
        val sealed = VaultPasswordCodec.encrypt("pässword 123".toCharArray(), key)
        assertEquals("pässword 123", String(VaultPasswordCodec.decrypt(sealed, key)))
        val other = gen.generateKey()
        val failed = runCatching { VaultPasswordCodec.decrypt(sealed, other) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `a typed password matches only the stored one`() {
        val stored = "pässword 123".toCharArray()
        assertTrue(vaultPasswordMatches(stored, "pässword 123".toCharArray()))
        assertFalse(vaultPasswordMatches(stored, "password 123".toCharArray()))
        assertFalse(vaultPasswordMatches(stored, "pässword 1234".toCharArray()))
        assertFalse(vaultPasswordMatches(stored, CharArray(0)))
    }
}
