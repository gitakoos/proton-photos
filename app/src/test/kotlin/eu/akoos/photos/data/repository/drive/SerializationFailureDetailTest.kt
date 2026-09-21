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

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Stands in for kotlinx.serialization's own decoding failure, whose type is not public. */
private class JsonDecodingException(message: String?) : SerializationException(message)

/** Stands in for kotlinx.serialization's missing-field failure, which carries a path but no excerpt. */
private class MissingFieldException(message: String) : SerializationException(message)

/**
 * What survives [serializationFailureDetail] on the way into the diagnostics buffer a tester pastes
 * into a public issue.
 *
 * A decoding failure's message is two things glued together: a description ending in the
 * `at path: $.Field` trailer, and a raw `JSON input:` excerpt of the body that failed. On a Drive
 * listing page that excerpt is link ids, share ids and encrypted names, so the tests assert the
 * exact surviving string rather than only that the interesting part is present.
 */
class SerializationFailureDetailTest {

    private val linkId = "aB3kJ9xQ2mLpZ7wR4tYvNs"
    private val shareId = "kQ8vTmR2nF5xLw9dPzYbHc"
    private val encryptedName = "0sBuTHiSiSaNeNcRyPtEdNaMe4Lw9dPzYbHc"

    private fun excerpt() =
        """JSON input: .....{"LinkID":"$linkId","Name":"$encryptedName","ShareID":"$shareId"}....."""

    @Test
    fun `the JSON input excerpt cannot survive`() {
        val cause = JsonDecodingException(
            "Unexpected JSON token at offset 214: Expected quotation mark '\"', " +
                "but had 'n' instead at path: \$.Links[3].Name\n" + excerpt()
        )
        val out = serializationFailureDetail(cause)
        assertEquals("JsonDecodingException at \$.Links[3].Name", out)
        assertFalse(out.contains("JSON input"))
        assertFalse(out.contains(linkId))
        assertFalse(out.contains(shareId))
        assertFalse(out.contains(encryptedName))
    }

    @Test
    fun `free text before the path is dropped even when it quotes a value`() {
        // The description itself can echo a key or a token straight out of the body, so only the
        // path trailer is kept from it.
        val cause = JsonDecodingException(
            "Unexpected JSON token at offset 91: Encountered an unknown key '$linkId' " +
                "at path: \$.Links[0]\n" + excerpt()
        )
        val out = serializationFailureDetail(cause)
        assertEquals("JsonDecodingException at \$.Links[0]", out)
        assertFalse(out.contains(linkId))
    }

    @Test
    fun `a missing field keeps the DTO path that names the failing field`() {
        // This is the whole point of logging the cause at all: the path says which photo field the
        // server answered differently.
        val cause = MissingFieldException(
            "Field 'LinkID' is required for type with serial name " +
                "'eu.akoos.photos.data.remote.PhotoLinkDto', but it was missing at path: \$.Links[3]"
        )
        assertEquals("MissingFieldException at \$.Links[3]", serializationFailureDetail(cause))
    }

    @Test
    fun `the excerpt cannot supply the path itself`() {
        // The body is cut off before the trailer is looked for, so a page whose own text contains
        // the marker cannot smuggle a fragment of itself through as a path.
        val cause = JsonDecodingException(
            "Unexpected end of input\nJSON input: {\"Name\":\"at path: \$.$encryptedName\"}"
        )
        val out = serializationFailureDetail(cause)
        assertEquals("JsonDecodingException", out)
        assertFalse(out.contains(encryptedName))
    }

    @Test
    fun `a message with no path degrades to the exception type`() {
        assertEquals(
            "JsonDecodingException",
            serializationFailureDetail(JsonDecodingException("Unexpected end of input")),
        )
    }

    @Test
    fun `a null message degrades to the exception type`() {
        assertEquals("JsonDecodingException", serializationFailureDetail(JsonDecodingException(null)))
    }

    @Test
    fun `text after the marker that is not a path expression is dropped`() {
        // Only kotlinx's own `$`-rooted path shape is recognised; anything else after the marker is
        // free text and could be anything.
        assertEquals(
            "JsonDecodingException",
            serializationFailureDetail(JsonDecodingException("broke at path: $encryptedName")),
        )
    }

    @Test
    fun `a runaway path is capped`() {
        val long = "\$." + "Field.".repeat(60)
        val out = serializationFailureDetail(JsonDecodingException("boom at path: $long"))
        // The type, the separator, and at most 80 characters of path.
        assertEquals("JsonDecodingException at ".length + 80, out.length)
    }
}
