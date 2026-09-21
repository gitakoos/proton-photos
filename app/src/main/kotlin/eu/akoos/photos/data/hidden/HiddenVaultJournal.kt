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

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.FolderCoverMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a hide survive being interrupted.
 *
 * Moving a device photo into the vault is three durable steps that cannot be made one — copy the
 * bytes, record the entry, remove the MediaStore original — so the order decides what a process death
 * costs. Recording LAST means a death after the delete leaves bytes nothing refers to: the photo is
 * gone from the gallery, absent from the vault, and taken by the next sign-out. Recording FIRST turns
 * the same death into a repairable state, which is what this class exists for.
 *
 * [journal] writes the intent before the delete, [confirm] publishes it after, [discard] undoes a
 * hide that did not happen, and [reconcile] resolves whatever an interruption left behind. Every hide
 * surface shares one instance, so an in-flight hide is never mistaken for a leftover.
 */
@Singleton
class HiddenVaultJournal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hiddenStorage: HiddenStorageManager,
    private val localTagDao: LocalTagDao,
    private val uploadAlbumTargetDao: UploadAlbumTargetDao,
    private val syncStateRepo: SyncStateRepository,
    private val accountManager: AccountManager,
) {

    /**
     * One photo on its way into the vault. [sourceUri] is the MediaStore original about to be
     * removed, and it is what a later [reconcile] re-checks to learn whether the removal happened.
     */
    data class Entry(
        val privateUri: String,
        val sourceUri: String,
        val sourceFolder: String? = null,
        val originalName: String? = null,
        val cloudLinkId: String? = null,
    )

    /** Vault URIs this process is actively hiding, so [reconcile] cannot mistake a hide that is
     *  merely waiting on the system delete dialog for one an interruption abandoned. Lost on process
     *  death, which is exactly right: after a restart there IS no in-flight hide. */
    private val inFlight = mutableSetOf<String>()

    private val inFlightLock = Mutex()

    /** [reconcile] repairs a durable state, so once per process is enough; running it again on every
     *  vault open would re-walk the directory for nothing. */
    private val reconciled = AtomicBoolean(false)

    /** The same, for [repairRevealedRows]: it repairs durable rows, and the device's answer about a
     *  file does not change often enough to be worth asking twice in one process. Its own flag rather
     *  than [reconciled], so each half of the repair claims itself once whatever happens to the other. */
    private val revealedRowsRepaired = AtomicBoolean(false)

    /**
     * Records the intent to hide [entries] and everything an eventual restore needs, BEFORE their
     * originals are deleted. Returns false when nothing could be persisted, in which case the caller
     * must not proceed to the delete.
     *
     * The vault index itself stays untouched: an entry that has not confirmed is not yet a hidden
     * photo, so nothing surfaces it and unhide never sees it. The per-uri maps are keyed by vault uri
     * and read only for indexed photos, so writing them here is invisible until [confirm].
     *
     * This is also where a photo's heart, categories, pinned cover and album queue are copied forward,
     * for one reason: it is the last moment they are still reachable. They are keyed by the source uri,
     * which stops naming anything the instant the delete lands, and the media scan that follows prunes
     * two of them the moment it proves the file gone. Reading them here — after the bytes are safe and
     * before a single original is removed — puts the whole record on disk ahead of both.
     *
     * The record is written for EVERY vaulted photo, including one that carries none of those four,
     * because it also holds the uri the photo was hidden from. That uri is what keys the sync row this
     * hide is about to mark vaulted, and therefore the one handle a reveal has on that row when no
     * Drive pairing was recorded to find it by. The journal entry holds it too and is cleared by
     * [confirm], so this is the copy that outlives the hide.
     */
    suspend fun journal(entries: List<Entry>): Boolean {
        if (entries.isEmpty()) return true
        val startedAt = SystemClock.elapsedRealtime()
        inFlightLock.withLock { inFlight += entries.map { it.privateUri } }
        return try {
            val albumsBySource = albumTargetsFor(entries)
            val userTagsBySource = userTagsFor(entries)
            context.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.HIDDEN_PENDING_HIDES] =
                    (prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet()) +
                        entries.map { HiddenVaultLeftovers.journalEntry(it.privateUri, it.sourceUri) }
                val folders = entries.mapNotNull { e ->
                    e.sourceFolder?.takeIf { it.isNotBlank() }?.let { "${e.privateUri}|$it" }
                }
                if (folders.isNotEmpty()) {
                    prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] =
                        (prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()) + folders
                }
                val names = entries.mapNotNull { e ->
                    e.originalName?.takeIf { it.isNotBlank() }?.let { "${e.privateUri}|$it" }
                }
                if (names.isNotEmpty()) {
                    prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] =
                        (prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()) + names
                }
                val cloudIds = entries.mapNotNull { e ->
                    e.cloudLinkId?.takeIf { it.isNotBlank() }?.let { "${e.privateUri}|$it" }
                }
                if (cloudIds.isNotEmpty()) {
                    prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] =
                        (prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()) + cloudIds
                }
                // Read inside the edit, so the favourite set and the cover map are the ones this write
                // is atomic with rather than a snapshot taken beside it.
                val favorites = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                val coverOf = FolderCoverMap.parse(prefs[SettingsKeys.FOLDER_COVER_URI_MAP])
                    .entries.associate { (folder, cover) -> cover to folder }
                val carried = entries.map { e ->
                    HiddenVaultCarry.encode(
                        e.privateUri,
                        CarriedPhoto(
                            sourceUri = e.sourceUri,
                            favorite = e.sourceUri in favorites,
                            userTagsCsv = userTagsBySource[e.sourceUri].orEmpty(),
                            coverOfFolder = coverOf[e.sourceUri].orEmpty(),
                            albumLinkIds = albumsBySource[e.sourceUri].orEmpty(),
                        ),
                    )
                }
                if (carried.isNotEmpty()) {
                    prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP] =
                        (prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP] ?: emptySet()) + carried
                }
            }
            HiddenVaultDiagnostics.journalled(entries.size)
            trace("journal: ${entries.size} entries in ${SystemClock.elapsedRealtime() - startedAt}ms")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "journal: failed to record ${entries.size} pending hide(s): ${e.message}")
            HiddenVaultDiagnostics.journalFailed(e.javaClass.simpleName)
            inFlightLock.withLock { inFlight -= entries.map { it.privateUri }.toSet() }
            false
        }
    }

    /**
     * Publishes as hidden the photos in [privateUris] whose originals have actually left the device, and
     * clears their journal entries in the same write, so the index gains them and the pending set loses
     * them together, never one without the other. Returns how many were NOT published because the device
     * still holds their original.
     *
     * Each original is verified against the record of where it was hidden from before its photo is
     * published: the hide's promise is that the original leaves the device, and a delete flow can report
     * success on a device that did not act on it, so a photo whose original is still there is a survivor
     * that must not be filed as hidden. A survivor is kept out of the hidden set, its now-redundant vault
     * copy dropped and its row returned to a live status, so this app never hides a photo whose file is
     * still in view of every other gallery app.
     *
     * The sync rows of the published photos go first, while the journal still names the originals they
     * belong to. An interruption between the two leaves the entries pending, which the next
     * reconciliation reads as a hide whose original is gone and publishes itself; the other order would
     * publish a photo whose Drive copy nothing had yet been told to drop, and nothing would come back
     * for it.
     */
    suspend fun confirm(privateUris: List<String>): Int {
        if (privateUris.isEmpty()) return 0
        val startedAt = SystemClock.elapsedRealtime()
        var survivorsKept = 0
        try {
            val sourceByVaultUri = HiddenVaultLeftovers.parseJournal(
                context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet(),
            )
            // One question per photo, asked of the ContentResolver: the source uri the hide keyed the
            // photo on, still resolving, is proof the delete this hide asked for did not remove it.
            val presentSources = withContext(Dispatchers.IO) {
                privateUris.mapNotNull { sourceByVaultUri[it] }.filterTo(HashSet()) { sourceExists(it) == true }
            }
            val split = HiddenVaultDecisions.confirmSplit(privateUris, sourceByVaultUri) { it in presentSources }
            // A survivor's original is the surviving copy, so dropping its vault copy loses nothing, and
            // its row is returned to view so the photo the hide could not remove is drawn again here.
            if (split.survivors.isNotEmpty()) {
                returnSurvivorsToView(split.survivors, sourceByVaultUri)
                discard(split.survivors)
                survivorsKept = split.survivors.size
            }
            val publish = split.publish
            val settled = if (publish.isEmpty()) SettledRows() else settleVaultedSyncRows(publish)
            if (publish.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.HIDDEN_PHOTO_URIS] =
                        (prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) + publish
                    prefs[SettingsKeys.HIDDEN_PENDING_HIDES] =
                        (prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())
                            .filterNot { it.substringBefore('|') in publish }
                            .toSet()
                }
            }
            HiddenVaultDiagnostics.confirmed(
                published = publish.size,
                hiddenRows = settled.rowsHidden,
                queueCleared = settled.queueCleared,
                survivorsKept = survivorsKept,
            )
            trace("confirm: ${publish.size} published, $survivorsKept kept in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The journal entry stays, so the next reconciliation finds the originals gone and
            // publishes these itself. Nothing is lost by the failure.
            Log.w(TAG, "confirm: failed for ${privateUris.size} hidden photo(s): ${e.message}")
        } finally {
            inFlightLock.withLock { inFlight -= privateUris.toSet() }
        }
        return survivorsKept
    }

    /**
     * Returns the sync row of each survivor to a live status, so a photo whose original the hide never
     * removed is drawn again rather than left filtered out by the HIDDEN row the delete flow set for it.
     *
     * A survivor's original is present by definition, so the row goes to SYNCED with a Drive copy or
     * LOCAL_ONLY without one ([HiddenVaultDecisions.revealedRowStatus]); a row already at a live status
     * is left alone. A failure is swallowed: the vault copy is being dropped either way, and the startup
     * sweep corrects a row this could not, since the discard has already taken the photo off the vault's
     * records.
     */
    private suspend fun returnSurvivorsToView(
        survivorVaultUris: List<String>,
        sourceByVaultUri: Map<String, String>,
    ) {
        for (vaultUri in survivorVaultUris) {
            val source = sourceByVaultUri[vaultUri]?.takeIf { it.isNotBlank() } ?: continue
            try {
                val row = syncStateRepo.getByUri(source) ?: continue
                val status = HiddenVaultDecisions.revealedRowStatus(
                    status = row.status,
                    hasCloudCopy = !row.cloudFileId.isNullOrBlank(),
                    deviceFileExists = true,
                ) ?: continue
                syncStateRepo.updateStatusAndDeleteLocal(source, status)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "confirm: a survivor's row not returned to view: ${e.message}")
            }
        }
    }

    /**
     * Settles the sync row of every photo in [privateUris] the vault has just taken: the back-up queue
     * lets go of all of them, and the ones with a Drive copy move to [SyncStatus.HIDDEN], the one
     * status a vaulted photo's row may carry.
     *
     * A backed-up photo is vaulted like any other — its device file moves and its original is
     * deleted — while its Drive copy stays exactly where it is. HIDDEN is what says so: every
     * reconcile, cleanup and upload pass skips such a row, and the gallery reads its cloudFileId as
     * the one Drive copy to leave out of every listing. Without it the vaulted photo's twin would go
     * on being drawn as an ordinary cloud photo, which is the hide only half done.
     *
     * The pairing and the original's uri are read from the records the hide wrote, so this works
     * the same for a photo whose surface knew it was backed up and for a device row that learnt it
     * only from the sync table. A row with no recorded Drive copy is a device-only photo and is left
     * to the delete's own transition.
     *
     * The write is an upsert rather than a status update, because a photo can be paired to its Drive
     * copy without any row at all: the gallery also pairs by content hash, by name and capture second
     * and by name alone, none of which puts anything in the sync table. A status update would then
     * touch nothing and the Drive copy would go on being drawn while its device file sat in the vault.
     *
     * A failure is swallowed: the bytes are in the vault and the original is gone, so refusing to
     * publish over a status write would put the photo nowhere at all.
     *
     * Answers with what it settled, since a hide reports on itself in counts and the two halves here
     * are the only ones the caller cannot see for itself.
     */
    private suspend fun settleVaultedSyncRows(privateUris: Collection<String>): SettledRows {
        if (privateUris.isEmpty()) return SettledRows()
        var queueCleared = 0
        var rowsHidden = 0
        try {
            val prefs = context.settingsDataStore.data.first()
            val wanted = privateUris.toSet()
            val sourceByVaultUri =
                HiddenVaultLeftovers.parseJournal(prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())

            // A photo queued for back-up before it was hidden keeps that intent on a row whose device
            // file the hide has just deleted. The upload pass claims a queued row by its status alone,
            // so the intent has to go with the file: otherwise a photo the user hid is still on the
            // list to be sent to Drive, and the attempt only fails once it reaches the dead uri.
            // Cleared for every vaulted photo, paired or not, since the queue is a device-side flag.
            for (vaultUri in wanted) {
                val sourceUri = sourceByVaultUri[vaultUri]?.takeIf { it.isNotBlank() } ?: continue
                syncStateRepo.clearQueuedForSynced(sourceUri)
                queueCleared++
            }

            val cloudIds = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
            if (cloudIds.isEmpty()) return SettledRows(rowsHidden, queueCleared)
            val pairedBy = HashMap<String, String>(wanted.size)
            for (entry in cloudIds) {
                val vaultUri = entry.substringBefore('|')
                val linkId = entry.substringAfter('|', "")
                if (vaultUri in wanted && linkId.isNotBlank()) pairedBy[vaultUri] = linkId
            }
            if (pairedBy.isEmpty()) return SettledRows(rowsHidden, queueCleared)
            val userId = accountManager.getPrimaryUserId().first()
                ?: return SettledRows(rowsHidden, queueCleared)
            for ((vaultUri, linkId) in pairedBy) {
                val sourceUri = sourceByVaultUri[vaultUri]?.takeIf { it.isNotBlank() } ?: continue
                val existing = syncStateRepo.getByUri(sourceUri)
                val row = existing?.copy(cloudFileId = linkId, status = SyncStatus.HIDDEN)
                    ?: SyncState(
                        localUri = sourceUri,
                        cloudFileId = linkId,
                        localHash = "",
                        cloudHash = null,
                        status = SyncStatus.HIDDEN,
                        lastSyncAttemptMs = System.currentTimeMillis(),
                        lastSyncSuccessMs = null,
                        // The vault never proved the bytes are on Drive, and a stamp is what lets the
                        // reclaim sweep delete a device file, so a row minted here states none.
                        backedUpAtMs = null,
                        sizeBytes = 0L,
                    )
                syncStateRepo.upsert(row, userId)
                rowsHidden++
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "confirm: sync rows not marked hidden: ${e.message}")
        }
        return SettledRows(rowsHidden, queueCleared)
    }

    /** What [settleVaultedSyncRows] settled: sync rows moved to the vaulted state, and pending
     *  upload intents dropped along with the device files they named. */
    private data class SettledRows(val rowsHidden: Int = 0, val queueCleared: Int = 0)

    /** Undoes a hide that never reached its delete: drops the vault copies and everything recorded
     *  for them. The originals are untouched, so the photos stay exactly where the user sees them. */
    suspend fun discard(privateUris: List<String>) {
        if (privateUris.isEmpty()) return
        try {
            withContext(Dispatchers.IO) { privateUris.forEach { hiddenStorage.delete(it) } }
            forget(privateUris)
            HiddenVaultDiagnostics.discarded(privateUris.size)
        } finally {
            inFlightLock.withLock { inFlight -= privateUris.toSet() }
        }
    }

    /**
     * Resolves everything an interrupted hide left behind, once per process. Cheap when there is
     * nothing to do: one directory listing, one preference read and one indexed row query.
     *
     * Each leftover is classified by [HiddenVaultLeftovers.decide] and then acted on — a copy is only
     * ever deleted for a leftover whose original is proven to still exist, and a vault file nothing
     * refers to is published rather than left invisible until the next sign-out takes it.
     *
     * [repairRevealedRows] runs alongside it for the other way a photo goes missing: a sync row still
     * claiming a photo the device has back.
     */
    suspend fun reconcile() {
        if (!reconciled.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // First, and outside the early returns below: a row the device contradicts is a repair of
            // its own, and it is due whether or not the vault's files and records agree with each other.
            repairRevealedRows()
            val busy = inFlightLock.withLock { inFlight.toSet() }
            val prefs = context.settingsDataStore.data.first()
            val journal = HiddenVaultLeftovers
                .parseJournal(prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())
                .filterKeys { it !in busy }
            val blobs = withContext(Dispatchers.IO) { hiddenStorage.vaultBlobUris() } - busy
            val recorded = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            if (journal.isEmpty() && blobs.all { it in recorded }) {
                HiddenVaultDiagnostics.reconciledNothing()
                return
            }

            val presence = withContext(Dispatchers.IO) {
                journal.values.distinct().mapNotNull { src -> sourceExists(src)?.let { src to it } }.toMap()
            }
            val leftovers = HiddenVaultLeftovers.leftovers(blobs, recorded, journal, presence)

            val discard = mutableListOf<String>()
            val confirm = mutableListOf<String>()
            val adopt = mutableListOf<String>()
            val forget = mutableListOf<String>()
            for (leftover in leftovers) {
                val action = HiddenVaultLeftovers.decide(leftover)
                trace("reconcile ${logRef(leftover.privateUri)}: $action")
                when (action) {
                    HiddenLeftoverAction.DISCARD -> discard += leftover.privateUri
                    HiddenLeftoverAction.CONFIRM -> confirm += leftover.privateUri
                    HiddenLeftoverAction.ADOPT -> adopt += leftover.privateUri
                    HiddenLeftoverAction.FORGET -> forget += leftover.privateUri
                    HiddenLeftoverAction.NONE -> Unit
                }
            }
            if (discard.isEmpty() && confirm.isEmpty() && adopt.isEmpty() && forget.isEmpty()) {
                HiddenVaultDiagnostics.reconciledNothing()
                return
            }

            // Bytes first: a death between here and the write below leaves a journal entry whose blob
            // is gone, which reads as FORGET next time. The reverse order would leave a copy nothing
            // refers to and re-adopt a photo the user can already see.
            if (discard.isNotEmpty()) {
                withContext(Dispatchers.IO) { discard.forEach { hiddenStorage.delete(it) } }
            }
            // The same status the ordinary hide writes, for the entries this pass is publishing on
            // its behalf. Runs before the write below, which is what clears the journal entries these
            // read the originals from. An adopted blob has no entry and therefore no pairing to mark.
            settleVaultedSyncRows(confirm)
            val published = confirm + adopt
            val cleared = (discard + confirm + forget).toSet()
            context.settingsDataStore.edit { p ->
                if (published.isNotEmpty()) {
                    p[SettingsKeys.HIDDEN_PHOTO_URIS] =
                        (p[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) + published
                }
                if (discard.isNotEmpty()) {
                    p[SettingsKeys.HIDDEN_PHOTO_URIS] =
                        (p[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) - discard.toSet()
                }
                p[SettingsKeys.HIDDEN_PENDING_HIDES] =
                    (p[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())
                        .filterNot { it.substringBefore('|') in cleared }
                        .toSet()
                val dropped = (discard + forget).toSet()
                if (dropped.isNotEmpty()) pruneMaps(p, dropped)
            }
            HiddenVaultDiagnostics.reconciled(
                confirm = confirm.size,
                adopt = adopt.size,
                discard = discard.size,
                forget = forget.size,
            )
            trace("reconcile: done in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (e: Exception) {
            if (e is CancellationException) {
                // The flag is claimed before a run's suspensions, and both callers live on a screen
                // scope that is cancelled the moment the user leaves. A cancelled run has repaired
                // nothing, so keeping the claim would disarm the repair for the rest of the process
                // and leave an interrupted hide unresolved.
                reconciled.set(false)
                throw e
            }
            Log.w(TAG, "reconcile: failed: ${e.message}")
        }
    }

    /**
     * Corrects every sync row the vault claims and the device contradicts, once per process.
     *
     * A row at [SyncStatus.HIDDEN] is the whole of what keeps a backed-up photo out of the listings:
     * the gallery drops the Drive copy that row's cloudFileId names, and the vault is expected to
     * hold the device file in its place. When a reveal puts the file back without settling the row,
     * the photo is left in neither place, filtered out of every listing while every other gallery app
     * on the device shows it. Nothing else corrects such a row: every reconcile, cleanup and upload
     * pass skips HIDDEN by design, which is what makes the state durable and this the repair for it.
     *
     * The strand takes two shapes and [HiddenVaultDecisions.vaultedRowFix] answers both. A reveal that
     * left the file on the vaulted row's OWN uri is returned in place, to SYNCED with a Drive copy or
     * to LOCAL_ONLY without one. A reveal that wrote the live SYNCED row on the uri the device minted
     * for the restored file, and left the HIDDEN row keyed on the dead pre-hide uri, is the other: that
     * row's own file resolves to nothing, so it is recognised by its cloud copy already carrying a live
     * row and is dropped rather than demoted, which is what keeps a single row on the one cloud copy. A
     * row whose file is gone with no live sibling is a correctly vaulted photo and is left untouched,
     * since the wrong answer in that direction reveals a photo the user meant to hide.
     *
     * The vault's own records are neither read nor written here. They say what the vault holds, while
     * this is only one row disagreeing with the device about one photo.
     */
    private suspend fun repairRevealedRows() {
        if (!revealedRowsRepaired.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                // Nothing to sweep and nothing proven, so the claim goes back: the rows belong to an
                // account, and a later run once one is primary is the run that can see them.
                revealedRowsRepaired.set(false)
                return
            }
            val rows = syncStateRepo.getVaulted(userId)
            if (rows.isEmpty()) {
                HiddenVaultDiagnostics.vaultedRowsSwept(examined = 0, synced = 0, localOnly = 0, stranded = 0)
                return
            }
            // The cloud copies this account already holds a live device-file row for. A vaulted row
            // whose cloud id is in here has a device file paired to that Drive copy under another row.
            val liveCloudIds = syncStateRepo.cloudIdsWithLivePairing(userId)
            // The cloud copies the vault's own records still name, keyed by uri as `vaultUri|linkId`. A
            // live sibling alone does not prove a hidden photo came back: a byte-identical duplicate the
            // user never hid pairs a second device file to the same Drive copy by content hash and reads
            // as a live sibling too. This is the authority that tells the two apart, so a row whose cloud
            // copy the vault still holds is left hidden however the rest of the table reads.
            val vaultHeldCloudIds = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP]
                .orEmpty()
                .mapNotNullTo(HashSet()) { it.substringAfter('|', "").takeIf { id -> id.isNotBlank() } }
            // One question per row, asked of the ContentResolver rather than of the uri's text: a uri
            // that reads like a media entry says nothing about a file existing, and it is the file
            // being there that makes the row wrong.
            val onDevice = withContext(Dispatchers.IO) {
                rows.mapNotNullTo(HashSet()) { row -> row.localUri.takeIf { sourceExists(it) == true } }
            }
            var synced = 0
            var localOnly = 0
            var stranded = 0
            for (row in rows) {
                val cloudId = row.cloudFileId
                val fix = HiddenVaultDecisions.vaultedRowFix(
                    status = row.status,
                    hasCloudCopy = !cloudId.isNullOrBlank(),
                    cloudHasLivePairing = cloudId != null && cloudId in liveCloudIds,
                    vaultStillHoldsPhoto = cloudId != null && cloudId in vaultHeldCloudIds,
                    ownFileExists = row.localUri in onDevice,
                )
                when (fix) {
                    VaultedRowFix.CLEAR_STRANDED -> {
                        // The photo is already back under a SYNCED row; this HIDDEN one only keeps the
                        // Drive copy filtered. Dropping it, not demoting it, is what avoids a second row
                        // on the one cloud copy.
                        syncStateRepo.delete(row.localUri)
                        stranded++
                    }
                    // The repository's status-only write, named for the delete flow it serves; it
                    // touches the one column and nothing else, so the row keeps its pairing and history.
                    VaultedRowFix.REVEAL_SYNCED -> {
                        syncStateRepo.updateStatusAndDeleteLocal(row.localUri, SyncStatus.SYNCED)
                        synced++
                    }
                    VaultedRowFix.REVEAL_LOCAL_ONLY -> {
                        syncStateRepo.updateStatusAndDeleteLocal(row.localUri, SyncStatus.LOCAL_ONLY)
                        localOnly++
                    }
                    VaultedRowFix.LEAVE -> {}
                }
                if (fix != VaultedRowFix.LEAVE) trace("sweep ${logRef(row.localUri)}: $fix")
            }
            HiddenVaultDiagnostics.vaultedRowsSwept(rows.size, synced, localOnly, stranded)
            trace("sweep: ${rows.size} rows in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (e: Exception) {
            if (e is CancellationException) {
                // Both callers live on a screen scope that is cancelled the moment the user leaves. A
                // cancelled run has repaired only what it got through, so the claim goes back and the
                // rest of the rows are swept by the next run rather than by no run at all.
                revealedRowsRepaired.set(false)
                throw e
            }
            Log.w(TAG, "sweep: vaulted rows not repaired: ${e.message}")
        }
    }

    /**
     * Bytes still missing for [requiredBytes] to fit alongside [HEADROOM_BYTES], or 0 when it fits.
     *
     * A hide copies the whole selection before a single original is deleted, so the volume has to
     * hold both at once. Checking up front turns "the disk filled up somewhere in the middle" into a
     * refusal the user can act on. Reads the data partition, the one the vault and the app cache live
     * on, the same way the storage figures in Settings do.
     */
    suspend fun spaceShortfallBytes(requiredBytes: Long): Long {
        if (requiredBytes <= 0L) return 0L
        val available = withContext(Dispatchers.IO) {
            runCatching { StatFs(Environment.getDataDirectory().absolutePath).availableBytes }
        }.getOrElse {
            // Unmeasurable free space is not a reason to refuse; the copy still reports its own failure.
            Log.w(TAG, "spaceShortfallBytes: free space unreadable: ${it.message}")
            HiddenVaultDiagnostics.spaceUnreadable()
            return 0L
        }
        val shortfall = (requiredBytes + HEADROOM_BYTES - available).coerceAtLeast(0L)
        HiddenVaultDiagnostics.spaceChecked(requiredBytes, shortfall)
        return shortfall
    }

    /**
     * A live, numbers-only picture of the vault for the diagnostics a tester hands over: whether the
     * index, the files on disk and the pending journal agree.
     *
     * The one question it answers is consistency, which is otherwise unanswerable without asking the
     * user to reproduce anything. `index` counts the vault entries the index lists, `filtered` the
     * entries that name no vault file (a hide that only ever filtered), and `blobs` what is actually
     * on disk. `missing` and `unindexed` are the two directions the first two can disagree in, and
     * either being non-zero is the tell for a leftover an interrupted hide left behind.
     *
     * Counts and byte totals only, exactly as the perf snapshot beside it: nothing here can name a
     * photo, a folder, a Drive copy or an account.
     */
    suspend fun vaultSnapshot(): String = try {
        val prefs = context.settingsDataStore.data.first()
        val indexed = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
        val entries = indexed.filterTo(HashSet()) { hiddenStorage.isHiddenUri(it) }
        val paired = HiddenVaultRecords.pairedUris(prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet())
        val pending = HiddenVaultLeftovers
            .parseJournal(prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet()).size
        val folders = (prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()).size
        val (blobs, sizeBytes) = withContext(Dispatchers.IO) {
            hiddenStorage.vaultBlobUris() to hiddenStorage.vaultSizeBytes()
        }
        buildString {
            append("index=").append(entries.size)
                .append(" filtered=").append(indexed.size - entries.size)
                .append(" blobs=").append(blobs.size).append('\n')
            append("missing=").append(entries.count { it !in blobs })
                .append(" unindexed=").append(blobs.count { it !in entries })
                .append(" diff=").append(blobs.size - entries.size).append('\n')
            append("paired=").append(entries.count { it in paired })
                .append(" pendingHides=").append(pending)
                .append(" folders=").append(folders).append('\n')
            append("size=").append(sizeBytes / (1024L * 1024L)).append("MB")
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        "unreadable (${e.javaClass.simpleName})"
    }

    /**
     * Clears the journal entries and per-uri records for [privateUris] without touching the index.
     *
     * Shared with [HiddenVaultRestorer], which runs it after a restore: a hide whose confirm never
     * landed still has an entry here, and leaving it would let the next reconciliation act on a photo
     * that is already back on the device.
     */
    suspend fun forget(privateUris: List<String>) {
        val keys = privateUris.toSet()
        try {
            context.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.HIDDEN_PENDING_HIDES] =
                    (prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())
                        .filterNot { it.substringBefore('|') in keys }
                        .toSet()
                pruneMaps(prefs, keys)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "forget: failed for ${privateUris.size} entr(ies): ${e.message}")
        }
    }

    /** Drops every `"uri|value"` entry keyed by one of [keys] from the four per-uri maps. */
    private fun pruneMaps(prefs: MutablePreferences, keys: Set<String>) {
        for (key in listOf(
            SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP,
            SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP,
            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
            SettingsKeys.HIDDEN_URI_CARRIED_MAP,
        )) {
            val existing = prefs[key] ?: continue
            prefs[key] = HiddenVaultRecords.dropped(existing, keys)
        }
    }

    /**
     * The album linkIds each source photo is queued to join, keyed by source uri.
     *
     * One read of the whole queue rather than a lookup per photo: the table holds only the photos
     * waiting to join an album, so it is small however large the library is, while a folder hide can
     * carry thousands of photos past this point.
     */
    private suspend fun albumTargetsFor(entries: List<Entry>): Map<String, List<String>> {
        val sources = entries.mapTo(HashSet(entries.size)) { it.sourceUri }
        return try {
            uploadAlbumTargetDao.getAll()
                .filter { it.localUri in sources }
                .groupBy({ it.localUri }, { it.albumLinkId })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "journal: album targets unreadable, hiding without them: ${e.message}")
            emptyMap()
        }
    }

    /**
     * The categories the user chose for each source photo, keyed by source uri, blank ones left out.
     *
     * A lookup per photo rather than one read of the whole cache: that cache holds a row per file the
     * device has ever shown, so reading it entire would put the library in memory to answer a question
     * about the handful of photos being hidden.
     */
    private suspend fun userTagsFor(entries: List<Entry>): Map<String, String> {
        val out = HashMap<String, String>()
        for (sourceUri in entries.mapTo(LinkedHashSet(entries.size)) { it.sourceUri }) {
            val csv = try {
                localTagDao.getUserTagsCsv(sourceUri)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "journal: categories unreadable, hiding without them: ${e.message}")
                return out
            }
            if (!csv.isNullOrEmpty()) out[sourceUri] = csv
        }
        return out
    }

    /**
     * Whether the MediaStore original at [sourceUri] still resolves. Null when the question cannot be
     * answered — an unparseable uri, a revoked permission, a provider that refuses the query — which
     * the decision treats as "unproven" and resolves towards keeping the vault copy.
     */
    private fun sourceExists(sourceUri: String): Boolean? {
        val parsed = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        if (parsed.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(
                parsed, arrayOf(MediaStore.MediaColumns._ID), null, null, null,
            )?.use { it.moveToFirst() }
        }.getOrNull()
    }

    /**
     * Verbose trace of one batch or one leftover, debug builds only, so a debug run reads as a full
     * walk of the vault while a release build costs nothing and stays quiet.
     *
     * A debug logcat is pasted into issues exactly as the shareable bundle is, so [message] carries
     * hashed refs and counts and never a name or a path.
     */
    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "HiddenVaultJournal"

        /** The vault's one short-token form, so every line about a photo reads the same way. */
        fun logRef(uri: String): String = HiddenVaultDiagnostics.ref(uri)

        /** Room left free after a hide. The copy is only half the operation — the system delete and
         *  MediaStore's own bookkeeping need space of their own, and a volume driven to zero fails in
         *  ways the user cannot act on. */
        const val HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}
