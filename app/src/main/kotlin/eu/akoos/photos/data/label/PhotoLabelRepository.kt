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

package eu.akoos.photos.data.label

import android.util.Log
import eu.akoos.photos.data.db.dao.LabelBulkResult
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.db.dao.PhotoLabelDao
import eu.akoos.photos.data.db.entity.PhotoLabelEntity
import eu.akoos.photos.data.db.entity.PhotoLabelNameEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.LabelNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The free-text labels a user puts on photos, kept on the device only and keyed like faces and
 * embeddings: the photo's gallery stableId, under the signed-in account or
 * [PhotoLocationEntity.LOCAL_USER] for a guest. Every write is normalised by [LabelNormalizer] (clean,
 * distinct, at most [LabelNormalizer.MAX_LABELS] per photo), and a label the account already uses
 * keeps the spelling it was first given.
 *
 * The first time a signed-in account touches labels in a process, the guest's labels are adopted into
 * it ([adoptGuestLabels]), so labelling before signing in carries over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PhotoLabelRepository @Inject constructor(
    private val dao: PhotoLabelDao,
    private val accountManager: AccountManager,
    private val gate: LabelWriteGate = LabelWriteGate.Open,
) {
    private val adoptLock = Mutex()
    private val adopted = HashSet<String>()

    /** The account key labels are stored under right now. */
    suspend fun currentUserKey(): String = keyFor(accountManager.getPrimaryUserId().first())

    /** [currentUserKey], live, so a guest screen upgrades in place on sign-in. */
    fun observeUserKey(): Flow<String> =
        accountManager.getPrimaryUserId()
            .map { it?.id ?: PhotoLocationEntity.LOCAL_USER }
            .distinctUntilChanged()
            .onEach { ensureAdopted(it) }

    /** Every labelled photo of the current account, photo key to its labels in display order. */
    fun observeLabelsByPhoto(): Flow<Map<String, List<String>>> =
        observeUserKey().flatMapLatest { user -> dao.observeAllForUser(user) }
            .map { rows ->
                val byKey = LinkedHashMap<String, MutableList<String>>()
                for (row in rows) byKey.getOrPut(row.photoKey) { mutableListOf() } += row.label
                byKey
            }

    /** Every distinct label of the current account with its photo count, most used first. */
    fun observeLabelUsage(): Flow<List<LabelUsage>> =
        observeUserKey().flatMapLatest { user ->
            combine(dao.observeUsage(user), dao.observeNames(user)) { usage, names -> withEmptyLabels(usage, names) }
        }

    /** Whether the labels are locked against user edits right now, live. See [LabelWriteGate]. */
    fun observeLocked(): Flow<Boolean> = gate.locked

    /**
     * Creates the label [text] without putting it on a photo, so it is listed and offered when labelling.
     * A label the account already has, whatever its case or accents, is [LabelCreation.Exists] under the
     * spelling it was first given, and text that is no valid label is [LabelCreation.Invalid].
     */
    suspend fun createLabel(text: String): LabelCreation {
        ensureUnlocked()
        val clean = LabelNormalizer.clean(text) ?: return LabelCreation.Invalid
        val user = writableUserKey()
        val key = LabelNormalizer.fold(clean)
        (dao.spellingOf(user, key) ?: dao.nameSpellingOf(user, key))?.let { return LabelCreation.Exists(it) }
        dao.insertName(PhotoLabelNameEntity(user, key, clean, System.currentTimeMillis()))
        return LabelCreation.Created(clean)
    }

    /** The cloud-or-device keys of the photos that carry [label], for a picker that offers the rest. */
    suspend fun photoKeysOfLabel(label: String): Set<String> =
        dao.photoKeysOfLabel(writableUserKey(), LabelNormalizer.fold(label)).toHashSet()

    /** One photo's labels under one key, live. */
    fun observeLabelsOf(photoKey: String): Flow<List<String>> =
        observeUserKey().flatMapLatest { user -> dao.observeLabelsOfPhoto(user, photoKey) }
            .map { rows -> rows.map { it.label } }

    /**
     * [item]'s labels, read under every key they may sit under ([LabelKeyReconciler.keysFor]). A Synced
     * item's labels still under its device URI are moved onto its linkId on the way, so the second
     * read is needed at most once per photo.
     */
    suspend fun labelsFor(item: GalleryItem): List<String> {
        val user = writableUserKey()
        val keys = LabelKeyReconciler.keysFor(item)
        val byKey = dao.labelsForKeys(user, keys).groupBy({ it.photoKey }, { it.label })
        val labels = LabelKeyReconciler.labelsFor(item, byKey)
        if (keys.size > 1 && byKey[keys[1]].orEmpty().isNotEmpty()) {
            runCatching { dao.rekeyPhotos(user, listOf(keys[1] to keys[0])) }
                .onFailure { Log.w(TAG, "label rekey on read failed: ${it.message}") }
        }
        return labels
    }

    /**
     * Moves the labels of every Synced item in [items] still keyed by its device URI onto its linkId.
     * Cheap when nothing is pending (one key read); returns how many rows moved.
     */
    suspend fun reconcileKeys(items: Iterable<GalleryItem>): Int {
        val user = writableUserKey()
        val labelled = dao.photoKeysForUser(user).toHashSet()
        if (labelled.isEmpty()) return 0
        val pairs = LabelKeyReconciler.pendingMoves(items, labelled)
        if (pairs.isEmpty()) return 0
        return dao.rekeyPhotos(user, pairs)
    }

    /**
     * Replaces [item]'s labels with [labels], normalised and capped. A Synced item's URI-keyed labels
     * are folded onto its linkId first, so the result is the photo's whole list in one place.
     */
    suspend fun setLabels(item: GalleryItem, labels: List<String>) {
        ensureUnlocked()
        val user = writableUserKey()
        val keys = LabelKeyReconciler.keysFor(item)
        if (keys.size > 1) dao.rekeyPhotos(user, listOf(keys[1] to keys[0]))
        replace(user, keys[0], labels)
    }

    /** Replaces the labels under [photoKey] with [labels], normalised and capped. */
    suspend fun setLabels(photoKey: String, labels: List<String>) {
        ensureUnlocked()
        replace(writableUserKey(), photoKey, labels)
    }

    /**
     * Adds [labels] to every photo in [photoKeys] in one transaction, skipping labels a photo already
     * has. A photo that would go past [LabelNormalizer.MAX_LABELS] keeps what it has and is counted in
     * [LabelBulkResult.cappedPhotos].
     */
    suspend fun addLabels(photoKeys: Collection<String>, labels: List<String>): LabelBulkResult {
        ensureUnlocked()
        val user = writableUserKey()
        val clean = canonical(user, LabelNormalizer.normalize(labels).labels)
        if (clean.isEmpty() || photoKeys.isEmpty()) return LabelBulkResult(0, 0)
        return dao.addLabelsToPhotos(user, photoKeys, clean, System.currentTimeMillis())
    }

    /** Removes [label] from the photo under [photoKey]. Returns true when it was there. */
    suspend fun removeLabel(photoKey: String, label: String): Boolean {
        ensureUnlocked()
        return dao.deleteLabelOfPhoto(writableUserKey(), photoKey, LabelNormalizer.fold(label)) > 0
    }

    /**
     * Renames [from] to [to] on every photo of the account, merging into [to] when it already exists.
     * Returns false when [to] is not a valid label.
     */
    suspend fun renameLabel(from: String, to: String): Boolean {
        ensureUnlocked()
        val clean = LabelNormalizer.clean(to) ?: return false
        dao.renameLabel(writableUserKey(), LabelNormalizer.fold(from), LabelNormalizer.fold(clean), clean)
        return true
    }

    /** Deletes every label of [labels] as [deleteLabel] does. Returns how many photos lost a label, counting a photo once per label. */
    suspend fun deleteLabels(labels: Collection<String>): Int {
        ensureUnlocked()
        val user = writableUserKey()
        return labels.sumOf { dao.removeLabelEverywhere(user, LabelNormalizer.fold(it)) }
    }

    /** Removes [label] from every photo of the account. Returns how many photos lost it. */
    suspend fun deleteLabel(label: String): Int {
        ensureUnlocked()
        return dao.removeLabelEverywhere(writableUserKey(), LabelNormalizer.fold(label))
    }

    /** [userId]'s labelled photos as the Drive copy stores them: each photo's key and its labels in order. */
    suspend fun snapshotPhotos(userId: UserId): List<LabelSnapshotPhoto> {
        val user = keyFor(userId)
        ensureAdopted(user)
        return dao.allLabelsForUser(user).groupBy({ it.photoKey }, { it.label })
            .map { (key, labels) -> LabelSnapshotPhoto(key, labels) }
    }

    /** The labels [userId] created without a photo and has not put on one since, as the Drive copy stores them. */
    suspend fun snapshotNames(userId: UserId): List<String> {
        val user = keyFor(userId)
        ensureAdopted(user)
        val used = dao.usage(user).mapTo(HashSet()) { it.labelKey }
        return dao.namesForUser(user).filter { it.labelKey !in used }.map { it.label }
    }

    /**
     * Brings the saved [snapshot] back into [userId]'s labels: each photo it lists that this install may restore
     * ([LabelSnapshotMerge.usablePhotos]) gets the saved labels first and keeps the ones it has now while there is
     * room ([LabelSnapshotMerge.restoredList]), so a label deleted since returns and a label added since stays.
     * The saved labels with no photo come back too, when the account has no label of that name. Returns how many
     * photos changed and how many labels without a photo were added.
     */
    suspend fun applySnapshot(userId: UserId, snapshot: LabelSnapshot, ownDeviceId: String, now: Long): LabelRestoreCounts {
        val user = keyFor(userId)
        ensureAdopted(user)
        var photos = 0
        for (chunk in LabelSnapshotMerge.usablePhotos(snapshot, ownDeviceId).chunked(RESTORE_CHUNK)) {
            val current = dao.labelsForKeys(user, chunk.map { it.key }).groupBy({ it.photoKey }, { it.label })
            for (photo in chunk) {
                val held = current[photo.key].orEmpty()
                val restored = LabelSnapshotMerge.restoredList(photo.labels, held)
                if (restored.map { LabelNormalizer.fold(it) } == held.map { LabelNormalizer.fold(it) }) continue
                val rows = canonical(user, restored).map {
                    PhotoLabelEntity(user, photo.key, it, LabelNormalizer.fold(it), now)
                }
                dao.replaceLabelsOfPhoto(user, photo.key, rows)
                photos++
            }
        }
        var names = 0
        for (name in snapshot.names) {
            val clean = LabelNormalizer.clean(name) ?: continue
            val key = LabelNormalizer.fold(clean)
            if (dao.spellingOf(user, key) != null || dao.nameSpellingOf(user, key) != null) continue
            dao.insertName(PhotoLabelNameEntity(user, key, clean, now))
            names++
        }
        return LabelRestoreCounts(photos = photos, names = names)
    }

    /** Moves the current account's labels from [oldKey] to [newKey]. */
    suspend fun rekey(oldKey: String, newKey: String): Int = rekey(writableUserKey(), oldKey, newKey)

    /** Moves [userId]'s labels from [oldKey] to [newKey], for a caller that already holds the account. */
    suspend fun rekeyFor(userId: UserId, oldKey: String, newKey: String): Int {
        val user = keyFor(userId)
        ensureAdopted(user)
        return rekey(user, oldKey, newKey)
    }

    /** Drops every label of the photos in [photoKeys], under every owner, for a photo going into the vault. */
    suspend fun deleteForPhotos(photoKeys: Collection<String>): Int =
        if (photoKeys.isEmpty()) 0 else dao.deleteForPhotoKeys(photoKeys)

    /** Drops every label of [userId], for the sign-out wipe. */
    suspend fun clearForUser(userId: UserId): Int {
        adoptLock.withLock { adopted -= userId.id }
        return dao.deleteAllForUser(userId.id)
    }

    /**
     * Adopts the guest's labels into [accountId]: on a photo the account has not labelled they move
     * over whole; on one it has, the account's labels win and the guest's fill the remaining room.
     * Idempotent, since no guest row is left afterwards. Returns how many rows the account gained.
     */
    suspend fun adoptGuestLabels(accountId: String): Int {
        if (accountId == PhotoLocationEntity.LOCAL_USER) return 0
        val guest = PhotoLocationEntity.LOCAL_USER
        if (!dao.hasAnyForUser(guest) && dao.namesForUser(guest).isEmpty()) return 0
        return dao.adoptUserRows(PhotoLocationEntity.LOCAL_USER, accountId)
    }

    private suspend fun rekey(user: String, oldKey: String, newKey: String): Int =
        if (oldKey == newKey) 0 else dao.rekeyPhotos(user, listOf(oldKey to newKey))

    private suspend fun replace(user: String, photoKey: String, labels: List<String>) {
        val now = System.currentTimeMillis()
        val rows = canonical(user, LabelNormalizer.normalize(labels).labels)
            .map { PhotoLabelEntity(user, photoKey, it, LabelNormalizer.fold(it), now) }
        dao.replaceLabelsOfPhoto(user, photoKey, rows)
    }

    /** [labels] with each one the account already uses swapped for the spelling it was first given. */
    private suspend fun canonical(user: String, labels: List<String>): List<String> =
        labels.map {
            val key = LabelNormalizer.fold(it)
            dao.spellingOf(user, key) ?: dao.nameSpellingOf(user, key) ?: it
        }

    /** The current account key, with the guest's labels adopted first when an account is signed in. */
    private suspend fun writableUserKey(): String = currentUserKey().also { ensureAdopted(it) }

    private suspend fun ensureUnlocked() {
        if (gate.isLocked()) throw LabelsLockedException()
    }

    private suspend fun ensureAdopted(user: String) {
        if (user == PhotoLocationEntity.LOCAL_USER) return
        adoptLock.withLock {
            if (user in adopted) return
            runCatching { adoptGuestLabels(user) }
                .onSuccess { adopted += user }
                .onFailure { Log.w(TAG, "guest label adoption failed: ${it.message}") }
        }
    }

    private fun keyFor(userId: UserId?): String = userId?.id ?: PhotoLocationEntity.LOCAL_USER

    private companion object {
        const val TAG = "PhotoLabelRepository"

        /** Photos a restore reads and writes per slice, under the host-variable limit. */
        const val RESTORE_CHUNK = 500
    }
}

/** What a restore from the Drive copy added: [photos] whose list changed, and [names] new labels with no photo. */
data class LabelRestoreCounts(val photos: Int, val names: Int)

/** How [PhotoLabelRepository.createLabel] ended. */
sealed interface LabelCreation {
    /** The label was made, under this spelling. */
    data class Created(val label: String) : LabelCreation

    /** The account already has the label, under this spelling. */
    data class Exists(val label: String) : LabelCreation

    /** The text is no valid label. */
    data object Invalid : LabelCreation
}

/** [usage] with each of [names] the account has no photo for added behind it at a count of zero. */
internal fun withEmptyLabels(usage: List<LabelUsage>, names: List<PhotoLabelNameEntity>): List<LabelUsage> {
    if (names.isEmpty()) return usage
    val used = usage.mapTo(HashSet()) { it.labelKey }
    val empty = names.filter { it.labelKey !in used }.map { LabelUsage(it.labelKey, it.label, 0) }
    return usage + empty
}
