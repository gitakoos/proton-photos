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

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.SdkDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A group of Drive operations that can run through the Drive SDK, each behind its own switch. */
enum class SdkRoute(val label: String, val key: Preferences.Key<Boolean>) {
    TAGS("tags", SettingsKeys.SDK_ROUTE_TAGS),
}

/**
 * Decides, per [SdkRoute], whether an operation goes through the Drive SDK or the app's own requests. A
 * route runs through the SDK only on a device [SdkGate] clears and only while its switch is on, and an SDK
 * call that fails runs the app's own path instead, so turning a route on can never lose an operation the
 * app would otherwise have done.
 */
@Singleton
class SdkRouting @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkGate: SdkGate,
) {
    /** Whether this device can run the SDK at all. */
    val isCapable: Boolean get() = sdkGate.isCapable

    /** The routes switched on, as stored. */
    val switchedOn: Flow<Set<SdkRoute>> = context.settingsDataStore.data.map { prefs ->
        SdkRoute.entries.filterTo(LinkedHashSet()) { prefs[it.key] == true }
    }

    suspend fun setSwitchedOn(route: SdkRoute, on: Boolean) {
        context.settingsDataStore.edit { it[route.key] = on }
    }

    suspend fun isOn(route: SdkRoute): Boolean =
        sdkGate.isCapable && context.settingsDataStore.data.first()[route.key] == true

    /**
     * Runs [viaSdk] when [route] is on, else [fallback]. A failed SDK call is counted and [fallback] runs
     * in its place; cancellation and fatal VM errors are not failures and pass through.
     */
    suspend fun <T> run(route: SdkRoute, viaSdk: suspend () -> T, fallback: suspend () -> T): T =
        runRouted(route.label, isOn(route), viaSdk, fallback)
}

/** [SdkRouting.run] with the switch already read, apart from Android so it is tested directly. */
internal suspend fun <T> runRouted(
    label: String,
    on: Boolean,
    viaSdk: suspend () -> T,
    fallback: suspend () -> T,
): T {
    if (!on) return fallback()
    val result = try {
        viaSdk()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        SdkDiagnostics.recordFallback(label, e)
        runCatching { Log.w("SdkRouting", "$label through the SDK failed, using the app's own path: ${e.javaClass.simpleName}") }
        return fallback()
    }
    SdkDiagnostics.recordSdk(label)
    return result
}
