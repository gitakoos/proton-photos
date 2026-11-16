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
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.SdkDiagnostics
import eu.akoos.photos.util.sanitizeErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.SdkHttpClientException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by an SDK call that failed without knowing whether its change reached Drive. The app's own path
 * must not run in its place, since it could do the change a second time; the caller fails and tries again
 * later, once the change shows up on Drive or is known to be missing.
 */
class SdkOutcomeUnknownException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

/**
 * A group of Drive operations that can run through the Drive SDK, each behind its own switch. [defaultOn] is
 * the switch's state until the user touches it: a route still being tried out starts off.
 *
 * @property releaseAllowed false keeps the route off in a release build whatever its stored switch says, and
 *   leaves its switch off the SDK settings page there; a debug build still shows and runs it. Drive SDK
 *   0.33.0 returns every time value (capture time, album last activity) shifted by the phone's UTC offset,
 *   which the timeline, album list and album photos routes would read; saving to the library has not been
 *   tried on a device yet.
 */
enum class SdkRoute(
    val label: String,
    val key: Preferences.Key<Boolean>,
    val defaultOn: Boolean = true,
    val releaseAllowed: Boolean = true,
) {
    TAGS("tags", SettingsKeys.SDK_ROUTE_TAGS),
    TRASH("trash", SettingsKeys.SDK_ROUTE_TRASH),
    DOWNLOAD("download", SettingsKeys.SDK_ROUTE_DOWNLOAD),
    UPLOAD("upload", SettingsKeys.SDK_ROUTE_UPLOAD),
    ALBUMS("albums", SettingsKeys.SDK_ROUTE_ALBUMS),
    EVENTS("events", SettingsKeys.SDK_ROUTE_EVENTS),
    SHARED("shared", SettingsKeys.SDK_ROUTE_SHARED),
    SHARED_BY_ME("shared-by-me", SettingsKeys.SDK_ROUTE_SHARED_BY_ME, defaultOn = false),
    SAVE_TO_LIBRARY("save-to-library", SettingsKeys.SDK_ROUTE_SAVE_TO_LIBRARY, defaultOn = false, releaseAllowed = false),
    TIMELINE("timeline", SettingsKeys.SDK_ROUTE_TIMELINE, defaultOn = false, releaseAllowed = false),
    ALBUM_LIST("album-list", SettingsKeys.SDK_ROUTE_ALBUM_LIST, defaultOn = false, releaseAllowed = false),
    ALBUM_PHOTOS("album-photos", SettingsKeys.SDK_ROUTE_ALBUM_PHOTOS, defaultOn = false, releaseAllowed = false),
    THUMBNAILS("thumbnails", SettingsKeys.SDK_ROUTE_THUMBNAILS, defaultOn = false),
}

/**
 * Decides, per [SdkRoute], whether an operation goes through the Drive SDK or the app's own requests. A
 * route runs through the SDK only on a device [SdkGate] clears and only while its switch is on (it is on
 * until the user switches it off), and an SDK call that fails runs the app's own path instead, so turning a
 * route on can never lose an operation the app would otherwise have done.
 */
@Singleton
class SdkRouting @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkGate: SdkGate,
) {
    /** Whether this device can run the SDK at all. */
    val isCapable: Boolean get() = sdkGate.isCapable

    /** The routes switched on, each by its stored switch or else its default ([routeOn]). */
    val switchedOn: Flow<Set<SdkRoute>> = context.settingsDataStore.data.map { prefs ->
        SdkRoute.entries.filterTo(LinkedHashSet()) { routeOn(it, prefs[it.key], BuildConfig.DEBUG) }
    }

    suspend fun setSwitchedOn(route: SdkRoute, on: Boolean) {
        context.settingsDataStore.edit { it[route.key] = on }
    }

    suspend fun isOn(route: SdkRoute): Boolean =
        sdkGate.isCapable && routeOn(route, context.settingsDataStore.data.first()[route.key], BuildConfig.DEBUG)

    /**
     * Runs [viaSdk] when [route] is on, else [fallback]. A failed SDK call is counted and [fallback] runs
     * in its place; cancellation and fatal VM errors are not failures and pass through, and an
     * [SdkOutcomeUnknownException] is counted and passes through without [fallback]. [on] false keeps an
     * operation the SDK would handle differently on the app's own path whatever the switch says.
     */
    suspend fun <T> run(route: SdkRoute, viaSdk: suspend () -> T, on: Boolean = true, fallback: suspend () -> T): T =
        runRouted(route.label, on && isOn(route), viaSdk, fallback)
}

/** A route's switch as stored, or [defaultOn] while the user has never touched it: a route on by default
 *  stays on until switched off, and one off by default stays off until switched on. */
internal fun routeSwitchedOn(stored: Boolean?, defaultOn: Boolean = true): Boolean = stored ?: defaultOn

/** Whether [route] may run at all in this kind of build: always in a debug build, in a release build only
 *  with [SdkRoute.releaseAllowed]. */
internal fun routeAvailable(route: SdkRoute, debug: Boolean): Boolean = debug || route.releaseAllowed

/** [route]'s switch ([routeSwitchedOn]), held off in a build where the route is not [routeAvailable]. */
internal fun routeOn(route: SdkRoute, stored: Boolean?, debug: Boolean): Boolean =
    routeAvailable(route, debug) && routeSwitchedOn(stored, route.defaultOn)

/** The routes whose switch the SDK settings page shows in this kind of build, in their order. */
internal fun routesShown(debug: Boolean): List<SdkRoute> = SdkRoute.entries.filter { routeAvailable(it, debug) }

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
    } catch (e: SdkOutcomeUnknownException) {
        SdkDiagnostics.recordFallback(label, describeSdkFailure(e))
        throw e
    } catch (e: Throwable) {
        val cause = describeSdkFailure(e)
        SdkDiagnostics.recordFallback(label, cause)
        runCatching { Log.w("SdkRouting", "$label through the SDK failed, using the app's own path: $cause") }
        return fallback()
    }
    SdkDiagnostics.recordSdk(label)
    return result
}

/**
 * Why an SDK call failed, for the diagnostics: each exception of the cause chain (at most [MAX_CAUSES]) by
 * class, with the HTTP status and Proton code of an HTTP failure, the domain and codes of an SDK error, and a
 * short redacted message ([sanitizeErrorMessage] takes out names, paths, addresses and ids), so a fallback's
 * cause shows in a release build without anything personal.
 */
internal fun describeSdkFailure(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    val seen = HashSet<Throwable>()
    while (current != null && depth < MAX_CAUSES && seen.add(current)) {
        if (depth > 0) append(" <- ")
        append(current.javaClass.simpleName.ifEmpty { current.javaClass.name.substringAfterLast('.') })
        var message = current.message
        when (current) {
            is SdkHttpClientException.Http -> {
                append(" http=").append(current.statusCode)
                current.protonCode?.let { append(" code=").append(it) }
            }
            is ProtonDriveSdkException -> current.error?.let { sdk ->
                append(" domain=").append(sdk.domain)
                sdk.primaryCode?.let { append(" code=").append(it) }
                sdk.secondaryCode?.let { append('/').append(it) }
                if (message.isNullOrBlank()) message = sdk.message
            }
        }
        if (!message.isNullOrBlank()) append(": ").append(sanitizeErrorMessage(message).take(MAX_MESSAGE_CHARS))
        current = current.cause
        depth++
    }
}

private const val MAX_CAUSES = 3
private const val MAX_MESSAGE_CHARS = 80
