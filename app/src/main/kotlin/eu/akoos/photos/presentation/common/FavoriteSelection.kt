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

package eu.akoos.photos.presentation.common

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.gallery.isItemFavorite
import eu.akoos.photos.presentation.viewer.favoriteIdsAfterToggle
import eu.akoos.photos.util.UploadPhotoTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which way a selection's favourite action goes: true adds the heart to every selected photo, false
 * takes it off every one of them.
 *
 * One photo in the selection that is not a favourite makes the whole press an add. The other reading
 * (any favourite means remove) would turn the ordinary case, a fresh selection that happens to hold
 * one already-favourite photo, into an un-favourite of that single photo and nothing at all for the
 * rest, which is the opposite of what the user asked for. Adding first also leaves the reverse one
 * press away: the selection stays put, so a second press with everything now favourited removes them.
 *
 * An empty selection answers false and has nothing to act on either way.
 */
fun favoriteTurnsOn(items: Collection<GalleryItem>, favoriteIds: Set<String>): Boolean =
    items.any { !isItemFavorite(it, favoriteIds) }

/**
 * The same rule for a surface whose selection is bare cloud photos rather than [GalleryItem]s.
 *
 * The device-side set is passed empty on purpose: it never speaks for a backed-up photo, whose heart
 * is Drive PhotoTag 0 and nothing else (see [isItemFavorite]), and every photo here is backed up.
 */
fun favoriteTurnsOnForCloudPhotos(photos: Collection<CloudPhoto>): Boolean =
    favoriteTurnsOn(photos.map { GalleryItem.CloudOnly(it) }, emptySet())

/**
 * [item] as it stands once a favourite write of [favorite] has landed on the photos [settledIds]
 * names, by [GalleryItem.stableId]. An item outside that set is returned untouched.
 *
 * A backed-up photo's heart is Drive PhotoTag 0, carried on the item itself, so a set of items put
 * together before the write answers for the photos as they were when they were picked. Every
 * surface holds such a set for the length of a selection, and [favoriteTurnsOn] reads it to name
 * the direction of the next press, so the tag has to be rewritten on the copies the surface holds
 * for that answer to follow the write.
 *
 * A device-only photo carries no tag: its heart is the device-side id set, which every surface
 * reads as a live flow, so it needs nothing here and is returned as it came.
 */
fun withFavoriteSettled(item: GalleryItem, settledIds: Set<String>, favorite: Boolean): GalleryItem =
    if (item.stableId !in settledIds) item else when (item) {
        is GalleryItem.LocalOnly -> item
        is GalleryItem.Synced    -> item.copy(cloud = item.cloud.withFavoriteTag(favorite))
        is GalleryItem.CloudOnly -> item.copy(cloud = item.cloud.withFavoriteTag(favorite))
    }

private fun CloudPhoto.withFavoriteTag(favorite: Boolean): CloudPhoto = copy(
    tags = if (favorite) tags + UploadPhotoTags.FAVORITE_TAG_ID
           else tags - UploadPhotoTags.FAVORITE_TAG_ID,
)

/** Progress of a batch favourite. [Working] counts settled photos so the dock item can ring. */
sealed class FavoriteActionState {
    data object Idle : FavoriteActionState()
    data class Working(val done: Int, val total: Int) : FavoriteActionState()
}

/**
 * Writes the favourite heart for a batch of photos, on whichever side records it for each one.
 *
 * A photo that lives only on the device is recorded in the device-side id set, a backed-up one in
 * Drive PhotoTag 0. That split is [favoriteIdsAfterToggle] and [isItemFavorite]'s, kept here in one
 * place so every selection surface writes what the viewer's single-photo heart writes.
 */
@Singleton
class FavoriteWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
) {
    /** The device-side favourite set, for a surface that has to decide which way its button goes. */
    val favoriteIds: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
        .distinctUntilChanged()

    /**
     * Puts [items] into [favorite], reporting how the batch ended.
     *
     * Photos already in the asked-for state are left alone: re-sending a tag the server already holds
     * buys nothing and would surface as a failure. The rest go one at a time, [onProgress] naming how
     * many have settled, and the device-side set is stored once at the end over the photos whose write
     * actually landed, so a refused cloud write leaves no local trace of a heart Drive never took.
     *
     * [onSettled] names each photo as its write lands, for a surface whose rows are a one-shot load
     * rather than a live flow and so have to be repainted from here.
     */
    suspend fun write(
        items: List<GalleryItem>,
        favorite: Boolean,
        onProgress: (done: Int) -> Unit = {},
        onSettled: (GalleryItem) -> Unit = {},
    ): FavoriteOutcome {
        val current = context.settingsDataStore.data.first()[SettingsKeys.FAVORITE_IDS] ?: emptySet()
        val targets = items.filter { isItemFavorite(it, current) != favorite }
        if (targets.isEmpty()) return favoriteOutcome(changed = 0, failed = 0)

        val settled = ArrayList<GalleryItem>(targets.size)
        var failed = 0
        var done = 0
        // A device-only photo is settled by the one store write below: no round trip, nothing to
        // refuse it. Counting those first keeps the ring moving while the cloud ones queue up.
        val (cloudItems, localItems) = targets.partition { it !is GalleryItem.LocalOnly }
        localItems.forEach {
            settled += it
            onSettled(it)
            onProgress(++done)
        }
        val userId = if (cloudItems.isEmpty()) null else accountManager.getPrimaryUserId().first()
        for (item in cloudItems) {
            val cloud = when (item) {
                is GalleryItem.Synced    -> item.cloud
                is GalleryItem.CloudOnly -> item.cloud
                is GalleryItem.LocalOnly -> null
            }
            // No signed-in user means no tag can be written, which counts as a refused write.
            val ok = cloud != null && userId != null &&
                driveRepo.setCloudFavorite(userId, cloud, favorite)
            if (ok) {
                settled += item
                onSettled(item)
            } else {
                failed++
            }
            onProgress(++done)
        }
        if (settled.isNotEmpty()) {
            context.settingsDataStore.edit { prefs ->
                val stored = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                val next = settled.fold(stored) { acc, item ->
                    favoriteIdsAfterToggle(item, acc, favorite)
                }
                if (next != stored) prefs[SettingsKeys.FAVORITE_IDS] = next
            }
        }
        return favoriteOutcome(changed = settled.size, failed = failed)
    }
}
