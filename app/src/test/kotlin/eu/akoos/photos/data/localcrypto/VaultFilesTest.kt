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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Writing into the vault: an edit is sealed whole or not at all, even when two of them race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        VaultFiles.vaultDir(context).deleteRecursively()
        VaultFiles.stagingDir(context).deleteRecursively()
        File(context.cacheDir, "vt").deleteRecursively()
    }

    @Test
    fun `two edits of the same vault photo racing each other never leave a partial file`() {
        val name = "race.jpg"
        val work = File(context.cacheDir, "vt").apply { mkdirs() }
        val a = File(work, "a.bin").apply { writeBytes(ByteArray(300_000) { 1 }) }
        val b = File(work, "b.bin").apply { writeBytes(ByteArray(200_000) { 2 }) }
        VaultFiles.sealInto(context, a, name, LocalFileMeta())
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val writers = listOf(a, b).map { source ->
            thread {
                start.await()
                repeat(40) {
                    runCatching { VaultFiles.sealInto(context, source, name, LocalFileMeta(), requireExisting = true) }
                        .onFailure { failure.compareAndSet(null, it) }
                }
            }
        }
        start.countDown()
        writers.forEach { it.join() }

        assertEquals(null, failure.get())
        val vault = File(VaultFiles.vaultDir(context), name).readBytes()
        assertTrue(vault.contentEquals(a.readBytes()) || vault.contentEquals(b.readBytes()))
        assertTrue(VaultFiles.stagingDir(context).listFiles().isNullOrEmpty())
    }
}
