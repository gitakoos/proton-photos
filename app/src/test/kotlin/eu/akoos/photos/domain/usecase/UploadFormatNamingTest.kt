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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadFormatNamingTest {

    @Test
    fun a_heic_compressed_to_jpeg_is_uploaded_as_a_jpeg() {
        assertEquals("IMG_1234.jpg" to "image/jpeg", UploadFormatNaming.forProducedFormat("IMG_1234.heic", "image/heic", "image/jpeg"))
    }

    @Test
    fun the_extension_case_is_kept() {
        assertEquals("IMG_1234.JPG" to "image/jpeg", UploadFormatNaming.forProducedFormat("IMG_1234.HEIC", "image/heic", "image/jpeg"))
        assertEquals("shot.jpg" to "image/jpeg", UploadFormatNaming.forProducedFormat("shot.png", "image/png", "image/jpeg"))
    }

    @Test
    fun an_opaque_webp_compressed_to_jpeg_becomes_a_jpeg() {
        assertEquals("PXL_1.jpg" to "image/jpeg", UploadFormatNaming.forProducedFormat("PXL_1.webp", "image/webp", "image/jpeg"))
    }

    @Test
    fun a_jpeg_staying_a_jpeg_is_untouched_whatever_its_spelling() {
        assertEquals("a.jpeg" to "image/jpeg", UploadFormatNaming.forProducedFormat("a.jpeg", "image/jpeg", "image/jpeg"))
        assertEquals("a.JPG" to "image/jpg", UploadFormatNaming.forProducedFormat("a.JPG", "image/jpg", "image/jpeg"))
    }

    @Test
    fun nothing_produced_means_nothing_changes() {
        assertEquals("a.heic" to "image/heic", UploadFormatNaming.forProducedFormat("a.heic", "image/heic", null))
    }

    @Test
    fun a_name_without_an_image_extension_gets_one_appended() {
        assertEquals("scan.jpg" to "image/jpeg", UploadFormatNaming.forProducedFormat("scan", "image/png", "image/jpeg"))
        assertEquals("trip.2024.jpg" to "image/jpeg", UploadFormatNaming.forProducedFormat("trip.2024", "image/heic", "image/jpeg"))
    }

    @Test
    fun an_unknown_output_type_never_renames() {
        assertEquals("a.png" to "image/png", UploadFormatNaming.forProducedFormat("a.png", "image/png", "image/avif"))
    }

    @Test
    fun a_transcoded_mov_3gp_webm_or_mkv_is_uploaded_as_an_mp4() {
        assertEquals("IMG_0001.MP4" to "video/mp4", UploadFormatNaming.forProducedFormat("IMG_0001.MOV", "video/quicktime", "video/mp4"))
        assertEquals("clip.mp4" to "video/mp4", UploadFormatNaming.forProducedFormat("clip.3gp", "video/3gpp", "video/mp4"))
        assertEquals("rec.mp4" to "video/mp4", UploadFormatNaming.forProducedFormat("rec.webm", "video/webm", "video/mp4"))
        assertEquals("a.mp4" to "video/mp4", UploadFormatNaming.forProducedFormat("a.mkv", "video/x-matroska", "video/mp4"))
    }

    @Test
    fun an_mp4_staying_an_mp4_is_untouched() {
        assertEquals("VID_1.mp4" to "video/mp4", UploadFormatNaming.forProducedFormat("VID_1.mp4", "video/mp4", "video/mp4"))
        assertEquals(true, UploadFormatNaming.isMp4("video/mp4"))
        assertEquals(false, UploadFormatNaming.isMp4("video/quicktime"))
    }

    @Test
    fun reconcile_looks_for_the_reencoded_name_only_when_the_format_changes() {
        assertEquals("IMG_1.jpg", UploadFormatNaming.reencodedName("IMG_1.heic", "image/heic"))
        assertNull(UploadFormatNaming.reencodedName("IMG_1.jpg", "image/jpeg"))
        assertEquals("VID_1.mp4", UploadFormatNaming.reencodedName("VID_1.mov", "video/quicktime"))
        assertNull(UploadFormatNaming.reencodedName("VID_1.mp4", "video/mp4"))
        assertNull(UploadFormatNaming.reencodedName("notes.pdf", "application/pdf"))
    }
}
