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
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.LocalTagDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes a photo the vault already holds: what it is called, when it was taken, and whether a second
 * copy of it joins it.
 *
 * A vaulted photo is an ordinary device photo that happens to live in app-private storage, so it takes
 * the same edits any other one does. What differs is where those edits land. A device photo keeps its
 * name and its date in a MediaStore row; a vaulted photo has no row, so its name lives in the vault's
 * own per-uri record and its date lives in the file name (see HiddenCaptureTime). Both are keyed by
 * the file's uri, and changing either renames the file — which changes that key. Doing the two halves
 * separately is what lets a rename be lost: the file moves, the records keep pointing at where it was,
 * and the reveal writes the OLD name back to the device. So both halves happen here, in one place, with
 * [HiddenVaultRecords] deciding what each record ends up holding.
 *
 * The same key reaches further than the vault's own records. A vaulted photo is offered a heart, its
 * categories and a pinned folder cover like any other, and all three are stored under its uri, so they
 * move with the file too — otherwise a rename quietly strips a photo of answers only the user could
 * give. And every screen showing the photo holds that uri as well, which is what [moves] is for.
 *
 * The file moves first. An interruption between the two then leaves a vault file no record names, which
 * the reconciliation adopts back as a hidden photo; the other order would leave every record pointing at
 * a file that is no longer there.
 */
@Singleton
class HiddenVaultEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hiddenStorage: HiddenStorageManager,
    private val localTagDao: LocalTagDao,
) {

    private val _moves = MutableStateFlow<Map<String, VaultMove>>(emptyMap())

    /**
     * Where every vault photo moved here has ended up, for the screens still holding it under the uri
     * it had. See [HiddenVaultMoves].
     *
     * A screen works from a list it took when the user opened it, and only this class can move the file
     * out from under one. Publishing the move is what lets the page keep showing the same photo instead
     * of a path nothing is at, whichever screen asked for the edit and whichever one is behind it.
     */
    val moves: StateFlow<Map<String, VaultMove>> = _moves.asStateFlow()

    /**
     * Renames the vaulted photo at [uri] to [newName], returning the uri it now lives under, or null
     * when the file could not be moved (ANOTHER vault file of that name is already there). A name that
     * resolves to the photo's own file answers with the uri it already has.
     *
     * The recorded name is REPLACED, not carried: it is what a reveal writes the photo back to the
     * device under, so a rename that left it alone would be undone by the reveal.
     */
    suspend fun rename(uri: String, newName: String): String? {
        val extension = extensionOf(uri)
        val newUri = hiddenStorage.rename(uri, newName) ?: return null
        val displayName = HiddenVaultRecords.recordedName(newName, extension)
        applyMove(uri, newUri, displayName)
        return newUri
    }

    /**
     * Copies the vaulted photo at [uri] into a second vaulted photo called [newName], returning the new
     * uri, or null when the copy could not be written.
     *
     * The copy stays in the vault. Writing it anywhere else would put the bytes the user asked to hide
     * into a plain visible file, which is the hide undone rather than a copy of it.
     */
    suspend fun copy(uri: String, newName: String): String? {
        val extension = extensionOf(uri)
        val copyUri = hiddenStorage.duplicate(uri) ?: return null
        val displayName = HiddenVaultRecords.recordedName(newName, extension)
        applyRecords(change = { records -> HiddenVaultRecords.copied(records, uri, copyUri, displayName) })
        return copyUri
    }

    /**
     * Records [copyUri] — a vault file already written by [HiddenStorageManager.create] — as a hidden
     * photo of its own, made from the one at [sourceUri] and shown as [displayName].
     *
     * The half of [copy] that turns bytes in the vault directory into a photo the vault knows about,
     * for a caller that produced those bytes itself rather than by duplicating a file.
     */
    suspend fun adoptCopy(sourceUri: String, copyUri: String, displayName: String) {
        applyRecords(
            change = { records -> HiddenVaultRecords.copied(records, sourceUri, copyUri, displayName) },
        )
    }

    /**
     * Rewrites the capture time the vaulted photo at [uri] carries to [captureTimeMs], returning the uri
     * it now lives under, or null when the file could not be moved.
     *
     * The name is the vault's only date store and it outranks the file's own EXIF everywhere the vault
     * reads a date, so a capture-date edit that stopped at the file's metadata would change nothing the
     * grid shows and nothing a reveal writes back. The recorded name is carried unchanged: only the
     * date moved.
     */
    suspend fun restampCaptureTime(uri: String, captureTimeMs: Long): String? {
        val newUri = hiddenStorage.restampCaptureTime(uri, captureTimeMs) ?: return null
        if (newUri == uri) return uri
        applyMove(uri, newUri, displayName = null)
        return newUri
    }

    /**
     * Follows the vault file that became [newUri] with everything keyed by the uri it had: the five
     * vault records, and the two stores the photo owns that the vault does not keep — its heart and any
     * folder cover pinned to it — all in the one edit [applyRecords] performs. [displayName] replaces
     * the recorded name for a rename and is null for a move that left the name alone.
     *
     * The categories follow separately, since they live in a table rather than in preferences, and then
     * the move is published so every screen still holding the old uri can find the photo again.
     *
     * A rename onto the name the file already has moves nothing, and only the records run: every store
     * keyed by the uri already answers under the one the photo keeps, and walking them anyway would take
     * each answer off the photo and put it back on the same photo — which for the categories, whose move
     * ends by dropping the row the old uri named, would be the choice deleted.
     */
    private suspend fun applyMove(oldUri: String, newUri: String, displayName: String?) =
        // The file has already moved by the time this runs, so the records naming it are the only
        // thing that can still find it. Both callers live on a screen scope, and returning from the
        // move is a cancellation point, so a back press between the two left the photo under a name
        // nothing referred to: its original filename and its Drive pairing were both unrecoverable.
        // Shielding the catch-up is what makes leaving the screen safe, exactly as the EXIF walk does.
        withContext(NonCancellable) { applyMoveRecords(oldUri, newUri, displayName) }

    private suspend fun applyMoveRecords(oldUri: String, newUri: String, displayName: String?) {
        val moved = oldUri != newUri
        applyRecords(
            change = { records -> HiddenVaultRecords.renamed(records, oldUri, newUri, displayName) },
            alsoEdit = { prefs ->
                if (moved) {
                    val favorites = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                    HiddenVaultRecords.favoritesAfterMove(favorites, oldUri, newUri)
                        ?.let { prefs[SettingsKeys.FAVORITE_IDS] = it }
                    val covers = prefs[SettingsKeys.FOLDER_COVER_URI_MAP] ?: emptySet()
                    HiddenVaultRecords.coversAfterMove(covers, oldUri, newUri)
                        ?.let { prefs[SettingsKeys.FOLDER_COVER_URI_MAP] = it }
                }
            },
        )
        if (!moved) return
        moveUserTags(oldUri, newUri)
        _moves.update { HiddenVaultMoves.folded(it, oldUri, newUri, displayName) }
    }

    /**
     * Moves the categories the user picked for the vault file at [oldUri] onto [newUri], dropping the
     * row the old uri named — nothing else will ever come for it, since that uri names no file.
     *
     * A row holding no choice is left alone: what it carries is a detection, which the scanner remakes
     * from the file whenever it needs it.
     *
     * A failure is swallowed. The photo has already moved and its name, date and heart went with it, so
     * losing a category is not worth reporting the whole edit as failed over.
     */
    private suspend fun moveUserTags(oldUri: String, newUri: String) {
        try {
            val csv = localTagDao.getUserTagsCsv(oldUri)
            if (csv.isNullOrEmpty()) return
            localTagDao.setUserTagsCsv(newUri, csv)
            localTagDao.deleteByUris(listOf(oldUri))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "move: categories not carried over: ${e.message}")
        }
    }

    /** Reads the five vault records, hands them to [change], and writes back what it answers — one
     *  atomic edit, so no record is ever left keyed to a file the others have moved on from. [alsoEdit]
     *  joins that same edit, for a store the vault does not own yet answers under a uri it just moved. */
    private suspend fun applyRecords(
        change: (VaultRecords) -> VaultRecords,
        alsoEdit: (MutablePreferences) -> Unit = {},
    ) {
        context.settingsDataStore.edit { prefs ->
            val updated = change(
                VaultRecords(
                    index = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet(),
                    names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet(),
                    folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet(),
                    cloudIds = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet(),
                    carried = prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP] ?: emptySet(),
                ),
            )
            prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = updated.index
            prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = updated.names
            prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = updated.folders
            prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = updated.cloudIds
            prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP] = updated.carried
            alsoEdit(prefs)
        }
    }

    /** The container extension the file at [uri] carries, which the recorded name reuses: the user
     *  renames the photo, never its format. */
    private fun extensionOf(uri: String): String =
        runCatching { File(Uri.parse(uri).path.orEmpty()).extension }.getOrDefault("")

    private companion object {
        const val TAG = "HiddenVaultEditor"
    }
}
