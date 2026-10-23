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

package eu.akoos.photos.data.upload

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.hidden.VaultZipPublisher
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.usecase.sizeChangedSinceBackup
import eu.akoos.photos.presentation.editor.requireEncoded
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * What the device itself does under the free-up and save guards: whether MediaStore reports a new size
 * after a file is rewritten in place (the only sign reconcile has of an edit made by another app),
 * whether the app's own guarded rewrite is recognised at the size MediaStore then reports, whether a
 * failed encode leaves the real original byte for byte, and whether a vault zip lands in Downloads as
 * a finished, non-pending item. Every row inserted here is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceRewriteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        LocalRewrites.clear()
    }

    @After
    fun tearDown() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        inserted.clear()
        LocalRewrites.clear()
    }

    private fun jpeg(side: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun insertJpeg(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "pfp_rewrite_${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PfpDeviceTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assumeTrue("MediaStore refused the fixture insert", uri != null)
        uri!!
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun mediaStoreSize(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)!!.use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }

    /** MediaStore's size for [uri] once it differs from [old], or the last value read after [timeoutMs]. */
    private fun awaitSizeChange(uri: Uri, old: Long, timeoutMs: Long = 10_000): Long {
        val end = System.currentTimeMillis() + timeoutMs
        var now = mediaStoreSize(uri)
        while (now == old && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            now = mediaStoreSize(uri)
        }
        return now
    }

    private fun readAll(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun backedUp(uri: Uri, size: Long) = SyncState(
        localUri = uri.toString(), cloudFileId = "link", localHash = "a".repeat(40), cloudHash = "c",
        status = SyncStatus.SYNCED, lastSyncAttemptMs = 1L, lastSyncSuccessMs = 1L, backedUpAtMs = 1L,
        sizeBytes = size,
    )

    @Test
    fun anInPlaceRewriteShowsUpAsANewMediaStoreSize() {
        val original = jpeg(32, 0xFF3366AA.toInt())
        val uri = insertJpeg(original)
        val before = mediaStoreSize(uri)
        assertEquals(original.size.toLong(), before)

        val edited = jpeg(256, 0xFFAA6633.toInt())
        assertNotEquals(original.size, edited.size)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(edited) }

        val after = awaitSizeChange(uri, before)
        assertEquals("MediaStore must report the rewritten length", edited.size.toLong(), after)
        assertTrue(sizeChangedSinceBackup(backedUp(uri, before), after))
    }

    @Test
    fun theAppsOwnGuardedRewriteIsRecognisedAtTheSizeMediaStoreReports() {
        val uri = insertJpeg(jpeg(32, 0xFF3366AA.toInt()))
        val before = mediaStoreSize(uri)
        val edited = jpeg(32, 0xFF11EE22.toInt()) + ByteArray(4096)

        LocalOverwriteGuard(context).run(uri.toString()) {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(edited) }
        }

        val after = awaitSizeChange(uri, before)
        assertEquals(edited.size.toLong(), after)
        assertTrue("the guard's note must match what reconcile will read", LocalRewrites.consume(uri.toString(), after))
    }

    @Test
    fun aFailedEncodeLeavesTheRealOriginalByteForByte() {
        val original = jpeg(64, 0xFF3366AA.toInt())
        val uri = insertJpeg(original)

        try {
            LocalOverwriteGuard(context).run(uri.toString()) {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ByteArray(10)) }
                requireEncoded(false)
            }
            fail("a failed encode must throw")
        } catch (e: IOException) {
            assertNotNull(e.message)
        }

        assertArrayEquals(original, readAll(uri))
        assertEquals(original.size.toLong(), awaitSizeChange(uri, -2L, 2_000))
    }

    @Test
    fun aVaultZipLandsInDownloadsFinishedAndNotPending() {
        val source = File(context.cacheDir, "pfp_vault_${System.nanoTime()}.zip").apply {
            writeBytes(ByteArray(64 * 1024) { (it % 251).toByte() })
        }
        val name = "pfp_device_test_${System.nanoTime()}.zip"
        try {
            assertTrue(VaultZipPublisher(context).publish(source, name))
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING)
            val args = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, args, null)!!.use { c ->
                assertTrue("the zip row must exist", c.moveToFirst())
                inserted += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                assertEquals(source.length(), c.getLong(1))
                assertEquals("the zip must not stay pending", 0, c.getInt(2))
            }
        } finally {
            source.delete()
        }
    }
}
