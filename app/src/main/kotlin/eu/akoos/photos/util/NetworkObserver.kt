/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for network reachability. Hot StateFlow that ViewModels gate
 * cloud calls on. Initial value derived synchronously so cold-start reads in init {}
 * blocks get a correct answer before any callback fires. "Online" requires both
 * NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED, so a captive-portal Wi-Fi
 * counts as offline.
 */
@Singleton
class NetworkObserver @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val cm: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /**
     * Whether the active network is reported as unmetered by the system. Wi-Fi and Ethernet
     * default to unmetered; cellular is metered unless the carrier explicitly flags an
     * unlimited plan. Mirrors WorkManager's `NetworkType.UNMETERED` constraint so the
     * viewer's Wi-Fi-only-for-fullres preference behaves consistently with sync's
     * Wi-Fi-only setting.
     */
    private val _isUnmetered = MutableStateFlow(currentlyUnmetered())
    val isUnmetered: StateFlow<Boolean> = _isUnmetered.asStateFlow()

    init {
        cm?.let { manager ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { refresh() }
                override fun onLost(network: Network) { refresh() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
            }
            try { manager.registerNetworkCallback(request, callback) }
            catch (e: Exception) { Log.w(TAG, "registerNetworkCallback failed: ${e.message}", e) }
        }
    }

    private fun refresh() {
        _isOnline.value = currentlyOnline()
        _isUnmetered.value = currentlyUnmetered()
    }

    private fun currentlyOnline(): Boolean {
        val manager = cm ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun currentlyUnmetered(): Boolean {
        val manager = cm ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object { private const val TAG = "NetworkObserver" }
}
