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

import eu.akoos.photos.util.toLowerHex
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The name hash pairs a device file with its Drive copy by content, so it must stay byte for byte what
 * it was while the Mac set-up is kept per key and the hex is built without a formatter.
 */
class NameHashTest {

    private val helper = DriveCryptoHelper(mockk(), mockk(), mockk())

    private fun reference(name: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun hex_matches_the_formatter_for_every_byte_value() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(all.joinToString("") { "%02x".format(it) }, all.toLowerHex())
        assertEquals("", ByteArray(0).toLowerHex())
    }

    @Test
    fun name_hash_matches_a_fresh_hmac_across_repeated_calls_and_key_changes() {
        val keyA = ByteArray(32) { (it * 7).toByte() }
        val keyB = ByteArray(32) { (255 - it).toByte() }
        val names = listOf("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", "IMG_0001.jpg", "", "árvíztűrő.heic")
        repeat(2) {
            for (key in listOf(keyA, keyB, keyA)) {
                for (name in names) assertEquals(reference(name, key), helper.computeNameHash(name, key))
            }
        }
    }

    @Test
    fun a_key_changed_in_place_by_the_caller_is_not_mistaken_for_the_held_one() {
        val key = ByteArray(32) { it.toByte() }
        val before = helper.computeNameHash("x", key)
        key[0] = 99
        assertEquals(reference("x", key), helper.computeNameHash("x", key))
        assertEquals(before, reference("x", ByteArray(32) { it.toByte() }))
    }
}
