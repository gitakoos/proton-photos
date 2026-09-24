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

package eu.akoos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external edit/view parser hands off only content:// URIs of another app's provider.
 * [isAcceptableExternalEditScheme] refuses a file:// path (the app's own private storage), and
 * [isAcceptableExternalEditUri] also refuses the app's own FileProvider authorities, which the app can
 * read itself, so a shared ACTION_EDIT / ACTION_VIEW intent cannot aim it at the vault or the decrypted
 * cache. Pure predicates, no Android runtime.
 */
class ExternalEditSchemeTest {

    private val pkg = "eu.akoos.photos"

    @Test
    fun `a content scheme is accepted`() {
        assertTrue(isAcceptableExternalEditScheme("content"))
    }

    @Test
    fun `another app's content authority is accepted`() {
        assertTrue(isAcceptableExternalEditUri("content", "media", pkg))
        assertTrue(isAcceptableExternalEditUri("content", "com.android.providers.media.documents", pkg))
        assertTrue(isAcceptableExternalEditUri("content", "com.google.android.apps.photos.contentprovider", pkg))
    }

    @Test
    fun `an authority that only shares the package's leading characters is accepted`() {
        assertTrue(isAcceptableExternalEditUri("content", "eu.akoos.photosextra.provider", pkg))
        assertTrue(isAcceptableExternalEditUri("content", "eu.akoos", pkg))
    }

    @Test
    fun `the app's own file providers are rejected`() {
        assertFalse(isAcceptableExternalEditUri("content", "eu.akoos.photos.share.fileprovider", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "eu.akoos.photos.updater.fileprovider", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "eu.akoos.photos.androidx-startup", pkg))
    }

    @Test
    fun `an authority equal to the package name is rejected`() {
        assertFalse(isAcceptableExternalEditUri("content", "eu.akoos.photos", pkg))
    }

    @Test
    fun `an own authority behind a user prefix is rejected`() {
        assertFalse(isAcceptableExternalEditUri("content", "0@eu.akoos.photos.share.fileprovider", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "10@eu.akoos.photos.share.fileprovider", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "a@b@eu.akoos.photos.share.fileprovider", pkg))
    }

    @Test
    fun `an own authority with a port or other casing is rejected`() {
        assertFalse(isAcceptableExternalEditUri("content", "eu.akoos.photos.share.fileprovider:80", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "EU.Akoos.Photos.share.fileprovider", pkg))
        assertFalse(isAcceptableExternalEditUri("content", " eu.akoos.photos.share.fileprovider", pkg))
    }

    @Test
    fun `a missing or blank authority is rejected`() {
        assertFalse(isAcceptableExternalEditUri("content", null, pkg))
        assertFalse(isAcceptableExternalEditUri("content", "", pkg))
        assertFalse(isAcceptableExternalEditUri("content", "   ", pkg))
    }

    @Test
    fun `a foreign authority still needs the content scheme`() {
        assertFalse(isAcceptableExternalEditUri("file", "media", pkg))
        assertFalse(isAcceptableExternalEditUri(null, "media", pkg))
    }

    @Test
    fun `the own-authority check follows the running package`() {
        assertTrue(isOwnProviderAuthority("eu.akoos.photos.debug.fileprovider", "eu.akoos.photos.debug"))
        assertFalse(isOwnProviderAuthority("eu.akoos.photos.share.fileprovider", "eu.akoos.photos.debug"))
        assertFalse(isOwnProviderAuthority(null, pkg))
    }

    @Test
    fun `a file scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme("file"))
    }

    @Test
    fun `a missing scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme(null))
    }

    @Test
    fun `an http scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme("http"))
    }

    @Test
    fun `an empty scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme(""))
    }
}
