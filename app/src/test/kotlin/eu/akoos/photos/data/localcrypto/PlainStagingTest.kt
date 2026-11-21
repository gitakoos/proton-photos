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

package eu.akoos.photos.data.localcrypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Plaintext copies a library needs as a file path live in one folder emptied at every start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainStagingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the plaintext staging folder is emptied at start`() {
        val copy = File(VaultFiles.plainStagingDir(context), "stripped_1.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(File(context.cacheDir, VaultFiles.PLAIN_STAGING_DIR), copy.parentFile)

        VaultFiles.clearScratch(context)

        assertFalse(copy.exists())
    }

    @Test
    fun `plaintext copies an earlier version left in the cache folder go, folders and newer files stay`() {
        val started = 1_800_000_000_000L
        fun file(dir: File, name: String, modified: Long = started - 60_000) =
            File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)); setLastModified(modified) }
        val cache = context.cacheDir
        val stale = listOf(
            file(cache, "stripped_9.jpg"),
            file(cache, "compressed_9.jpg"),
            file(cache, "videocompress_9.mp4"),
            file(cache, "motion_out_9.jpg"),
            file(cache, "import_9.jpg"),
            file(cache, "stage_9.jpg"),
            file(File(cache, "editor"), "edit_9.jpg"),
        )
        val resume = File(cache, "upload_abc").apply { mkdirs() }
        val scan = File(cache, "import_scan").apply { mkdirs() }
        val kept = listOf(
            file(cache, "stripped_new.jpg", modified = started + 1_000),
            file(cache, "other.jpg"),
            file(resume, "block_0.enc"),
        )

        VaultFiles.sweepLegacyPlainCopies(context, before = started)

        assertTrue(stale.none { it.exists() })
        assertTrue(kept.all { it.exists() })
        assertTrue(scan.isDirectory)
        kept.forEach { it.delete() }
    }

    @Test
    fun `import review thumbnails are an encrypted folder the image loader decrypts`() {
        val thumb = File(File(context.cacheDir, "import_thumbs"), "zip_entry.jpg")
        assertEquals(LocalFiles.Area.IMPORT_THUMBS, LocalFiles.areaOf(context, thumb))
        assertTrue(LocalFiles.Area.IMPORT_THUMBS.regenerable)
    }
}
