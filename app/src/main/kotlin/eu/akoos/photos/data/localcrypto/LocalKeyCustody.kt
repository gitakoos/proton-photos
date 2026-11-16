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

package eu.akoos.photos.data.localcrypto

import eu.akoos.photos.data.db.encryption.DatabaseKeyState
import java.io.File

/** A database the open gate set aside because its key could not be opened, with the recovery copies of that key. */
class SetAsideDatabase(val dir: File, val copies: KeyRecoveryCopies)

/** What the database side offers the custody of keys, behind an interface so the rules run in a unit test. */
interface DatabaseKeyAccess {
    /** The database key when it opens now, else null (no encrypted database yet, or the Keystore is not answering). */
    fun currentKey(): ByteArray?

    /** The recovery copies of the live database key. */
    val copies: KeyRecoveryCopies

    /** Set-aside databases with recovery copies whose key has not been recovered yet. */
    fun waitingSetAsides(): List<SetAsideDatabase>

    /** Keeps [key] (sealed by the Keystore) as the recovered key of [setAside], for the next start to restore from. */
    fun markRecovered(setAside: SetAsideDatabase, key: ByteArray, ownerHash: String?)
}

/** How many keys and set-aside databases an unlock opened. */
data class RecoveryOutcome(val keys: Int, val databases: Int, val wrongPassword: Boolean = false) {
    val any: Boolean get() = keys > 0 || databases > 0
}

/** What the settings row and the info sheet say about the local keys. */
data class LocalKeyStatus(
    val lockedKeys: Int,
    val lockedDatabases: Int,
    val passwordCanUnlock: Boolean,
) {
    val locked: Boolean get() = lockedKeys > 0 || lockedDatabases > 0
}

/**
 * Who owns the current master key, and the recovery copies of every key: the rules from the plan's key hierarchy,
 * kept apart from Android so each of them runs in a unit test.
 *
 * - The current key follows the occupant of the app (a guest, or one account): a guest's key is adopted by the
 *   account it signs in to, another account's key is replaced by a new one, and a sign-out replaces the key of the
 *   account that left. Replaced keys stay, so what they encrypted stays readable.
 * - An account copy of the current key is written for the account that owns it, and an account copy of the
 *   database key for every account that signs in.
 * - While a Hidden password is set, every key that opens has a password copy.
 */
class LocalKeyCustody(
    private val keyring: LocalKeyring,
    private val masterCopies: KeyRecoveryCopies,
    private val database: DatabaseKeyAccess,
    private val log: (String) -> Unit,
) {

    /** The occupant now: [userId] signed in, or null for a guest or nobody. */
    fun onOccupant(userId: String?) {
        val info = keyring.currentInfo()
        if (userId == null) {
            if (info == null || info.second == LocalKeyOwner.UNASSIGNED) {
                keyring.current()
                keyring.setOwner(LocalKeyOwner.GUEST)
            }
            return
        }
        val owner = LocalKeyOwner.account(userId)
        when {
            info == null -> {
                keyring.current()
                keyring.setOwner(owner)
            }
            info.second == owner -> Unit
            !LocalKeyOwner.isAccount(info.second) -> {
                keyring.setOwner(owner)
                log("local keys: current key adopted by the account")
            }
            else -> {
                keyring.rotate(owner)
                log("local keys: new key for another account")
            }
        }
    }

    /** [userId] finished signing out: the key it owned stops being the one new data is written with. */
    fun onSignedOut(userId: String) {
        val info = keyring.currentInfo() ?: return
        if (info.second == LocalKeyOwner.account(userId)) {
            keyring.rotate(LocalKeyOwner.UNASSIGNED)
            log("local keys: new key after sign-out")
        }
    }

    /** Writes the account copies [userId] is due and lacks. Answers how many were written. */
    fun ensureAccountCopies(userId: String, sealer: AccountSealer): Int {
        val hash = LocalKeyOwner.userHash(userId)
        var written = 0
        val info = keyring.currentInfo()
        if (info != null && info.second == LocalKeyOwner.account(userId) && !masterCopies.hasAccountCopy(info.first, hash)) {
            val current = keyring.current()
            if (current.keyId == info.first) {
                masterCopies.writeAccountCopy(current.keyId, current.key, hash, sealer)
                written++
            }
        }
        val dbKey = database.currentKey()
        if (dbKey != null && !database.copies.hasAccountCopy(DATABASE_NAME, hash)) {
            database.copies.writeAccountCopy(DATABASE_NAME, dbKey, hash, sealer)
            written++
        }
        if (written > 0) log("local keys: $written account copies written")
        return written
    }

    /** Opens every locked key and set-aside database [userId]'s account copies open. */
    fun recoverWithAccount(userId: String, sealer: AccountSealer): RecoveryOutcome {
        val hash = LocalKeyOwner.userHash(userId)
        var keys = 0
        for (id in keyring.lockedKeyIds()) {
            val key = masterCopies.openAccountCopy(id, hash, sealer) ?: continue
            keyring.restore(id, key)
            keys++
        }
        var databases = 0
        for (setAside in database.waitingSetAsides()) {
            val key = setAside.copies.openAccountCopy(DATABASE_NAME, hash, sealer) ?: continue
            database.markRecovered(setAside, key, hash)
            databases++
        }
        if (keys + databases > 0) log("local keys: account opened $keys keys, $databases databases")
        return RecoveryOutcome(keys, databases)
    }

    /** Opens every locked key and set-aside database a password copy opens with [password]. */
    fun recoverWithPassword(password: CharArray): RecoveryOutcome {
        var keys = 0
        var tried = 0
        for (id in keyring.lockedKeyIds()) {
            if (!masterCopies.hasPasswordCopy(id)) continue
            tried++
            val key = masterCopies.openPasswordCopy(id, password) ?: continue
            keyring.restore(id, key)
            keys++
        }
        var databases = 0
        for (setAside in database.waitingSetAsides()) {
            if (!setAside.copies.hasPasswordCopy(DATABASE_NAME)) continue
            tried++
            val key = setAside.copies.openPasswordCopy(DATABASE_NAME, password) ?: continue
            database.markRecovered(setAside, key, null)
            databases++
        }
        if (keys + databases > 0) log("local keys: password opened $keys keys, $databases databases")
        return RecoveryOutcome(keys, databases, wrongPassword = tried > 0 && keys + databases == 0)
    }

    /**
     * Brings the password copies in line with [password]. [rewrite] is the user's own change: a new password
     * rewrites the copy of every key that opens now (each new copy replaces the old one in one step, so there is
     * never a moment with neither), and none removes them. Without [rewrite] (a start, a new key) only missing
     * copies are written, and no password removes nothing: a password that is not set or cannot be read is never
     * taken for the user removing it. The copy of a key that does not open now is never touched: it may be the
     * only way back to that key, and it cannot be written again.
     */
    fun syncPasswordCopies(password: CharArray?, rewrite: Boolean) {
        if (password == null && !rewrite) return
        runCatching { keyring.current() }
        val ready = keyring.keyIds().mapNotNull { id -> (keyring.state(id) as? DatabaseKeyState.Ready)?.let { id to it.key } }
        val dbKey = database.currentKey()
        if (password == null) {
            ready.forEach { (id, _) -> masterCopies.deletePasswordCopy(id) }
            if (dbKey != null) database.copies.deletePasswordCopy(DATABASE_NAME)
            log("local keys: password copies removed by the user")
            return
        }
        var written = 0
        for ((id, key) in ready) {
            if (!rewrite && masterCopies.hasPasswordCopy(id)) continue
            masterCopies.writePasswordCopy(id, key, password)
            written++
        }
        if (dbKey != null && (rewrite || !database.copies.hasPasswordCopy(DATABASE_NAME))) {
            database.copies.writePasswordCopy(DATABASE_NAME, dbKey, password)
            written++
        }
        if (written > 0) log("local keys: $written password copies written")
    }

    /**
     * Whether [password] opens a password copy this phone holds: the proof that it is the user's password when
     * the stored one cannot be read any more (its Keystore key was lost), so it can be stored again.
     */
    fun passwordOpensCopies(password: CharArray): Boolean {
        val names = keyring.keyIds().filter { masterCopies.hasPasswordCopy(it) }
        if (names.isEmpty()) return database.copies.openPasswordCopy(DATABASE_NAME, password) != null
        return masterCopies.openPasswordCopy(names.first(), password) != null
    }

    fun status(): LocalKeyStatus {
        val locked = keyring.lockedKeyIds()
        val waiting = database.waitingSetAsides()
        val byPassword = locked.any { masterCopies.hasPasswordCopy(it) } ||
            waiting.any { it.copies.hasPasswordCopy(DATABASE_NAME) }
        return LocalKeyStatus(locked.size, waiting.size, byPassword)
    }

    companion object {
        const val DATABASE_NAME = "db"
    }
}
