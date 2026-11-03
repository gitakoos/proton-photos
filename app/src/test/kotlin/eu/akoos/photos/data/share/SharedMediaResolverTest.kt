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

package eu.akoos.photos.data.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedMediaResolverTest {

    private fun canonical(uri: String): String? = Uri.parse(uri).let { canonicalMediaStoreUri(it.scheme, it.authority, it.pathSegments) }

    @Test
    fun `a MediaStore photo or video of any volume maps to the uri the local listing writes`() {
        assertEquals("content://media/external/images/media/42", canonical("content://media/external/images/media/42"))
        assertEquals("content://media/external/images/media/42", canonical("content://media/external_primary/images/media/42"))
        assertEquals("content://media/external/video/media/7", canonical("content://media/external/video/media/7"))
    }

    @Test
    fun `other uris have no direct match`() {
        assertNull(canonical("content://media/external/file/42"))
        assertNull(canonical("content://media/picker/0/com.android.providers.media.photopicker/media/42"))
        assertNull(canonical("content://media/external/images/media"))
        assertNull(canonical("content://media/external/images/media/abc"))
        assertNull(canonical("content://com.sec.android.gallery3d.provider/picasa/item/42"))
        assertNull(canonical("file:///sdcard/DCIM/a.jpg"))
    }

    @Test
    fun `a shared file keeps its own name and gets an extension when it has none`() {
        assertEquals("cat.gif", sharedFileName("cat.gif", "image/gif", 5L))
        assertEquals("cat.gif", sharedFileName("cat", "image/gif", 5L))
        assertEquals("a_b.png", sharedFileName("a/b.png", "image/png", 5L))
        assertEquals("shared_5.mp4", sharedFileName(null, "video/mp4", 5L))
        assertEquals("shared_5.jpg", sharedFileName("  ", "image/x-unknown", 5L))
    }

    @Test
    fun `a single share is read from its stream`() {
        val uri = Uri.parse("content://media/external/images/media/1")
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(listOf(uri), sharedUrisOf(intent))
    }

    @Test
    fun `a multiple share is read from its stream list`() {
        val uris = arrayListOf(Uri.parse("content://a/1"), Uri.parse("content://a/2"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        assertEquals(uris, sharedUrisOf(intent))
    }

    @Test
    fun `a share with only clip data is read from the clip`() {
        val first = Uri.parse("content://a/1")
        val second = Uri.parse("content://a/2")
        val clip = ClipData.newRawUri("shared", first).apply { addItem(ClipData.Item(second)) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply { clipData = clip }
        assertEquals(listOf(first, second), sharedUrisOf(intent))
    }

    @Test
    fun `any other action carries nothing`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/images/media/1"))
        assertEquals(emptyList<Uri>(), sharedUrisOf(intent))
    }
    @Test
    fun `only another app's content uri is read`() {
        val own = "eu.akoos.photos"
        assertTrue(readableShare("content", "media", own))
        assertTrue(readableShare("content", "com.whatsapp.provider.media", own))
        assertFalse(readableShare("file", null, own))
        assertFalse(readableShare("file", "", own))
        assertFalse(readableShare("content", own, own))
        assertFalse(readableShare("content", "$own.share.fileprovider", own))
        assertFalse(readableShare("content", "$own.debug.updater.fileprovider", own))
        assertFalse(readableShare("content", null, own))
        assertFalse(readableShare("http", "example.com", own))
    }

    @Test
    fun `a file uri to this app's own database is refused without being opened`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolved = SharedMediaResolver(context.contentResolver, context.packageName)
            .resolve(listOf(Uri.parse("file:///data/data/${context.packageName}/databases/app.db")), "image/jpeg")
        assertEquals(emptyList<String>(), resolved.uris)
        assertEquals(1, resolved.unreadable)
        assertEquals(0, resolved.saved)
    }
}
