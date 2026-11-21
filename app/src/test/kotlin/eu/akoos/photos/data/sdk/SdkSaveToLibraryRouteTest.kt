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

import eu.akoos.photos.util.SdkDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeResultPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Saving photos of an album shared with this user into the own library: through the SDK when the route is
 * on, through the app's own copy when it is off, and through the app's own copy for every photo the SDK did
 * not copy.
 */
class SdkSaveToLibraryRouteTest {

    private val userId = UserId("u")
    private val label = SdkRoute.SAVE_TO_LIBRARY.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var provider: SdkDriveClientProvider

    @Before
    fun setUp() {
        SdkDiagnostics.clear()
        client = mockk()
        provider = mockk()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
    }

    private fun uid(linkId: String, volume: String = "owner-volume") = LegacyNodeUid(volumeId = volume, linkId = linkId)

    private fun failure(linkId: String, type: String, domain: ProtonSdkError.ErrorDomain = ProtonSdkError.ErrorDomain.Api) =
        NodeResultPair.Failure(
            uid(linkId),
            ProtonDriveSdkException("no", error = ProtonSdkError(message = "no", type = type, domain = domain)),
        )

    /** The app's own copy, recording which photos it was asked for; [failing] ones fail. */
    private class OwnCopy(private val failing: Set<String> = emptySet()) {
        val asked = mutableListOf<String>()
        suspend fun copy(linkId: String): Boolean {
            asked += linkId
            return linkId !in failing
        }
    }

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.SAVE_TO_LIBRARY.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.SAVE_TO_LIBRARY.defaultOn))
    }

    @Test
    fun `switched off, every photo goes through the app's own copy in order and the SDK is never asked`() = runTest {
        val own = OwnCopy(failing = setOf("b"))
        var sdkCalls = 0

        val counts = saveSharedPhotosRouted(listOf("a", "b", "c"), sdkOn = false, viaSdk = { _, _ -> sdkCalls++ }, copyOwnPath = own::copy)

        assertEquals(listOf("a", "b", "c"), own.asked)
        assertEquals(SaveToLibraryCounts(copied = 2, failed = 1, alreadyThere = 0), counts)
        assertEquals(0, sdkCalls)
        assertEquals(0, SdkDiagnostics.sdkCount(label))
        assertEquals(0, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `photos the SDK copied are never sent again`() = runTest {
        val own = OwnCopy()

        val counts = saveSharedPhotosRouted(
            listOf("a", "b"),
            sdkOn = true,
            viaSdk = { ids, onOutcome -> ids.forEach { onOutcome(it, SdkSaveOutcome.COPIED) } },
            copyOwnPath = own::copy,
        )

        assertTrue(own.asked.isEmpty())
        assertEquals(SaveToLibraryCounts(copied = 2, failed = 0, alreadyThere = 0), counts)
        assertEquals(1, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `a failed SDK call sends every photo it had not answered through the app's own copy`() = runTest {
        val own = OwnCopy()

        val counts = saveSharedPhotosRouted(
            listOf("a", "b", "c"),
            sdkOn = true,
            viaSdk = { _, onOutcome ->
                onOutcome("a", SdkSaveOutcome.COPIED)
                throw ProtonDriveSdkException("transport")
            },
            copyOwnPath = own::copy,
        )

        assertEquals(listOf("b", "c"), own.asked)
        assertEquals(SaveToLibraryCounts(copied = 3, failed = 0, alreadyThere = 0), counts)
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a photo the SDK did not copy goes through the app's own copy, one already there does not`() = runTest {
        val own = OwnCopy(failing = setOf("refused"))

        val counts = saveSharedPhotosRouted(
            listOf("ok", "refused", "there", "unanswered"),
            sdkOn = true,
            viaSdk = { _, onOutcome ->
                onOutcome("ok", SdkSaveOutcome.COPIED)
                onOutcome("refused", SdkSaveOutcome.NOT_DONE)
                onOutcome("there", SdkSaveOutcome.ALREADY_THERE)
            },
            copyOwnPath = own::copy,
        )

        assertEquals(listOf("refused", "unanswered"), own.asked)
        assertEquals(SaveToLibraryCounts(copied = 2, failed = 1, alreadyThere = 1), counts)
    }

    @Test
    fun `cancellation stops the save without the app's own copy`() = runTest {
        val own = OwnCopy()
        try {
            saveSharedPhotosRouted(
                listOf("a"),
                sdkOn = true,
                viaSdk = { _, _ -> throw CancellationException("stop") },
                copyOwnPath = own::copy,
            )
            fail("cancellation must pass through")
        } catch (_: CancellationException) {
        }
        assertTrue(own.asked.isEmpty())
    }

    @Test
    fun `nothing to save asks neither path`() = runTest {
        val own = OwnCopy()
        var sdkCalls = 0
        val counts = saveSharedPhotosRouted(emptyList(), sdkOn = true, viaSdk = { _, _ -> sdkCalls++ }, copyOwnPath = own::copy)
        assertEquals(SaveToLibraryCounts(0, 0, 0), counts)
        assertEquals(0, sdkCalls)
        assertTrue(own.asked.isEmpty())
    }

    @Test
    fun `the SDK's answers map to copied, already there, or left for the app's own copy`() {
        assertEquals(SdkSaveOutcome.COPIED, saveOutcomeOf(NodeResultPair.Success(uid("a"))))
        assertEquals(SdkSaveOutcome.ALREADY_THERE, saveOutcomeOf(failure("a", "PhotoAlreadyInTargetException")))
        assertEquals(SdkSaveOutcome.NOT_DONE, saveOutcomeOf(failure("a", "ProtonApiException")))
        assertEquals(
            SdkSaveOutcome.NOT_DONE,
            saveOutcomeOf(failure("a", "InvalidOperationException", ProtonSdkError.ErrorDomain.BusinessLogic)),
        )
    }

    @Test
    fun `the SDK is asked for the photos on the shared album's volume and each answer is handed on`() = runTest {
        every { client.savePhotosToTimeline(listOf(uid("p1"), uid("p2"))) } returns
            flowOf(NodeResultPair.Success(uid("p1")), failure("p2", "ProtonApiException"))
        val answers = mutableListOf<Pair<String, SdkSaveOutcome>>()

        SdkSharedOps(provider).saveToTimeline(userId, "owner-volume", listOf("p1", "p2")) { id, outcome -> answers += id to outcome }

        assertEquals(listOf("p1" to SdkSaveOutcome.COPIED, "p2" to SdkSaveOutcome.NOT_DONE), answers)
        verify(exactly = 1) { client.savePhotosToTimeline(listOf(uid("p1"), uid("p2"))) }
    }

    @Test
    fun `an SDK save that fails partway keeps the answers it gave before failing`() = runTest {
        every { client.savePhotosToTimeline(any()) } returns flow {
            emit(NodeResultPair.Success(uid("p1")))
            throw ProtonDriveSdkException("transport")
        }
        val ops = SdkSharedOps(provider)
        val own = OwnCopy()

        val counts = saveSharedPhotosRouted(
            listOf("p1", "p2"),
            sdkOn = true,
            viaSdk = { ids, onOutcome -> ops.saveToTimeline(userId, "owner-volume", ids, onOutcome) },
            copyOwnPath = own::copy,
        )

        assertEquals(listOf("p2"), own.asked)
        assertEquals(SaveToLibraryCounts(copied = 2, failed = 0, alreadyThere = 0), counts)
    }
}
