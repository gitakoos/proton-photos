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

package eu.akoos.photos.crypto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.proton.core.crypto.common.pgp.SessionKey
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CryptoServiceClient"

/**
 * Main-process front door to the [CryptoService] running in `:crypto`.
 *
 * Routes thumbnail decrypt leaves over the binder so the panic-prone libgojni runs in its own
 * process, keeping a residual SIGABRT off the UI. EVERY method falls back to the in-process
 * [DriveCryptoHelper] on any binder failure, so behaviour matches in-process decrypt if `:crypto`
 * never comes up. [fallback] is [dagger.Lazy] so resolving this client doesn't construct the helper
 * (and its Room graph) unless a fallback fires.
 */
@Singleton
class CryptoServiceClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fallback: dagger.Lazy<DriveCryptoHelper>,
) {

    @Volatile private var binder: ICryptoService? = null

    /** Completed in [onServiceConnected] so callers can await; recreated on each (re)bind. */
    @Volatile private var connection = CompletableDeferred<ICryptoService>()

    /** Rebind attempts since the last connect; past [MAX_REBINDS] we stay in-process for the session. */
    private val rebindAttempts = AtomicInteger(0)

    /** Latched once we give up on `:crypto` for the session — calls go straight to fallback. */
    @Volatile private var permanentFallback = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = ICryptoService.Stub.asInterface(service)
            binder = stub
            rebindAttempts.set(0)
            if (!connection.isCompleted) connection.complete(stub)
            Log.d(TAG, "connected to :crypto")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // :crypto died (possibly a residual SIGABRT). Null the binder so in-flight calls fall
            // back, then rebind up to the cap; past it, run in-process for the session.
            binder = null
            Log.w(TAG, "disconnected from :crypto")
            connection = CompletableDeferred()
            if (rebindAttempts.incrementAndGet() <= MAX_REBINDS) {
                bind()
            } else {
                permanentFallback = true
                Log.w(TAG, "rebind budget exhausted — staying in-process for this session")
            }
        }
    }

    /** Kick off the binding. Idempotent — repeat calls are cheap no-ops under BIND_AUTO_CREATE. */
    private fun bind() {
        val ok = runCatching {
            context.bindService(
                Intent(context, CryptoService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!ok) {
            permanentFallback = true
            Log.w(TAG, "bindService returned false — staying in-process for this session")
        }
    }

    /** Returns the live binder, binding (and awaiting) on first use; null when unreachable in time. */
    private suspend fun awaitBinding(): ICryptoService? {
        if (permanentFallback) return null
        binder?.let { return it }
        if (connection.isCompleted && !connection.isCancelled) {
            binder?.let { return it } // prior connect won; the first volatile read lost the race
        }
        bind()
        return try {
            withTimeout(BIND_TIMEOUT_MS) { connection.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "awaitBinding timed out after ${BIND_TIMEOUT_MS}ms — falling back")
            null
        } catch (e: Exception) {
            Log.w(TAG, "awaitBinding failed: ${e.message} — falling back")
            null
        }
    }

    /**
     * Runs [remote] against the bound service, falling back to [local] only on a transport failure
     * (no binding, RemoteException, dead binder). A null from [remote] is a real decrypt failure
     * that already happened in `:crypto`, so it is NOT retried in-process — that would re-run the
     * same panic-prone input.
     */
    private suspend fun <T> withService(
        remote: (ICryptoService) -> T,
        local: suspend () -> T,
    ): T {
        val svc = awaitBinding() ?: return local()
        return try {
            remote(svc)
        } catch (e: DeadObjectException) {
            binder = null
            Log.w(TAG, "remote call hit a dead :crypto — falling back in-process")
            local()
        } catch (e: RemoteException) {
            Log.w(TAG, "remote call failed (${e.message}) — falling back in-process")
            local()
        } catch (e: Exception) {
            Log.w(TAG, "remote call errored (${e.message}) — falling back in-process")
            local()
        }
    }

    // ─── Suspend mirrors of the DriveCryptoHelper leaf decrypts ────────────────

    suspend fun decryptNodeKey(
        nodeKeyArmored: String,
        nodePassphraseArmored: String,
        parentKeyBytes: ByteArray,
    ): ByteArray = withService(
        remote = { svc ->
            svc.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes)
                // Null = :crypto's guarded decrypt threw; the in-process helper throws on the same
                // input too, so mirror that contract rather than returning empty bytes.
                ?: throw RemoteException("decryptNodeKey returned null from :crypto")
        },
        local = { fallback.get().decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes) },
    )

    suspend fun decryptSessionKey(contentKeyPacketBase64: String, nodeKeyBytes: ByteArray): SessionKey? =
        withService(
            remote = { svc ->
                svc.decryptSessionKeyBytes(contentKeyPacketBase64, nodeKeyBytes)?.let { SessionKey(it) }
            },
            local = { fallback.get().decryptSessionKey(contentKeyPacketBase64, nodeKeyBytes) },
        )

    suspend fun decryptFileToDestination(sessionKey: SessionKey, encryptedFile: File, destFile: File): File =
        withService(
            remote = { svc ->
                val ok = svc.decryptFileWithSessionKey(sessionKey.key, encryptedFile.absolutePath, destFile.absolutePath)
                if (!ok) throw RemoteException("decryptFileWithSessionKey failed in :crypto")
                destFile
            },
            local = { fallback.get().decryptFileToDestination(sessionKey, encryptedFile, destFile) },
        )

    suspend fun decryptBinaryPgpWithNodeKey(data: ByteArray, nodeKeyBytes: ByteArray): ByteArray? =
        withService(
            remote = { svc -> svc.decryptBinaryPgpWithNodeKey(data, nodeKeyBytes) },
            local = { fallback.get().decryptBinaryPgpWithNodeKey(data, nodeKeyBytes) },
        )

    /** File-based binary-PGP decrypt for large download blocks. Returns true on success
     *  (plaintext written to [destFile]). Falls back to the in-process byte[] helper. */
    suspend fun decryptBinaryToFile(encryptedFile: File, destFile: File, nodeKeyBytes: ByteArray): Boolean =
        withService(
            remote = { svc -> svc.decryptBinaryToFile(encryptedFile.absolutePath, destFile.absolutePath, nodeKeyBytes) },
            local = {
                val plain = fallback.get().decryptBinaryPgpWithNodeKey(encryptedFile.readBytes(), nodeKeyBytes)
                if (plain != null) { destFile.writeBytes(plain); true } else false
            },
        )

    private companion object {
        /** Capped rebind attempts before the client gives up on `:crypto` for the
         *  session. Each disconnect (e.g. a residual single-blob SIGABRT) consumes one. */
        const val MAX_REBINDS = 5

        /** Upper bound on how long a caller waits for the first bind before falling
         *  back in-process. Short so a stuck `:crypto` never stalls the gallery. */
        const val BIND_TIMEOUT_MS = 4_000L
    }
}
