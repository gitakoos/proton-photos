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
 * The deliberate sign-out has already dealt with the vault, after a confirmation that names the
 * photos, so it finds nothing here. A session ended by the server cannot ask: the photos with no
 * confirmed Drive copy go back to the device first, and the wipe destroys only what that rescue
 * dealt with. A sign-in that gave up before it made a session owns nothing in the vault, so its
 * disabling leaves the vault alone.
 */
class VaultSignOutPolicy(
    private val rescue: suspend (userId: String, busy: Set<String>) -> HiddenRescue,
    private val notify: (RescueNotice) -> Unit,
) {
    private val unfinishedSignIns = ConcurrentHashMap.newKeySet<String>()

    /** Record that the account [userId] is about to be disabled because its sign-in gave up. */
    fun markUnfinishedSignIn(userId: String) {
        unfinishedSignIns += userId
    }

    /**
     * The [VaultWipe] the sign-out of [userId] may carry out, after any rescue it needed. [busy] are the
     * vault files a hide was moving when the sign-out began; they are neither rescued nor destroyed.
     *
     * A rescue that fails outright answers [VaultWipe.None]: nothing is known about which files are
     * safe to destroy, so none is.
     */
    suspend fun afterSignOut(userId: String, busy: Set<String> = emptySet()): VaultWipe {
        // The deliberate sign-out has dealt with the vault itself, and logged what it did.
        if (UserSignOut.consume(userId)) return VaultWipe.None
        if (unfinishedSignIns.remove(userId)) {
            SyncDiagnostics.log("sign-out: sign-in gave up, vault left alone")
            return VaultWipe.None
        }
        val rescued = try {
            rescue(userId, busy)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncDiagnostics.log("sign-out: session ended, vault rescue failed (${e.javaClass.simpleName})")
            return VaultWipe.None
        }
        SyncDiagnostics.log("sign-out: session ended, ${vaultNote(rescued)}")
        forcedLogoutNotice(rescued)?.let(notify)
        if (rescued.keptForDrive.isNotEmpty()) notify(RescueNotice.KeptUnconfirmedInDrive)
        return wipeAfterRescue(rescued, busy)
    }

    companion object {
        /**
         * The vault wipe that follows a rescue: exactly the files the rescue says are safe elsewhere,
         * never one that stayed behind or one a hide was still moving ([busy]). A rescue that could not
         * run at all ([rescued] null) spares everything, since nothing says which files are safe.
         */
        fun wipeAfterRescue(rescued: HiddenRescue?, busy: Set<String> = emptySet()): VaultWipe =
            if (rescued == null) VaultWipe.None
            else VaultWipe.Only(rescued.wipeable - rescued.stillInVault - rescued.keptForDrive - busy)

        /** The notice about the photos a forced logout's rescue took out, or null when it took nothing
         *  anywhere. The photos kept for Drive have a notice of their own beside it. */
        fun forcedLogoutNotice(rescued: HiddenRescue): RescueNotice? = when {
            rescued.keptForPassword -> RescueNotice.KeptForPassword
            rescued.zipFailed -> RescueNotice.ZipFailed
            rescued.stillInVault.isEmpty() -> if (rescued.moved > 0) RescueNotice.Moved(rescued.zipName) else null
            rescued.moved > 0 -> RescueNotice.MovedPartly
            else -> RescueNotice.NotMoved
        }

        /**
         * The notice after a sign-out the user asked for, choosing to take the photos to the gallery: none
         * when every photo went where the user chose, otherwise where the rest are. A rescue that could not
         * run with [vaultFiles] in the vault left all of them there.
         */
        fun userSignOutNotice(rescued: HiddenRescue?, vaultFiles: Int): RescueNotice? = when {
            rescued == null -> if (vaultFiles > 0) RescueNotice.NotMoved else null
            rescued.keptForPassword -> RescueNotice.KeptForPassword
            rescued.zipFailed -> RescueNotice.ZipFailed
            rescued.stillInVault.isEmpty() -> null
            rescued.moved > 0 -> RescueNotice.MovedPartly
            else -> RescueNotice.NotMoved
        }

        /** What a rescue did, as counts only, for the diagnostics a tester copies out. */
        fun vaultNote(rescued: HiddenRescue?): String {
            if (rescued == null) return "vault move failed, vault left alone"
            val note = if (rescued.keptForPassword) "vault kept ${rescued.stillInVault.size}, password not readable for the zip"
            else if (rescued.zipFailed) "vault kept ${rescued.stillInVault.size}, zip not made"
            else if (rescued.zipName != null) "vault zipped ${rescued.moved}, ${rescued.stillInVault.size} left in vault"
            else "vault moved ${rescued.moved}, ${rescued.stillInVault.size} left in vault"
            return if (rescued.keptForDrive.isEmpty()) note
            else "$note, ${rescued.keptForDrive.size} kept, not confirmed in Drive"
        }
    }
}

/** What the user is told about the hidden photos after a sign-out. */
sealed interface RescueNotice {
    /** Every photo with no confirmed Drive copy went to the gallery, or into the zip [zipName]. */
    data class Moved(val zipName: String?) : RescueNotice

    /** Some went to the gallery and the rest stayed in the app. */
    data object MovedPartly : RescueNotice

    /** None could go to the gallery; they all stayed in the app. */
    data object NotMoved : RescueNotice

    /** A password is set but cannot be read, so they stayed in the app rather than going out unprotected. */
    data object KeptForPassword : RescueNotice

    /** The password-protected zip could not be made, so they stayed in the app. */
    data object ZipFailed : RescueNotice

    /** Photos recorded with a Drive copy that Drive could not confirm stayed in the app, and show in Hidden
     *  again after the next sign-in. */
    data object KeptUnconfirmedInDrive : RescueNotice
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
