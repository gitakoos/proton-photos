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
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/** done/total of a folder-wide move in or out of the vault, so both directions report one shape. */
data class HiddenFolderProgress(val done: Int, val total: Int, val restoring: Boolean)

/**
 * Takes photos back out of the vault: one at a time, or a whole device folder at once.
 *
 * The counterpart of [HiddenVaultJournal], which puts them in. Restoring is the safer direction —
 * the vault copy is only deleted once its MediaStore row exists — so it needs no journal of its own,
 * but it does need one home: the vault screen, the folder screen and the undo action all restore, and
 * a photo has to come back with its capture date, its name, its original location, its cloud pairing
 * and everything the hide carried forward intact whichever of them asked.
 *
 * It also needs a life of its own. Every screen that reveals a photo can be gone the moment after it
 * asks — the viewer pops itself as soon as the reveal starts — and a reveal has a stretch where the
 * bytes have already moved and the records naming them have not gone yet. [HiddenRestoreRuns] is what
 * keeps that stretch off any scope a screen owns, so what a caller can still stop is which photos are
 * STARTED, never one already on its way back.
 */
@Singleton
class HiddenVaultRestorer @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val hiddenStorage: HiddenStorageManager,
    private val journal: HiddenVaultJournal,
    private val syncStateRepo: SyncStateRepository,
    private val accountManager: AccountManager,
    private val localTagDao: LocalTagDao,
    private val uploadAlbumTargetDao: UploadAlbumTargetDao,
    private val photoLocationDao: PhotoLocationDao,
) {

    private val runs = HiddenRestoreRuns(appScope)

    /**
     * Restore one vault photo to the device, drop everything the vault recorded for it, and clear its
     * folder if that was the last photo the vault held from it. Returns false when the photo did not
     * come back, so the screen that asked can say so. That includes a photo whose bytes landed while
     * a row went on marking it as vaulted, since that photo is not one the user can see.
     *
     * [fallbackDisplayName] is used only when no name was recorded at hide time: the vault file lives
     * under a private code, so restoring without a name would put that code on the user's device.
     *
     * The whole of it runs detached, tail included: the folder's own record is cleared here, and a
     * caller that goes away between the file landing and that clear would leave a folder holding
     * nothing yet still listed as hidden.
     */
    suspend fun restorePhoto(uri: String, fallbackDisplayName: String? = null): Boolean = runs.detached {
        val buckets = recordedBucketsOf(listOf(uri))
        val tally = RevealTally()
        HiddenVaultDiagnostics.revealStarted(1)
        val outcome = restoreOne(uri, tally, fallbackDisplayName)
        if (outcome.clearsRecords) journal.forget(listOf(uri))
        clearEmptiedFolders(buckets)
        HiddenVaultDiagnostics.revealFinished(
            revealed = if (outcome.revealed) 1 else 0,
            failed = if (outcome.revealed) 0 else 1,
            tally = tally,
        )
        outcome.revealed
    }

    /**
     * Destroy one vault photo: the file, the index entry, everything else recorded for it, and its
     * folder when the vault holds nothing more from it. Returns false when [uri] names no vault file,
     * or when the file is still there afterwards, so a caller never reports a delete that did not
     * happen.
     *
     * A vaulted photo has no MediaStore row and no device original — the hide removed it — so there
     * is no system trash to move it to and nothing on the cloud side to answer for. That makes the
     * whole delete the vault's own, and [HiddenVaultJournal.discard] is already exactly the half of
     * it that drops the bytes together with the journal entry and the four per-uri maps, leaving
     * only the index entry for the write below.
     *
     * The bytes go FIRST, matching every other path that destroys a vault copy: an interruption
     * between the two leaves an index entry naming a file that is gone, which surfaces nowhere and
     * clears itself on the next reveal. The other order would leave the file behind with nothing
     * referring to it, and the next reconciliation would adopt the photo straight back in.
     *
     * A photo that was backed up keeps its Drive copy — the vault never touched it — so the delete
     * ends by giving that copy back to the gallery. The vault held the only device file left, and
     * with it gone the photo is genuinely a cloud photo again; leaving its row as HIDDEN would keep
     * the Drive copy out of every listing with nothing anywhere still able to bring it back.
     *
     * The source folders and the recorded pairing are read BEFORE the discard, since discarding is
     * also what prunes the records that name them.
     *
     * Detached for the same reason a reveal is: the bytes go first, so a caller that goes away
     * mid-delete would otherwise leave the index entry and the folder record standing over nothing.
     */
    suspend fun deletePhoto(uri: String): Boolean = runs.detached {
        if (!hiddenStorage.isHiddenUri(uri)) return@detached false
        val buckets = recordedBucketsOf(listOf(uri))
        val cloudLinkId = recordedCloudLinkId(uri)
        journal.discard(listOf(uri))
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.HIDDEN_PHOTO_URIS] = (p[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) - uri
        }
        releaseCloudTwin(cloudLinkId)
        clearEmptiedFolders(buckets)
        withContext(Dispatchers.IO) { !hiddenStorage.hasBlob(uri) }
    }

    /** The Drive copy the hide recorded for [uri], or null when the photo had none. */
    private suspend fun recordedCloudLinkId(uri: String): String? =
        context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP]
            ?.firstOrNull { it.startsWith("$uri|") }
            ?.substringAfter('|')
            ?.takeIf { it.isNotBlank() }

    /**
     * Put the row of [cloudLinkId]'s photo back to CLOUD_ONLY, so a Drive copy whose vault file has
     * just been destroyed is drawn again instead of staying dropped by the vault's own filter.
     *
     * Only a row still marked HIDDEN is touched: any other status belongs to a photo that has since
     * been revealed or re-paired, and that row already says something truer than this would.
     *
     * A failure is swallowed: the vault file is gone either way, and a photo drawn one refresh late
     * is not worth reporting a delete as failed over.
     */
    private suspend fun releaseCloudTwin(cloudLinkId: String?) {
        if (cloudLinkId == null) return
        try {
            val row = syncStateRepo.getByCloudId(cloudLinkId) ?: return
            if (row.status != SyncStatus.HIDDEN) return
            syncStateRepo.updateStatusAndDeleteLocal(row.localUri, SyncStatus.CLOUD_ONLY)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "delete: cloud twin not released: ${e.message}")
        }
    }

    /**
     * The one way a photo comes back, whichever screen asked and however many of them ask at once.
     *
     * Every caller goes through here, which is what makes the register in [HiddenRestoreRuns] worth
     * anything: a photo a folder reveal is already moving, and the same photo tapped in the viewer,
     * meet on the one run rather than each moving the same bytes.
     */
    private suspend fun restoreOne(
        uri: String,
        tally: RevealTally,
        fallbackDisplayName: String? = null,
    ): HiddenRestoreOutcome = runs.forPhoto(uri) { roundTrip(uri, tally, fallbackDisplayName) }

    /**
     * The round trip for one photo: the file back to the device, then the index entry and the four
     * per-uri maps, but ONLY once the file is somewhere the user can reach it AND no row still marks
     * the photo as vaulted.
     *
     * A failed write leaves the vault file exactly where it was, so dropping the records would leave a
     * photo whose name, folder, cloud pairing and carried state are gone while its bytes sit under a
     * private code — adopted nameless by the next reconciliation, and destroyed by a sign-out before
     * that.
     *
     * A row left at [SyncStatus.HIDDEN] costs the same in the other direction. That status is what
     * drops the photo's Drive copy from every listing this app draws, so a reveal that put the bytes
     * back and left the row alone leaves the photo in neither place: visible to every other gallery
     * app on the device and absent from this one. The row is therefore settled INSIDE the restore,
     * before the vault copy is released, and a reveal that could not settle it keeps its copy, keeps
     * its records and reports itself as failed.
     */
    private suspend fun roundTrip(
        uri: String,
        tally: RevealTally,
        fallbackDisplayName: String? = null,
    ): HiddenRestoreOutcome {
        val ref = HiddenVaultDiagnostics.ref(uri)
        val startedAt = SystemClock.elapsedRealtime()
        // The cloud linkId stashed at hide time lets the existing SyncState row be transplanted onto
        // the freshly-restored MediaStore uri. Without it reconcile reads the restored file as a
        // brand-new local photo and uploads a duplicate.
        val prefs = context.settingsDataStore.data.first()
        val cloudLinkId = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP]
            ?.firstOrNull { it.startsWith("$uri|") }
            ?.substringAfter('|')
        // Source folder and original filename, both recorded at hide time so the file returns to
        // where it came from under the name it had. Absent for entries hidden before those maps
        // existed — restore then keeps its root default and generates a name.
        val sourceFolder = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP]
            ?.firstOrNull { it.startsWith("$uri|") }
            ?.substringAfter('|')
        val storedName = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP]
            ?.firstOrNull { it.startsWith("$uri|") }
            ?.substringAfter('|')
        // The heart, the categories, the pinned folder cover and the album queue the photo owned on
        // the device, all of them keyed by a uri the hide destroyed and the restore is about to
        // replace. Absent for a photo that owned none of them, and for one hidden before the record
        // existed — such a photo comes back with its bytes and its date, as it always did.
        val carried = HiddenVaultCarry.carriedBy(prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP], uri)
        // The uri the photo was hidden from, which is what keys the sync row the hide marked vaulted.
        // The carried record holds it for every photo the vault took; the pending journal holds it for
        // a hide whose confirm never landed, and for a photo vaulted before the record was written it
        // is unknown, which leaves the restored uri as the one handle below.
        val sourceUri = carried?.sourceUri?.takeIf { it.isNotBlank() }
            ?: HiddenVaultLeftovers
                .parseJournal(prefs[SettingsKeys.HIDDEN_PENDING_HIDES] ?: emptySet())[uri]
                ?.takeIf { it.isNotBlank() }
        // A heart given to the photo while it sat in the vault is keyed by the vault uri, so it is the
        // reveal's to move exactly as much as anything the hide carried in — and the photo owning it may
        // be one the hide recorded nothing for, which is how the ordinary photo is held.
        val heartedInVault = prefs[SettingsKeys.FAVORITE_IDS]?.contains(uri) == true

        val isVaultEntry = hiddenStorage.isHiddenUri(uri)
        var restored: HiddenStorageManager.RestoredFile? = null
        var revival = HiddenRowRevival.UNCLAIMED
        val vaultFileExists = withContext(Dispatchers.IO) {
            if (!isVaultEntry || !hiddenStorage.hasBlob(uri)) return@withContext false
            restored = hiddenStorage.restore(
                uri,
                storedName ?: fallbackDisplayName,
                albumFolderName = sourceFolder,
                // Handed to the restore rather than run after it, so the pairing is on the restored
                // uri before the file is published. A sync pass that reached the file first would
                // read it as a photo this device has never backed up and queue a second upload of a
                // photo that is already on Drive.
                onRestoredUri = { restoredUri ->
                    val move =
                        transplantHiddenSyncState(syncStateRepo, accountManager, cloudLinkId, restoredUri)
                    tally.count(move)
                    revival = reviveVaultedRow(move, sourceUri, restoredUri)
                    tally.count(revival)
                    trace("reveal $ref: pairing $move, row $revival")
                    revival.settled
                },
            )
            true
        }
        val restoredUri = restored?.uri
        if (restored?.viaOriginalPath == true) tally.originalPathBranch++
        else if (restored != null) tally.mediaStoreBranch++
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = isVaultEntry,
            vaultFileExists = vaultFileExists,
            restoredUri = restoredUri,
            // A reveal that never reached the restore settled no row and needs none: its photo is
            // either still in the vault or was never a vault file at all.
            rowSettled = restored?.recordsSettled ?: true,
        )
        // Put back what the photo carried in BEFORE the record naming it goes, so an interruption
        // between the two leaves a record still pointing at a photo that came back rather than a photo
        // whose heart and categories nothing holds any more.
        restoredUri?.let { restoredTo ->
            // Every vaulted photo has a carried record, since it also holds the uri the photo was
            // hidden from, so what is asked here is whether the photo owns any of the four stores the
            // reapply writes. An ordinary photo owns none and the write is skipped whole.
            if (carried?.isWorthCarrying == true || heartedInVault) {
                reapplyCarried(carried, uri, restoredTo)
                tally.carriedReapplied++
            }
            // Carry the photo's stored map location from the uri it was hidden from onto the uri it
            // came back as. The location row is keyed by uri and the restore mints a fresh one, so
            // without this a revealed photo keeps its pin on a uri nothing shows and drops off the map.
            // Ungated on isWorthCarrying: an ordinary photo carrying nothing else can still carry one.
            sourceUri?.let { from ->
                if (from != restoredTo) runCatching { photoLocationDao.rekey(from, restoredTo) }
            }
        }
        // Both halves on the one line, so a report of a photo that came back yet cannot be seen is
        // answered from the log rather than from a database pull.
        val fileHalf = when {
            restored == null -> "none"
            restored?.viaOriginalPath == true -> "originalPath"
            else -> "mediaStore"
        }
        trace("reveal $ref: $outcome file=$fileHalf row=$revival in ${SystemClock.elapsedRealtime() - startedAt}ms")
        if (outcome.clearsRecords) {
            tally.recordsDropped++
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.HIDDEN_PHOTO_URIS] = (p[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) - uri
                for (key in listOf(
                    SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
                    SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP,
                    SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP,
                    SettingsKeys.HIDDEN_URI_CARRIED_MAP,
                )) {
                    val existing = p[key] ?: continue
                    p[key] = HiddenVaultRecords.dropped(existing, setOf(uri))
                }
            }
            // With the records gone the vault answers for this photo not at all, so its Drive copy
            // must not stay dropped by the vault's own filter. A reveal that re-paired has already
            // retired the row this reads, so nothing happens for it; what is left are the reveals
            // that could not — the vault file gone, or a file handed back somewhere the media index
            // has not named yet — and for those the Drive copy is what has to come back into view.
            releaseCloudTwin(cloudLinkId)
        }
        return outcome
    }

    /**
     * Return to a live status every sync row that still marks the photo as vaulted, and answer which
     * handle found it.
     *
     * [SyncStatus.HIDDEN] is the whole of what keeps a backed-up photo out of the listings: the gallery
     * drops the Drive copy that row's cloudFileId names, and the vault is expected to hold the device
     * file in its place. The file is back, so any such row now describes a photo that is not hidden and
     * is filtered out of this app while every other gallery app on the device shows it.
     *
     * A pairing that MOVED already rewrote that row onto the restored photo and deleted the one the
     * hide wrote, so it answers [HiddenRowRevival.PAIRED] and touches nothing. Every other pairing
     * answer leaves a row to look for, and the one the vault was blind to is the pairing it never
     * recorded: with no Drive copy to search by, the uri the photo was hidden from is the handle, and
     * the uri it came back as covers a row a sync pass minted for the restored file in the meantime.
     *
     * The status is [HiddenVaultDecisions.revealedRowStatus]'s to pick, so a row the vault never
     * claimed is left to whatever owns it. The device file is stated as present rather than asked
     * about, since this runs on the uri the restore has just written.
     *
     * A failure is the one thing here that must NOT be swallowed: the caller releases the vault copy on
     * this answer, and a photo whose row goes on hiding it has to keep the copy the hidden area offers
     * it from.
     */
    private suspend fun reviveVaultedRow(
        move: HiddenPairingMove,
        sourceUri: String?,
        restoredUri: String,
    ): HiddenRowRevival {
        if (!HiddenVaultDecisions.revealNeedsRowRepair(move)) return HiddenRowRevival.PAIRED
        return try {
            var found = HiddenRowRevival.UNCLAIMED
            for (candidate in HiddenVaultDecisions.revealRowCandidates(sourceUri, restoredUri)) {
                val row = syncStateRepo.getByUri(candidate) ?: continue
                val status = HiddenVaultDecisions.revealedRowStatus(
                    status = row.status,
                    hasCloudCopy = !row.cloudFileId.isNullOrBlank(),
                    deviceFileExists = true,
                ) ?: continue
                syncStateRepo.updateStatusAndDeleteLocal(candidate, status)
                // The first handle that answered is what the log reports, while every handle that
                // answers is repaired: two rows can name one photo, and either left at HIDDEN goes
                // on dropping the Drive copy.
                if (found == HiddenRowRevival.UNCLAIMED) {
                    found = if (candidate == sourceUri) HiddenRowRevival.BY_SOURCE
                    else HiddenRowRevival.BY_RESTORED
                }
            }
            found
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "reveal: vaulted row not returned to a live status: ${e.message}")
            HiddenRowRevival.STRANDED
        }
    }

    /**
     * Move everything [carried] describes, and any heart the photo was given while it sat at [vaultUri],
     * onto [restoredUri] — and off both uris that named the photo before, the one it was hidden from and
     * the vault's own.
     *
     * Every half matters. Writing the new uri is what gives the photo its heart, its categories, its
     * folder cover and its place in an album queue back; clearing the two old ones is what keeps the
     * answer single, since neither names a file any more and nothing else would ever come to collect
     * them.
     *
     * [carried] is null for a photo the hide recorded nothing for, which still leaves a heart given in
     * the vault to move, and that heart is then the whole of it.
     *
     * What makes any of this reach the photo is [restoredUri] being the uri the gallery lists it under,
     * which is why [HiddenStorageManager.restore] answers with the uri the media index minted rather
     * than with the path the bytes went to.
     *
     * A failure is swallowed: the photo is already back on the device, and losing a heart is not worth
     * reporting a reveal as failed over.
     */
    private suspend fun reapplyCarried(carried: CarriedPhoto?, vaultUri: String, restoredUri: String) {
        try {
            context.settingsDataStore.edit { p ->
                val favorites = p[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                val next = HiddenVaultCarry.favoriteIdsAfterRestore(favorites, carried, vaultUri, restoredUri)
                if (next != favorites) p[SettingsKeys.FAVORITE_IDS] = next
                val covers = p[SettingsKeys.FOLDER_COVER_URI_MAP] ?: emptySet()
                carried?.let { HiddenVaultCarry.coversAfterRestore(covers, it, restoredUri) }
                    ?.let { p[SettingsKeys.FOLDER_COVER_URI_MAP] = it }
            }
            if (carried == null) return
            if (carried.userTagsCsv.isNotEmpty()) {
                localTagDao.setUserTagsCsv(restoredUri, carried.userTagsCsv)
                // The old row survives a media scan precisely BECAUSE it carries a user choice, so
                // nothing else will ever take it; the choice now lives on the restored uri.
                if (carried.sourceUri.isNotEmpty()) localTagDao.deleteByUris(listOf(carried.sourceUri))
            }
            if (carried.albumLinkIds.isNotEmpty()) {
                for (albumLinkId in carried.albumLinkIds) {
                    uploadAlbumTargetDao.insertIgnore(restoredUri, albumLinkId)
                }
                if (carried.sourceUri.isNotEmpty()) uploadAlbumTargetDao.deleteFor(carried.sourceUri)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "restore: carried state not fully reapplied: ${e.message}")
        }
    }

    /**
     * Take a whole set of photos back out of the vault and leave NOTHING the hide wrote behind.
     *
     * Each photo drops its index entry and its four per-uri maps, which is everything a single hidden
     * photo owns. A batch owns two records more: the journal entry, still there for any hide whose
     * confirm did not land, and — when the batch came from a folder hide — the folder's own name.
     * Clearing both is what leaves the vault as it was before the hide, rather than a photo that is
     * back on the device yet still listed as hidden. This is the whole reversal an undo needs, and the
     * same one a reveal from inside a hidden folder runs.
     *
     * A photo whose file could not be written back keeps ALL of it, journal entry included, so the
     * vault file stays reachable under its own name. So does one whose row goes on marking it as
     * vaulted: its bytes are on the device and this app still drops its Drive copy, so the hidden area
     * has to keep offering it. Returns how many photos did not come back, counting both, so the screen
     * that asked can say so.
     *
     * The source folders are read BEFORE the first restore, since restoring is also what prunes the
     * records that name them.
     *
     * The batch is detached whole. Nothing offers to stop an undo or a selection part-way, so there is
     * no cancellation here worth honouring, and the two records this owns beyond the single photos —
     * the journal entries and the folder names — are cleared once at the end, where a caller walking
     * off would take them with it.
     */
    suspend fun restoreAll(uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        return runs.detached {
            val buckets = recordedBucketsOf(uris)
            val tally = RevealTally()
            HiddenVaultDiagnostics.revealStarted(uris.size)
            val kept = mutableSetOf<String>()
            var failed = 0
            for (uri in uris) {
                val outcome = restoreOne(uri, tally)
                if (!outcome.clearsRecords) kept += uri
                if (!outcome.revealed) failed++
            }
            journal.forget(uris - kept)
            clearEmptiedFolders(buckets)
            HiddenVaultDiagnostics.revealFinished(uris.size - failed, failed, tally)
            failed
        }
    }

    /**
     * Restore every vaulted photo recorded as coming from [bucketName], reporting progress as it goes,
     * and clear the folder from the hidden set once nothing of it is left in the vault.
     *
     * [shouldStop] is polled between photos so the user can stop a folder holding thousands. What has
     * been restored stays restored and the rest stays in the vault, which is why the folder name is
     * cleared LAST and only when the vault holds nothing more from it: a folder with photos still in
     * the vault has to keep its name, or the vault loses the only handle it has on them. A photo that
     * could not be written back is one of those, and counts towards the returned failure total.
     *
     * This is the one reveal that stays the caller's to stop, so the loop deliberately keeps running
     * on the caller's own coroutine: both the Stop button and the screen going away end it BETWEEN
     * photos, and progress goes on being reported straight to the screen that asked. Only each photo
     * is detached, which is the whole distinction — what stops is which photos are started, never one
     * whose bytes are already moving.
     *
     * A cancelled caller is the one ending that cannot clear the folder name itself, so it hands that
     * last step to the app's own scope: a screen going away right after the last photo would otherwise
     * leave the folder hidden over an empty vault. Every other ending, the Stop button included,
     * clears it in place, where a hide of the same folder started straight afterwards cannot have its
     * fresh name taken back out from under it.
     */
    suspend fun restoreFolder(
        bucketName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): Int {
        if (bucketName.isBlank()) return 0
        val targets = vaultedUrisOf(bucketName)
        // A folder hidden before anything of it was vaulted has nothing to report, and a progress
        // surface raised for zero photos would only flash.
        if (targets.isNotEmpty()) onProgress(0, targets.size)
        val tally = RevealTally()
        HiddenVaultDiagnostics.revealStarted(targets.size)
        var done = 0
        var failed = 0
        try {
            for (uri in targets) {
                if (shouldStop()) break
                if (!restoreOne(uri, tally).revealed) failed++
                done++
                onProgress(done, targets.size)
            }
        } catch (e: CancellationException) {
            appScope.launch { clearEmptiedFolders(setOf(bucketName)) }
            throw e
        } finally {
            // In a finally so a stop and a screen going away both report what they got through, which
            // is the one ending where a partial count is the whole answer.
            HiddenVaultDiagnostics.revealFinished(done - failed, failed, tally)
        }
        clearEmptiedFolders(setOf(bucketName))
        return failed
    }

    /** The vault uris recorded as coming from [bucketName], newest first. Read fresh each time, so a
     *  restore that stops part-way can be resumed against what is actually left. */
    private suspend fun vaultedUrisOf(bucketName: String): List<String> {
        val prefs = context.settingsDataStore.data.first()
        return HiddenFolderRecords.vaultedByBucket(
            sourceFolderEntries = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet(),
            vaultedUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet(),
        )[bucketName].orEmpty()
    }

    /** The folders [uris] were recorded as coming from, keyed the way every per-folder preference is:
     *  by bucket display name. */
    private suspend fun recordedBucketsOf(uris: List<String>): Set<String> {
        val keys = uris.toSet()
        val entries = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP]
            ?: return emptySet()
        return entries.mapNotNullTo(mutableSetOf()) { entry ->
            if (entry.substringBefore('|') !in keys) null
            else HiddenFolderRecords.bucketOf(entry.substringAfter('|', "")).takeIf { it.isNotBlank() }
        }
    }

    /**
     * Drop from the hidden set every folder in [buckets] the vault holds nothing more from, so a
     * folder emptied by a restore returns to the Albums grid on its own.
     *
     * A folder with photos still in the vault keeps its name, since the name is the only handle the
     * vault has on them: that is why the check runs against what is actually left rather than against
     * what the caller intended to restore.
     */
    private suspend fun clearEmptiedFolders(buckets: Set<String>) {
        if (buckets.isEmpty()) return
        // A photo revealed on its own usually names a folder that was never hidden, and an edit that
        // removes nothing still re-emits to every collector.
        val hidden = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()
        val emptied = buckets.filter { it.isNotBlank() && it in hidden && vaultedUrisOf(it).isEmpty() }.toSet()
        if (emptied.isEmpty()) return
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.HIDDEN_FOLDER_NAMES] = (p[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()) - emptied
        }
    }

    /**
     * Verbose per-photo trace, debug builds only, so a debug run reads as a full walk of the reveal
     * while a release build costs nothing and stays quiet.
     *
     * A debug logcat is pasted into issues exactly as the shareable bundle is, so [message] carries
     * hashed refs and outcomes and never a name or a path.
     */
    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "HiddenVaultRestorer"
    }
}

/** What a reveal's attempt to carry the Drive pairing onto the restored photo came to. */
enum class HiddenPairingMove {
    /** The row now names the restored photo. */
    MOVED,

    /** The restored uri is not one the media index minted, so the pairing stays where it is. */
    SKIPPED,

    /** The photo has no Drive copy, so there was no pairing to move. */
    ABSENT,

    /** There is a pairing to move and the rows could not be rewritten. */
    FAILED,
}

/**
 * Carry a restored photo's SyncState row from the vault uri it was hidden under onto the MediaStore
 * uri it just came back as, so the photo goes on being the same backed-up photo it was.
 *
 * Without it the restored file has no row at all: reconcile cannot match it by id or hash, falls
 * through to matching by name and date, and on any drift across the hide cycle uploads it again as a
 * duplicate. The pairing is carried by Drive linkId and by nothing else, so a video whose timestamp
 * the reveal rewrote — which changes its bytes and therefore its hash — comes back paired all the
 * same.
 *
 * The row moves in three steps and the order of them is the whole safety of it. The fresh SYNCED row
 * on [restoredUri] goes first, carrying the hash and the backed-up timestamp forward so the row's
 * history does not reset; it is written through an upsert, so it also takes over a row a sync pass
 * has already created for the restored file. Its queue intent is then cleared, since a row created
 * that way arrives queued for the upload the pairing has just made unnecessary. The old row goes
 * last and is DELETED rather than demoted: its uri names a MediaStore row the hide removed for good,
 * and a demoted row would leave two rows pointing at one Drive copy, which is a question with no
 * answer the moment anything asks which local file that copy belongs to.
 *
 * Which [restoredUri] may take the pairing at all is [HiddenVaultDecisions.transplantedPairing]'s to
 * answer, and one it refuses leaves every row exactly as it is. The reveal then drops the vault's own
 * marker from the row the pairing is already on, which is what lets the sync pass pair the restored
 * file to the very same Drive copy — by content hash, or by name and capture second for a file whose
 * bytes the reveal rewrote — as soon as the media index names it.
 *
 * A failure is swallowed: the photo is already back on the device, and the worst that follows is one
 * duplicate upload, which is not worth failing a restore over.
 */
suspend fun transplantHiddenSyncState(
    syncStateRepo: SyncStateRepository,
    accountManager: AccountManager,
    cloudLinkId: String?,
    restoredUri: String?,
): HiddenPairingMove {
    val pairing = HiddenVaultDecisions.transplantedPairing(cloudLinkId, restoredUri)
        ?: return if (cloudLinkId.isNullOrBlank()) HiddenPairingMove.ABSENT else HiddenPairingMove.SKIPPED
    return try {
        val userId = accountManager.getPrimaryUserId().first() ?: return HiddenPairingMove.FAILED
        val oldRow = syncStateRepo.getByCloudId(pairing.cloudLinkId) ?: return HiddenPairingMove.FAILED
        syncStateRepo.upsert(oldRow.copy(localUri = pairing.restoredUri, status = SyncStatus.SYNCED), userId)
        syncStateRepo.clearQueuedForSynced(pairing.restoredUri)
        if (oldRow.localUri != pairing.restoredUri) syncStateRepo.delete(oldRow.localUri)
        // getByCloudId returns ONE row, but the table can hold more than one for a single cloud copy: a
        // sync pass that reached the restored file first mints a SYNCED row on it, so getByCloudId
        // returns that and the delete above cancels itself, leaving the HIDDEN row the hide wrote in
        // place. Its cloudFileId then goes on dropping the Drive copy from every listing while the file
        // sits in plain view of every other gallery app. Drop any HIDDEN sibling for this cloud id so
        // none outlives the reveal; the row just written is SYNCED, so it is never the one cleared.
        syncStateRepo.clearHiddenForCloudId(pairing.cloudLinkId)
        HiddenPairingMove.MOVED
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        HiddenPairingMove.FAILED
    }
}
