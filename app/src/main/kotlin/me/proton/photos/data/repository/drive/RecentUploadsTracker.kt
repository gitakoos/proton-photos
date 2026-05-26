package me.proton.photos.data.repository.drive

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks linkIds uploaded by this app session that haven't yet shown up in a full cloud
 * refresh. [PhotoStreamService] reads the snapshot to avoid deleting fresh uploads when
 * the photo stream API is temporarily unavailable.
 *
 * Persisted via [SettingsKeys.RECENT_UPLOAD_IDS] with a TTL of
 * [SettingsKeys.RECENT_UPLOAD_TTL_MS] so a process restart between upload and stream
 * catch-up doesn't reintroduce the "stream finds none, we delete the fresh upload" bug.
 *
 * In-memory we keep a `linkId → uploadedAtMs` map so [snapshotWithinMs] can return only
 * very-recent entries — when the photo stream call definitively succeeded, the long
 * (1h) TTL was over-protecting items the user had since deleted on the web. A tight
 * window in the success path is enough to guard the upload→stream-visibility race.
 */
@Singleton
class RecentUploadsTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** linkId → recorded-at-ms. Cleared on TTL expiry or [clearInMemory]. */
    private val inMemory = ConcurrentHashMap<String, Long>()
    @Volatile private var hydrated = false

    /**
     * Hydrates [inMemory] from DataStore. Drops expired entries on the way and rewrites
     * the persisted set if any survivors were trimmed. Idempotent and cheap to call.
     */
    suspend fun hydrate() {
        if (hydrated) return
        val now = System.currentTimeMillis()
        val raw = context.settingsDataStore.data.first()[SettingsKeys.RECENT_UPLOAD_IDS] ?: emptySet()
        val live = mutableSetOf<String>()
        for (encoded in raw) {
            val pipe = encoded.indexOf('|')
            if (pipe <= 0) continue
            val linkId = encoded.substring(0, pipe)
            val ts = encoded.substring(pipe + 1).toLongOrNull() ?: continue
            if (now - ts < SettingsKeys.RECENT_UPLOAD_TTL_MS) {
                inMemory[linkId] = ts
                live.add(encoded)
            }
        }
        if (live.size != raw.size) {
            context.settingsDataStore.edit { it[SettingsKeys.RECENT_UPLOAD_IDS] = live }
        }
        hydrated = true
    }

    /** Records [linkId] in the in-memory + persisted set. Pruned to TTL on every write. */
    suspend fun record(linkId: String) {
        hydrate()
        val now = System.currentTimeMillis()
        inMemory[linkId] = now
        context.settingsDataStore.edit { prefs ->
            val raw = prefs[SettingsKeys.RECENT_UPLOAD_IDS] ?: emptySet()
            val live = raw.filter { encoded ->
                val pipe = encoded.indexOf('|')
                if (pipe <= 0) return@filter false
                val ts = encoded.substring(pipe + 1).toLongOrNull() ?: return@filter false
                now - ts < SettingsKeys.RECENT_UPLOAD_TTL_MS
            }.toMutableSet()
            live.removeAll { it.startsWith("$linkId|") } // drop any old entry for this linkId
            live.add("$linkId|$now")
            prefs[SettingsKeys.RECENT_UPLOAD_IDS] = live
        }
    }

    /** Hydrates and returns a defensive snapshot of currently-tracked linkIds. */
    suspend fun snapshot(): Set<String> {
        hydrate()
        return inMemory.keys.toSet()
    }

    /**
     * Snapshot restricted to entries recorded within the last [maxAgeMs] milliseconds.
     *
     * Used by [PhotoStreamService.refreshCloudPhotos]'s delete-missing pass: when the
     * photo stream API returns successfully we have ground truth from the server, so the
     * only legitimate "don't delete" case is the narrow upload-just-completed-but-stream-
     * is-still-catching-up race. A 60-90 second window is plenty for that race; anything
     * longer over-protects items the user has since deleted on Drive web.
     */
    suspend fun snapshotWithinMs(maxAgeMs: Long): Set<String> {
        hydrate()
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return inMemory.asSequence()
            .filter { it.value >= cutoff }
            .map { it.key }
            .toSet()
    }

    /**
     * Drops [linkIds] from the in-memory tracker. Called when we KNOW the items are no
     * longer on the server (e.g. after a successful Drive delete) so a subsequent refresh
     * doesn't over-protect them — see the comment on [snapshotWithinMs] for the wider
     * protection-too-aggressive failure mode.
     */
    fun forget(linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        for (id in linkIds) inMemory.remove(id)
    }

    /** Wipes in-memory tracking (callers should also clear DataStore via [clearPersisted] on sign-out). */
    fun clearInMemory() {
        inMemory.clear()
        hydrated = false
    }
}
