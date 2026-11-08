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

package eu.akoos.photos.presentation.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.R
import eu.akoos.photos.data.share.backUpSharedMedia
import eu.akoos.photos.data.share.sharedUrisOf
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * The share sheet's "Back up to Drive": photos and videos another app shares are queued on the same
 * explicit back-up the selection's Back up action uses, then the screen closes. One the phone does not
 * hold yet (from a browser or a chat app) is saved into the camera folder first. It shows nothing of
 * its own, so the app lock has nothing to cover; the outcome is a toast.
 */
@AndroidEntryPoint
class ShareBackupActivity : ComponentActivity() {

    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var forceUpload: ForceUploadLocalUrisUseCase
    @Inject lateinit var syncStateRepo: SyncStateRepository
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val shared = sharedUrisOf(intent)
        if (shared.isEmpty()) { finish(); return }
        lifecycleScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                toast(getString(R.string.share_backup_sign_in))
                finish()
                return@launch
            }
            // Matched and copied while the share lasts: the sender's read grant ends with this screen.
            val message = backUpSharedMedia(
                this@ShareBackupActivity, userId, shared, intent.type, syncStateRepo, forceUpload, appScope,
            )
            toast(message)
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
}
