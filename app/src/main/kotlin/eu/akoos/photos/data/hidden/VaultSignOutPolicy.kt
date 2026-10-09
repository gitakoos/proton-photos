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

package eu.akoos.photos.data.hidden

import eu.akoos.photos.domain.repository.VaultWipe
import eu.akoos.photos.util.SyncDiagnostics
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * What a disabled account's sign-out does to the hidden vault.
 *
 * The deliberate sign-out has already emptied the vault, after a confirmation that names the photos,
 * so it finds nothing here. A session ended by the server cannot ask: the photos with no Drive copy go
 * back to the gallery first, and only what could not be moved stays in the vault instead of being
 * destroyed with it. A sign-in that gave up before it made a session owns nothing in the vault, so its
 * disabling leaves the vault alone.
 */
class VaultSignOutPolicy(
    private val rescue: suspend () -> HiddenRescue,
    private val notifyMoved: (zipName: String?) -> Unit,
) {
    private val unfinishedSignIns = ConcurrentHashMap.newKeySet<String>()

    /** Record that the account [userId] is about to be disabled because its sign-in gave up. */
    fun markUnfinishedSignIn(userId: String) {
        unfinishedSignIns += userId
    }

    /**
     * The [VaultWipe] the sign-out of [userId] may carry out, after any rescue it needed.
     *
     * A rescue that fails outright answers [VaultWipe.None]: nothing is known about which files are
     * safe to destroy, so none is.
     */
    suspend fun afterSignOut(userId: String): VaultWipe {
        // The deliberate sign-out has dealt with the vault itself, and logged what it did.
        if (UserSignOut.consume(userId)) return VaultWipe.None
        if (unfinishedSignIns.remove(userId)) {
            SyncDiagnostics.log("sign-out: sign-in gave up, vault left alone")
            return VaultWipe.None
        }
        val rescued = try {
            rescue()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncDiagnostics.log("sign-out: session ended, vault rescue failed (${e.javaClass.simpleName})")
            return VaultWipe.None
        }
        SyncDiagnostics.log("sign-out: session ended, ${vaultNote(rescued)}")
        if (rescued.moved > 0) notifyMoved(rescued.zipName)
        return wipeAfterRescue(rescued)
    }

    companion object {
        /**
         * The vault wipe that follows a rescue: it spares what stayed behind, and a rescue that
         * could not run at all ([rescued] null) spares everything, since nothing says which files are
         * safe to destroy.
         */
        fun wipeAfterRescue(rescued: HiddenRescue?): VaultWipe =
            if (rescued == null) VaultWipe.None else VaultWipe.Except(rescued.stillInVault)

        /** What a rescue did, as counts only, for the diagnostics a tester copies out. */
        fun vaultNote(rescued: HiddenRescue?): String =
            if (rescued == null) "vault move failed, vault left alone"
            else if (rescued.zipName != null) "vault zipped ${rescued.moved}, ${rescued.stillInVault.size} left in vault"
            else "vault moved ${rescued.moved}, ${rescued.stillInVault.size} left in vault"
    }
}

/** What the user chose to do with the hidden photos that have no Drive copy when signing out. */
enum class SignOutVaultChoice {
    /** Take them to the gallery, into [HiddenRescue.SIGN_OUT_FOLDER]. */
    MOVE_OUT,

    /** Delete them with the rest of the vault. */
    DELETE,
}

/**
 * The sign-outs the user asked for, noted before the account is disabled, so the handler every
 * sign-out reaches can tell them from a session the server ended.
 */
object UserSignOut {
    private val userIds = ConcurrentHashMap.newKeySet<String>()

    fun mark(userId: String) {
        userIds += userId
    }

    /** Whether [userId] signed out by choice; true once, so a later session ended by the server for
     *  the same account is not taken for it. */
    fun consume(userId: String): Boolean = userIds.remove(userId)
}
