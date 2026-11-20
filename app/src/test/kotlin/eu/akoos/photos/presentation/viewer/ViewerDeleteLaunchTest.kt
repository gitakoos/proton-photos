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

package eu.akoos.photos.presentation.viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewerDeleteLaunchTest {

    @Test
    fun `a delete still waiting finishes after the viewer closes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewerScope = CoroutineScope(Job() + dispatcher)
        val trashReply = CompletableDeferred<Unit>()
        var deleted = false

        val job = appScope.launchViewerDelete(dispatcher) {
            trashReply.await()
            deleted = true
        }
        advanceUntilIdle()
        viewerScope.cancel()
        trashReply.complete(Unit)
        advanceUntilIdle()

        assertTrue(deleted)
        assertTrue(job.isCompleted)
        appScope.cancel()
    }

    @Test
    fun `the viewer delete runs in the app scope`() {
        val body = viewerFunctionBody("deleteItem")

        assertTrue(body.contains("appScope.launchViewerDelete"))
        assertFalse(body.contains("viewModelScope.launch"))
    }

    @Test
    fun `the delete finished after the system consent runs in the app scope`() {
        val body = viewerFunctionBody("onDeletePermissionGranted")

        assertTrue(body.contains("appScope.launchViewerDelete"))
        assertFalse(body.contains("viewModelScope.launch"))
    }

    private fun viewerFunctionBody(name: String): String {
        val source = File("src/main/kotlin/eu/akoos/photos/presentation/viewer/PhotoViewerViewModel.kt").readText()
        val start = source.indexOf("fun $name(")
        val next = source.indexOf("\n    fun ", start + 1).takeIf { it > 0 } ?: source.length
        return source.substring(start, next)
    }
}
