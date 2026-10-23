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
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.di.BaseProtonApiUrl
import me.proton.core.network.domain.session.SessionProvider
import me.proton.core.user.domain.repository.UserAddressRepository
import me.proton.drive.sdk.CorePublicAddressResolver
import me.proton.drive.sdk.CoreUserAddressResolver
import me.proton.drive.sdk.LoggerProvider
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.ProtonDriveSdk
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.ProtonSdkClient
import me.proton.drive.sdk.entity.ClientCreateRequest
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown by [SdkDriveClientProvider.withClient] for an account whose client is closed: it is signing out. */
class SdkClientClosedException : IllegalStateException("Drive client closed")

/**
 * Builds and caches the Proton Drive SDK clients per user: the My files [ProtonDriveClient] and the
 * Photos [ProtonPhotosClient], each made on first use. The auth
 * dependencies come from the sign-in stack already in the graph, and the address resolvers ship inside
 * the SDK, so no extra Proton wiring is needed.
 *
 * Every call runs through [withClient] or [withPhotosClient], which count it as in flight, so a sign-out
 * never frees a native client under a call: [close] refuses the account from then on, and [remove] waits
 * for the calls still running before it closes each client, or leaves the close to the last of them.
 *
 * The SDK's native library loads the first time [ProtonDriveSdk] is touched, which happens only in
 * [create] and only once [SdkGate] has cleared the device. Injecting this provider never loads it.
 */
@Singleton
class SdkDriveClientProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkGate: SdkGate,
    private val apiProvider: ApiProvider,
    private val cryptoContext: CryptoContext,
    private val userAddressRepository: UserAddressRepository,
    private val publicAddressRepository: PublicAddressRepository,
    private val sessionProvider: SessionProvider,
    @BaseProtonApiUrl private val baseApiUrl: HttpUrl,
) {
    private val mutex = Mutex()
    private val clients = mutableMapOf<Pair<UserId, ClientKind>, HeldClient>()

    /** The accounts whose client is closed, each with the session it was closed under, null when it had none. */
    private val closed = mutableMapOf<UserId, String?>()

    /**
     * Runs [block] with [userId]'s client, created on first use, or returns null on a device [SdkGate]
     * rules out. The call counts as in flight until [block] returns, so [remove] never closes the client
     * under it. Throws [SdkClientClosedException] for an account whose client is closed ([close]).
     */
    suspend fun <T> withClient(userId: UserId, block: suspend (ProtonDriveClient) -> T): T? =
        withHeld(userId, ClientKind.DRIVE) { block(it as ProtonDriveClient) }

    /** [withClient] for [userId]'s Photos client, the one that reads and writes the photo library. */
    suspend fun <T> withPhotosClient(userId: UserId, block: suspend (ProtonPhotosClient) -> T): T? =
        withHeld(userId, ClientKind.PHOTOS) { block(it as ProtonPhotosClient) }

    private suspend fun <T> withHeld(userId: UserId, kind: ClientKind, block: suspend (ProtonSdkClient) -> T): T? {
        if (!sdkGate.isCapable) return null
        val held = getOrCreate(userId, kind)
        try {
            return block(held.client)
        } finally {
            withContext(NonCancellable) { release(held) }
        }
    }

    /**
     * The client of [userId] with one more call counted on it, created when there is none yet. Refuses an
     * account whose client is closed, so nothing makes a new client for an account that is signing out.
     */
    private suspend fun getOrCreate(userId: UserId, kind: ClientKind): HeldClient = mutex.withLock {
        if (isClosedLocked(userId)) throw SdkClientClosedException()
        val key = userId to kind
        val held = clients[key] ?: create(userId, kind).also { clients[key] = it }
        // A retired client leaves the map in the same step, so one found here always takes the call.
        if (!held.lease.enter()) throw SdkClientClosedException()
        held
    }

    private suspend fun release(held: HeldClient) {
        val closeNow = mutex.withLock { held.lease.exit() }
        if (closeNow) shutDown(held)
    }

    private suspend fun create(userId: UserId, kind: ClientKind): HeldClient {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val request = ClientCreateRequest(
                baseUrl = baseApiUrl.toString(),
                loggerProvider = loggerProvider(),
                bindingsLanguage = "kotlin",
                uid = sdkClientUid(installClientUid(), userId.id),
            )
            val userAddressResolver = CoreUserAddressResolver(
                userId = userId,
                cryptoContext = cryptoContext,
                userAddressRepository = userAddressRepository,
            )
            val publicAddressResolver = CorePublicAddressResolver(
                userId = userId,
                publicAddressRepository = publicAddressRepository,
            )
            val client: ProtonSdkClient = when (kind) {
                ClientKind.DRIVE -> ProtonDriveSdk.protonDriveClientCreate(
                    coroutineScope = scope,
                    userId = userId,
                    apiProvider = apiProvider,
                    request = request,
                    userAddressResolver = userAddressResolver,
                    publicAddressResolver = publicAddressResolver,
                )
                ClientKind.PHOTOS -> ProtonDriveSdk.protonPhotosClientCreate(
                    coroutineScope = scope,
                    userId = userId,
                    apiProvider = apiProvider,
                    request = request,
                    userAddressResolver = userAddressResolver,
                    publicAddressResolver = publicAddressResolver,
                )
            }
            return HeldClient(client, scope)
        } catch (t: Throwable) {
            scope.cancel()
            throw t
        }
    }

    /**
     * The SDK's log lines, in a debug build only. A release build installs a logger that drops every line,
     * as a line can carry names and ids from the account's Drive.
     */
    private suspend fun loggerProvider(): LoggerProvider =
        if (BuildConfig.DEBUG) {
            ProtonDriveSdk.loggerProvider { level, category, message ->
                Log.println(level.toAndroidPriority(), "DriveSdk/$category", message)
            }
        } else {
            ProtonDriveSdk.loggerProvider { _, _, _ -> }
        }

    /**
     * This install's own random id ([SettingsKeys.DRIVE_SDK_CLIENT_UID]), created on first use and kept
     * across restarts. The SDK never sees it: each account's client gets the id [sdkClientUid] derives.
     */
    private suspend fun installClientUid(): String {
        val store = context.settingsDataStore
        store.data.first()[SettingsKeys.DRIVE_SDK_CLIENT_UID]?.let { return it }
        var uid = UUID.randomUUID().toString()
        store.edit { prefs ->
            val existing = prefs[SettingsKeys.DRIVE_SDK_CLIENT_UID]
            if (existing != null) uid = existing else prefs[SettingsKeys.DRIVE_SDK_CLIENT_UID] = uid
        }
        return uid
    }

    /**
     * Closes [userId]'s client to new calls, the first step of a sign-out: [withClient] refuses the account
     * from here on, until a sign-in gives it a session other than the one it has now. Calls already running
     * carry on; [remove] waits for them.
     */
    suspend fun close(userId: UserId) {
        val session = currentSession(userId)
        mutex.withLock {
            // A mark left from an earlier sign-out, with a newer session since, is replaced by this one.
            if (userId !in closed || (session != null && session != closed[userId])) closed[userId] = session
        }
    }

    /** Whether [userId]'s client is closed ([close]): the account is signing out, or signed out and not back. */
    suspend fun isClosed(userId: UserId): Boolean = mutex.withLock { isClosedLocked(userId) }

    private suspend fun isClosedLocked(userId: UserId): Boolean {
        if (userId !in closed) return false
        if (clientStaysClosed(closedSession = closed[userId], currentSession = currentSession(userId))) return true
        closed.remove(userId)
        return false
    }

    /** The account's session id, or null when it has none or it cannot be read, which keeps a closed client closed. */
    private suspend fun currentSession(userId: UserId): String? = try {
        sessionProvider.getSessionId(userId)?.id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "session unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Closes and drops [userId]'s clients and their scopes, so a later sign-in never reuses them, after
     * closing the account to new calls ([close]). A call still running keeps its client: this waits for
     * such calls, at most [IN_FLIGHT_WAIT_MS] in all, and a client still in use by then is closed by the
     * last call out, never under one.
     */
    suspend fun remove(userId: UserId) {
        close(userId)
        val retired = mutex.withLock {
            ClientKind.entries.mapNotNull { kind ->
                val held = clients.remove(userId to kind) ?: return@mapNotNull null
                held to held.lease.retire()
            }
        }
        val busy = retired.mapNotNull { (held, closeNow) ->
            if (closeNow) {
                shutDown(held)
                null
            } else {
                held
            }
        }
        if (busy.isEmpty()) return
        val idle = withTimeoutOrNull(IN_FLIGHT_WAIT_MS) { busy.forEach { held -> held.lease.idle.first { it } } }
        if (idle == null) Log.w(TAG, "Drive client still in use, its last call closes it")
    }

    private fun shutDown(held: HeldClient) {
        try {
            held.client.close()
        } catch (e: Exception) {
            Log.w(TAG, "Drive client close failed: ${e.javaClass.simpleName}")
        } finally {
            held.scope.cancel()
        }
    }

    private fun LoggerProvider.Level.toAndroidPriority(): Int = when (this) {
        LoggerProvider.Level.VERBOSE -> Log.VERBOSE
        LoggerProvider.Level.DEBUG -> Log.DEBUG
        LoggerProvider.Level.INFO -> Log.INFO
        LoggerProvider.Level.WARN -> Log.WARN
        LoggerProvider.Level.ERROR -> Log.ERROR
    }

    /** Which of an account's two SDK clients. */
    private enum class ClientKind { DRIVE, PHOTOS }

    /** One account's client, the scope it was created on, and its calls in flight. */
    private class HeldClient(val client: ProtonSdkClient, val scope: CoroutineScope) {
        val lease = SdkClientLease()
    }

    private companion object {
        const val TAG = "SdkDriveClient"

        /** How long [remove] waits for the calls still running on a client before leaving its close to them. */
        const val IN_FLIGHT_WAIT_MS = 10_000L
    }
}

/**
 * The client id [userId]'s Drive SDK client gets on this install: a name-based UUID of the install's own
 * random id and the account. The same on every start, so a draft an interrupted upload left is still
 * recognised as this client's, and different for every account, so two accounts signed in on one install
 * never hand the server the same id.
 */
internal fun sdkClientUid(installUid: String, userId: String): String =
    UUID.nameUUIDFromBytes("$installUid/$userId".toByteArray(Charsets.UTF_8)).toString()

/**
 * Whether a client closed while its account had [closedSession] stays closed now that the account has
 * [currentSession]. It does until the account has a session other than that one, which only a new sign-in
 * brings: no session at all (signed out, or unreadable) keeps it closed.
 */
internal fun clientStaysClosed(closedSession: String?, currentSession: String?): Boolean =
    currentSession == null || currentSession == closedSession

/**
 * One client's calls in flight and whether it is retired, kept apart from the SDK so the rule for when the
 * client closes runs in a plain test: a retired client takes no new call, and closes once, when no call is
 * in flight any more. Not thread-safe; the provider calls it under its lock.
 */
internal class SdkClientLease {
    private var inFlight = 0
    private var retired = false
    private var closed = false
    private val _idle = MutableStateFlow(true)

    /** True while no call is in flight. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    /** Counts a call in. False once the client is retired: the call must not use it. */
    fun enter(): Boolean {
        if (retired) return false
        inFlight++
        _idle.value = false
        return true
    }

    /** Counts a call out. True when the caller closes the client now: retired, and this was the last call. */
    fun exit(): Boolean {
        check(inFlight > 0) { "no call in flight" }
        inFlight--
        if (inFlight == 0) _idle.value = true
        return closeNow()
    }

    /** Retires the client, so no call enters it again. True when the caller closes it now: no call is in flight. */
    fun retire(): Boolean {
        retired = true
        return closeNow()
    }

    private fun closeNow(): Boolean {
        if (!retired || inFlight > 0 || closed) return false
        closed = true
        return true
    }
}
