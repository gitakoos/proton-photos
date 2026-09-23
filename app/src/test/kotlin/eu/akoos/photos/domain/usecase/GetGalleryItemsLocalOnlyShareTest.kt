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

import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guest feed is merged once for every surface that reads it, as the signed-in feed is: a
 * timeline, search, map and people screen open together must not each merge the whole device
 * library into a list of their own.
 *
 * The merge runs on the real Default dispatcher, so this waits in real time, bounded by a timeout.
 */
class GetGalleryItemsLocalOnlyShareTest {

    private fun localItem(uri: String, dateTaken: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = "photo_$uri.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    @Test
    fun `guest collectors share one merge of one device list`() = runBlocking {
        val subscriptions = AtomicInteger()
        val device = MutableStateFlow(listOf(localItem("uri://old", 1_000L), localItem("uri://new", 9_000L)))
        val localRepo = mockk<LocalMediaRepository>()
        every { localRepo.observeLocalMedia() } returns device.onStart { subscriptions.incrementAndGet() }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val useCase = GetGalleryItemsUseCase(
                localRepo,
                mockk<DrivePhotoRepository>(),
                mockk<SyncStateSnapshot>(),
                appScope,
            )
            assertSame("every guest surface must get the one feed", useCase.invokeLocalOnly(), useCase.invokeLocalOnly())

            val timeline = async { useCase.invokeLocalOnly().first() }
            val search = async { useCase.invokeLocalOnly().first() }
            val a = withTimeout(10_000) { timeline.await() }
            val b = withTimeout(10_000) { search.await() }

            assertSame("both collectors must receive the one merged list", a, b)
            assertEquals(
                listOf("uri://new", "uri://old"),
                a.filterIsInstance<GalleryItem.LocalOnly>().map { it.local.uri },
            )
            assertEquals("the device list must be subscribed once for every guest collector", 1, subscriptions.get())
            verify(exactly = 1) { localRepo.observeLocalMedia() }
        } finally {
            appScope.cancel()
        }
    }
}
