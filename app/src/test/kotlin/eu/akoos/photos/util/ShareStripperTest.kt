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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [shareStripKind]. The full [stripForShare] orchestration needs a Context,
 * a registered FileProvider and real files, so — like [VideoMetadataStripperTest] — it is exercised
 * on-device; only the kind decision is unit-testable, and it is pure (string parsing + [mimeFromPath]).
 */
class ShareStripperTest {

    // ─── image mimes ──────────────────────────────────────────────────────────

    @Test
    fun `jpeg mime is image`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind("image/jpeg", "content://media/1"))
    }

    @Test
    fun `png mime is image`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind("image/png", "content://media/2"))
    }

    @Test
    fun `webp mime is image`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind("image/webp", "content://media/3"))
    }

    @Test
    fun `heic mime is image`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind("image/heic", "content://media/4"))
    }

    // ─── video mimes ──────────────────────────────────────────────────────────

    @Test
    fun `mp4 mime is video`() {
        assertEquals(ShareStripKind.VIDEO, shareStripKind("video/mp4", "content://media/5"))
    }

    @Test
    fun `quicktime mime is video`() {
        assertEquals(ShareStripKind.VIDEO, shareStripKind("video/quicktime", "content://media/6"))
    }

    @Test
    fun `codec parameter and mixed case still resolve to the base video type`() {
        assertEquals(ShareStripKind.VIDEO, shareStripKind("VIDEO/MP4; codecs=avc1", "content://media/7"))
    }

    // ─── mime absent, decided by extension ────────────────────────────────────

    @Test
    fun `null mime falls back to a jpg extension`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind(null, "file:///a/photo.jpg"))
    }

    @Test
    fun `null mime falls back to an mp4 extension`() {
        assertEquals(ShareStripKind.VIDEO, shareStripKind(null, "file:///a/clip.mp4"))
    }

    @Test
    fun `blank mime falls back to the extension`() {
        assertEquals(ShareStripKind.IMAGE, shareStripKind("", "file:///a/photo.png"))
    }

    // ─── unsupported ──────────────────────────────────────────────────────────

    @Test
    fun `pdf mime is unsupported`() {
        assertEquals(ShareStripKind.UNSUPPORTED, shareStripKind("application/pdf", "file:///a/doc.pdf"))
    }

    @Test
    fun `null mime with no extension is unsupported`() {
        assertEquals(ShareStripKind.UNSUPPORTED, shareStripKind(null, "content://media/8"))
    }

    @Test
    fun `a concrete non-media mime wins over an image-looking path`() {
        // mime takes precedence; the extension is consulted only when the mime is absent or blank.
        assertEquals(ShareStripKind.UNSUPPORTED, shareStripKind("application/octet-stream", "file:///a/photo.jpg"))
    }
}
