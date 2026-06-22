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
 * blocks get a correct answer before any callback fires.
 *
 * "Online" requires only `NET_CAPABILITY_INTERNET` on any attached network — we
 * deliberately do NOT gate on `NET_CAPABILITY_VALIDATED`. The validated capability
 * stays false on networks where Android's captive-portal probe endpoint
 * (gstatic.com / samsung.com) is unreachable, even though Proton's transport is
 * perfectly usable; gating on it would lock the offline indicator on for those
 * users. We also enumerate `allNetworks` rather than reading `activeNetwork` so
 * the indicator doesn't flicker during a wifi-to-cellular handover when the
 * active assignment is momentarily null.
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

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
    }

    init {
        cm?.let { manager ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try { manager.registerNetworkCallback(request, callback) }
            catch (e: Exception) { Log.w(TAG, "registerNetworkCallback failed: ${e.message}", e) }
        }
    }

    /**
     * Unregister the system network callback. Idempotent — re-calls after the OS has
     * already torn the registration down are swallowed via runCatching. Not auto-wired
     * to any production lifecycle hook (Application.onTerminate only fires in emulators);
     * the singleton's registration is intended to live as long as the process. Exposed
     * for instrumented tests and any future deliberate teardown path.
     */
    fun release() {
        val manager = cm ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        _isOnline.value = currentlyOnline()
        _isUnmetered.value = currentlyUnmetered()
    }

    private fun currentlyOnline(): Boolean {
        val manager = cm ?: return false
        // Enumerate ALL attached networks rather than just `activeNetwork`. During a
        // wifi → cellular handover, activeNetwork briefly returns null even though
        // one or both networks are still attached — leading the avatar dot to flicker
        // between offline/online. allNetworks is stable across the transition.
        // We only require NET_CAPABILITY_INTERNET (not VALIDATED), because the
        // VALIDATED gate would never flip true on captive-portal-blocked networks
        // even though Proton's transport works fine.
        return manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun currentlyUnmetered(): Boolean {
        val manager = cm ?: return false
        // Enumerate ALL attached networks like currentlyOnline(), NOT `activeNetwork`: the active
        // assignment is briefly null at cold start and during a wifi handover, which wrongly
        // reported metered (false) on a perfectly good Wi-Fi link and nagged the viewer to
        // "connect to Wi-Fi" while already on it. An attached unmetered internet network means big
        // downloads route over it.
        return manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    /**
     * Whether an attached internet network rides the Wi-Fi transport, regardless of whether the
     * system flags it metered. Wider than [currentlyUnmetered]: a phone hotspot or a router that
     * advertises itself as metered still counts as Wi-Fi here, so the "sync only on Wi-Fi" setting
     * allows those while still blocking cellular. Same allNetworks enumeration + null/exception
     * safety as the other probes.
     */
    fun currentlyOnWifi(): Boolean {
        val manager = cm ?: return false
        return manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    companion object { private const val TAG = "NetworkObserver" }
}
