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

package eu.akoos.photos.data.mlsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.entity.Author
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.Membership
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.ParentNodeUid
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * How [MlDriveStore] finds the Drive data folders: the nodes come in batches, My files is walked once for all three
 * areas, a found folder is remembered across a new store (an app start) and checked before it is used again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlDriveStoreFolderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val user = UserId("user-1")
    private val client = mockk<ProtonDriveClient>()
    private val provider = mockk<SdkDriveClientProvider>()
    private val nodes = HashMap<String, Node>()
    private val children = HashMap<String, List<String>>()
    private val author = mockk<Author>(relaxed = true)

    @Before
    fun setUp() {
        DriveFolderMemory(context).clearUser(user.id)
        coEvery { provider.withClient(any(), any<suspend (ProtonDriveClient) -> Any?>()) } coAnswers {
            secondArg<suspend (ProtonDriveClient) -> Any?>().invoke(client)
        }
        coEvery { client.getMyFilesFolder() } answers { nodes.getValue(MY_FILES) as FolderNode }
        // Every name is taken, so each lookup has to find the folder among the children.
        coEvery { client.getAvailableName(any(), any(), any()) } returns "taken"
        every { client.enumerateFolderChildrenNodeUids(any()) } answers {
            flowOf(*children[firstArg<NodeUid>().value].orEmpty().map { NodeUid(it) }.toTypedArray())
        }
        every { client.enumerateNodes(any()) } answers {
            flowOf(*firstArg<List<NodeUid>>().mapNotNull { nodes[it.value] }.toTypedArray())
        }
        coEvery { client.getNode(any()) } throws AssertionError("nodes are fetched in batches, never one by one")

        folder(MY_FILES, "root", "My files")
        val strangers = (1..250).map { "stranger-$it" }
        strangers.forEach { folder(it, MY_FILES, it) }
        folder(APP, MY_FILES, MlDriveNaming.APP_FOLDER_NAME)
        children[MY_FILES] = strangers + APP
        folder(ML, APP, DriveDataFolder.ML.folderName)
        folder(LABELS, APP, DriveDataFolder.LABELS.folderName)
        folder(HIDDEN, APP, DriveDataFolder.HIDDEN.folderName)
        children[APP] = listOf(ML, LABELS, HIDDEN)
    }

    @Test
    fun my_files_is_walked_once_for_all_three_areas() = runTest {
        val store = newStore()

        assertEquals(ML, store.folderUid(DriveDataFolder.ML))
        assertEquals(LABELS, store.folderUid(DriveDataFolder.LABELS))
        assertEquals(HIDDEN, store.folderUid(DriveDataFolder.HIDDEN))

        verify(exactly = 1) { client.enumerateFolderChildrenNodeUids(match { it.value == MY_FILES }) }
        coVerify(exactly = 0) { client.getNode(any()) }
    }

    @Test
    fun a_new_store_takes_the_remembered_folders_without_walking() = runTest {
        newStore().apply {
            folderUid(DriveDataFolder.ML)
            folderUid(DriveDataFolder.LABELS)
        }

        val restarted = newStore()
        assertEquals(LABELS, restarted.folderUid(DriveDataFolder.LABELS, create = false))
        assertEquals(emptyList<MlDriveFile>(), (restarted.list(user, DriveDataFolder.ML) as MlDriveResult.Ok).value)

        // Both lookups were made by the first store; the restarted one asked for neither My files nor its children.
        verify(exactly = 1) { client.enumerateFolderChildrenNodeUids(match { it.value == MY_FILES }) }
        coVerify(exactly = 2) { client.getMyFilesFolder() }
    }

    @Test
    fun a_moved_folder_is_looked_up_again_under_the_app_folder() = runTest {
        newStore().apply {
            folderUid(DriveDataFolder.ML)
            folderUid(DriveDataFolder.LABELS)
        }
        // The remembered ML folder went elsewhere; a new one sits in the app folder.
        folder(ML, "elsewhere", DriveDataFolder.ML.folderName)
        folder("ml-new", APP, DriveDataFolder.ML.folderName)
        children[APP] = listOf("ml-new", LABELS, HIDDEN)

        assertEquals("ml-new", newStore().folderUid(DriveDataFolder.ML))
        // The app folder came from the Labels record, so My files was not walked again.
        verify(exactly = 1) { client.enumerateFolderChildrenNodeUids(match { it.value == MY_FILES }) }
    }

    @Test
    fun signing_out_forgets_the_folders() = runTest {
        newStore().folderUid(DriveDataFolder.ML)
        DriveFolderMemory(context).clearUser(user.id)

        assertEquals(ML, newStore().folderUid(DriveDataFolder.ML))
        verify(exactly = 2) { client.enumerateFolderChildrenNodeUids(match { it.value == MY_FILES }) }
    }

    private fun newStore() = MlDriveStore(context, provider, mockk<ApiProvider>(), DriveFolderMemory(context))

    private suspend fun MlDriveStore.folderUid(area: DriveDataFolder, create: Boolean = true): String? =
        (folder(user, createIfMissing = create, area = area) as MlDriveResult.Ok).value?.nodeUid

    private fun folder(uid: String, parent: String, name: String) {
        nodes[uid] = FolderNode(
            NodeUid(uid),
            ParentNodeUid(parent),
            ScopeId("scope"),
            Result.success(name),
            Instant.EPOCH,
            null,
            Result.success(author),
            Result.success(author),
            OwnedBy("owner", ""),
            false,
            false,
            MemberRole.INHERITED,
            Membership(MemberRole.INHERITED, Instant.EPOCH, Result.success(author)),
            emptyList(),
            "",
        )
    }

    private companion object {
        const val MY_FILES = "my-files"
        const val APP = "app"
        const val ML = "ml"
        const val LABELS = "labels"
        const val HIDDEN = "hidden"
    }
}
