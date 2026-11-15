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

package eu.akoos.photos.data.sdk

import eu.akoos.photos.data.repository.drive.cachedThumbnailFile
import eu.akoos.photos.util.SdkDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.FileThumbnail
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.ThumbnailType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A photo's thumbnail through the SDK, or the app's own decrypt of the stored url, into the same file. */
class SdkThumbnailsRouteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val userId = UserId("u")
    private val label = SdkRoute.THUMBNAILS.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var ops: SdkListingOps
    private lateinit var cacheDir: File

    /** The decrypted thumbnail the app's own path writes for the fixture photo. */
    private val ownBytes = byteArrayOf(-1, -40, -1, 0, 1, 2, 3)

    @Before
    fun setUp() {
        SdkDiagnostics.clear()
        cacheDir = tmp.newFolder("thumbnails")
        client = mockk()
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
        ops = SdkListingOps(provider)
        every { client.enumerateThumbnails(any(), ThumbnailType.THUMBNAIL) } returns
            flowOf(FileThumbnail(LegacyNodeUid("v", "p1"), Result.success(ownBytes.copyOf())))
    }

    private fun ownPath(): String {
        val f = File(cacheDir, "thumb_p1.jpg").apply { writeBytes(ownBytes) }
        return "file://${f.absolutePath}"
    }

    private suspend fun route(on: Boolean): String = runRouted(
        label,
        on,
        viaSdk = { cachedThumbnailFile(cacheDir, "thumb_p1.jpg", "p1") { ops.thumbnail(userId, "v", "p1", ThumbnailType.THUMBNAIL) } },
        fallback = { ownPath() },
    )

    private fun leftoverTemps() = cacheDir.listFiles().orEmpty().filter { it.name.startsWith("thumb_dec_") }

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.THUMBNAILS.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.THUMBNAILS.defaultOn))
    }

    @Test
    fun `switched off, the app's own decrypt answers and the SDK is never asked`() = runTest {
        route(on = false)
        verify(exactly = 0) { client.enumerateThumbnails(any(), any()) }
        assertEquals(0, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `the SDK thumbnail lands in the app's own file with the app's own bytes`() = runTest {
        val viaSdk = route(on = true)

        assertEquals("file://${File(cacheDir, "thumb_p1.jpg").absolutePath}", viaSdk)
        assertArrayEquals(ownBytes, File(cacheDir, "thumb_p1.jpg").readBytes())
        assertEquals(emptyList<File>(), leftoverTemps())
        assertEquals(1, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `a thumbnail already on disk answers without asking the SDK`() = runTest {
        File(cacheDir, "thumb_p1.jpg").writeBytes(ownBytes)

        route(on = true)
        verify(exactly = 0) { client.enumerateThumbnails(any(), any()) }
    }

    @Test
    fun `a failed SDK call falls back to the app's own decrypt`() = runTest {
        every { client.enumerateThumbnails(any(), any()) } throws IllegalStateException("boom")

        val result = route(on = true)
        assertEquals("file://${File(cacheDir, "thumb_p1.jpg").absolutePath}", result)
        assertArrayEquals(ownBytes, File(cacheDir, "thumb_p1.jpg").readBytes())
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a thumbnail the SDK could not give falls back to the app's own decrypt and leaves no file`() = runTest {
        every { client.enumerateThumbnails(any(), any()) } returns
            flowOf(FileThumbnail(LegacyNodeUid("v", "p1"), Result.failure(IllegalStateException("no thumbnail"))))
        var fellBack = false

        runRouted(
            label,
            on = true,
            viaSdk = { cachedThumbnailFile(cacheDir, "thumb_p1.jpg", "p1") { ops.thumbnail(userId, "v", "p1", ThumbnailType.THUMBNAIL) } },
            fallback = { fellBack = true; "" },
        )
        assertEquals(true, fellBack)
        assertFalse(File(cacheDir, "thumb_p1.jpg").exists())
        assertEquals(emptyList<File>(), leftoverTemps())
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `the HD thumbnail is asked as the SDK's preview`() = runTest {
        every { client.enumerateThumbnails(any(), ThumbnailType.PREVIEW) } returns
            flowOf(FileThumbnail(LegacyNodeUid("v", "p1"), Result.success(ownBytes.copyOf())))

        cachedThumbnailFile(cacheDir, "thumb_hd_p1.jpg", "p1") { ops.thumbnail(userId, "v", "p1", ThumbnailType.PREVIEW) }
        assertArrayEquals(ownBytes, File(cacheDir, "thumb_hd_p1.jpg").readBytes())
        verify(exactly = 0) { client.enumerateThumbnails(any(), ThumbnailType.THUMBNAIL) }
    }
}
