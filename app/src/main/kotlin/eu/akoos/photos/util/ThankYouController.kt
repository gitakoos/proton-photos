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

package eu.akoos.photos.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.preferences.setThanks250Seen
import eu.akoos.photos.data.preferences.thanks250Seen
import eu.akoos.photos.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide visibility of the one-time 2.5.0 thank-you popup. A @Singleton so the two view-models
 * that touch it, the About-screen preview trigger and the NavGraph host that renders the dialog, share
 * one piece of state: a preview raised from About shows over the dialog rendered at the nav root.
 *
 * On the first launch of the stable release the thank-you comes FIRST and What's New opens when it is
 * dismissed, so the greeting leads and the highlights follow. The real show happens once in the app's
 * lifetime: it is gated on the stable version name and a device-wide seen flag, and the flag is written
 * the moment it is shown, so it never returns. A preview bypasses the gate and never marks the flag.
 */
@Singleton
class ThankYouController @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // Whether dismissing the popup should open What's New next (the first-run order: thank-you first).
    @Volatile
    private var chainWhatsNew = false

    /** Whether the real one-time show is due: the stable 2.5.0 build, not shown before. */
    suspend fun shouldShow(): Boolean =
        BuildConfig.VERSION_NAME == "2.5.0" && !context.thanks250Seen.first()

    /**
     * Show the real one-time popup and mark it seen at once, so it appears a single time in the app's
     * lifetime and never returns, however it is closed. [thenWhatsNew] carries whether What's New is
     * still pending, so it opens after the thank-you is dismissed.
     */
    fun showReal(thenWhatsNew: Boolean) {
        chainWhatsNew = thenWhatsNew
        _visible.value = true
        appScope.launch { context.setThanks250Seen(true) }
    }

    /**
     * Show the popup for the owner's hidden preview: no gate, and it never marks the flag, so it can be
     * raised on any build. [thenWhatsNew] lets the preview also demonstrate the first-run order.
     */
    fun showPreview(thenWhatsNew: Boolean) {
        chainWhatsNew = thenWhatsNew
        _visible.value = true
    }

    /** Hide the popup, and report whether What's New should now open. */
    fun dismiss(): Boolean {
        _visible.value = false
        val chain = chainWhatsNew
        chainWhatsNew = false
        return chain
    }
}
