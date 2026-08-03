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

package eu.akoos.photos.presentation.metadata

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.hidden.HiddenVaultEditor
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.ExifAsciiText
import eu.akoos.photos.domain.usecase.MetadataWriteResult
import eu.akoos.photos.domain.usecase.TextTagEdit
import eu.akoos.photos.domain.usecase.WriteLocalPhotoMetadataUseCase
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.PhotoGpsResolver
import eu.akoos.photos.util.PhotoMetadata
import eu.akoos.photos.util.forEachSqlChunk
import eu.akoos.photos.util.originalUriForExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.util.Locale
import javax.inject.Inject

/** Which source the shown capture date is read from, so the screen can label it. The display basis
 *  matches the timeline (a device photo reads its DATE_TAKEN, a backed-up one its Drive capture time). */
enum class DateSource { DEVICE, CLOUD }

/** Why a field can't be edited, driving the inline note the screen shows in place of the control. Null
 *  means the field is editable. [FORMAT] is the container refusing the field (the descriptive text tags
 *  need a JPEG, PNG or WebP, which is narrower than the date); [DATE_FORMAT] is the container refusing a
 *  durable capture-date write, the date-specific twin of the place-specific [VIDEO]; [BULK] is a field
 *  that only makes sense on a single photo. */
enum class EditLock { CLOUD, SHARED, VIDEO, DATE_FORMAT, FORMAT, PLACE_FORMAT, BULK }

/** The descriptive EXIF text tags this editor writes. Each is a field of its own with its own apply
 *  action, so a write never carries a tag the user did not touch. */
enum class MetadataTextTag { DESCRIPTION, ARTIST, COPYRIGHT }

/**
 * One descriptive text field: what the box shows, what the file already holds, and why it is locked.
 * The typed value lives here rather than in the composable, so the keyboard closing or the screen
 * recomposing can't drop it.
 */
data class MetadataTextFieldState(
    val value: String = "",
    val stored: String = "",
    val lock: EditLock? = null,
    /** In bulk mode, whether an apply has landed yet. A single photo seeds this true, so it gates only
     *  the bulk "not set" prompt. */
    val chosen: Boolean = true,
) {
    /** True when applying [value] would change what the file holds. Both sides are compared in the ASCII
     *  form an EXIF text tag actually stores, so retyping the same text with different accents or
     *  spacing offers no apply. */
    val dirty: Boolean
        get() = ExifAsciiText.transliterate(value) != ExifAsciiText.transliterate(stored)
}

data class MetadataEditorUiState(
    val loaded: Boolean = false,
    /** Capture timestamp in epoch-ms on the same basis the timeline groups by. */
    val captureDateMs: Long = 0L,
    val dateSource: DateSource = DateSource.DEVICE,
    /** Null when the date is editable; otherwise why the picker is locked. */
    val dateLock: EditLock? = null,
    /** Resolved "City, Country" for the current coordinates, or null while it resolves / when there
     *  are none (the screen then shows its "No location" placeholder). */
    val placeLabel: String? = null,
    val hasLocation: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Null when the place is editable; otherwise why the control is locked. */
    val placeLock: EditLock? = null,
    val isSaving: Boolean = false,
    /** The consent request a foreign-file write raised; the screen launches it and, on approval,
     *  calls [onPermissionGranted] to retry the queued write. */
    val pendingWriteIntent: IntentSender? = null,
    /** True when more than one editable photo is bound: the date and place start unset and a chosen
     *  value applies to every editable item at once, rather than showing one photo's own values. */
    val bulk: Boolean = false,
    /** How many bound photos this editor can write (device-only), and how many are read-only here
     *  (backed up / cloud), so the screen can label a mixed selection. */
    val editableCount: Int = 0,
    val skippedCount: Int = 0,
    /** In bulk mode, whether the user has picked a date / place yet. A single photo seeds both true
     *  from its own values, so these gate only the bulk "not set" prompts. */
    val dateChosen: Boolean = true,
    val placeChosen: Boolean = true,
    /** True when two or more bound photos carry a date a shift can move, so offering to move them all
     *  by one delta says something the single absolute date cannot. */
    val dateShiftAvailable: Boolean = false,
    /** The oldest and newest date across those photos, both 0 when no shift is possible. What the
     *  user picks is a new value for [dateShiftEarliestMs]; everything else follows it by the same
     *  delta. */
    val dateShiftEarliestMs: Long = 0L,
    val dateShiftLatestMs: Long = 0L,
    /** The latest instant [dateShiftEarliestMs] may be moved to: past it the newest photo would land
     *  in the future. */
    val dateShiftMaxEarliestMs: Long = 0L,
    /** How many photos a shift passes over that the absolute date still reaches, because their own
     *  date is not real enough to add a delta to. Separate from [skippedCount], which counts what
     *  this editor cannot write at all. */
    val dateShiftSkippedCount: Int = 0,
    /** The descriptive text tags, each with its own value, stored value and lock. */
    val description: MetadataTextFieldState = MetadataTextFieldState(),
    val artist: MetadataTextFieldState = MetadataTextFieldState(),
    val copyright: MetadataTextFieldState = MetadataTextFieldState(),
) {
    /** The span a shift moves, ready for [DateShift]'s own rules, or null when no shift is possible. */
    val dateShiftSpan: DateShift.Span?
        get() = if (dateShiftAvailable) DateShift.Span(dateShiftEarliestMs, dateShiftLatestMs) else null

    fun textField(tag: MetadataTextTag): MetadataTextFieldState = when (tag) {
        MetadataTextTag.DESCRIPTION -> description
        MetadataTextTag.ARTIST -> artist
        MetadataTextTag.COPYRIGHT -> copyright
    }

    fun withTextField(
        tag: MetadataTextTag,
        transform: (MetadataTextFieldState) -> MetadataTextFieldState,
    ): MetadataEditorUiState = when (tag) {
        MetadataTextTag.DESCRIPTION -> copy(description = transform(description))
        MetadataTextTag.ARTIST -> copy(artist = transform(artist))
        MetadataTextTag.COPYRIGHT -> copy(copyright = transform(copyright))
    }
}

/** One-shot outcomes the screen reacts to (a brief confirmation, or a failure snackbar). */
sealed interface MetadataEditorEvent {
    data object Saved : MetadataEditorEvent

    /** Part of a batch took the edit and [failed] items did not, so the message carries both counts. */
    data class PartlySaved(val saved: Int, val failed: Int) : MetadataEditorEvent

    data object Failed : MetadataEditorEvent
}

/**
 * The outcome a batch of writes reports: every write landing is a plain confirmation, none landing is a
 * plain failure, and a mix reports both counts, so a batch where most items failed can never read as a
 * clean save. Null when nothing was attempted, which the caller turns into no message at all. Pure, so
 * the mapping is verifiable without a ViewModel.
 */
internal fun metadataWriteOutcome(succeeded: Int, failed: Int): MetadataEditorEvent? = when {
    succeeded > 0 && failed > 0 -> MetadataEditorEvent.PartlySaved(succeeded, failed)
    succeeded > 0 -> MetadataEditorEvent.Saved
    failed > 0 -> MetadataEditorEvent.Failed
    else -> null
}

/**
 * The stored-fix ids a place write landing on [savedUris] makes stale, picked out of the bound [items].
 *
 * `photo_location` keys a device photo's fix by its content URI and a cloud photo's by its linkId, so
 * which key a landed write invalidates is decided per subtype:
 *  - [GalleryItem.LocalOnly] keeps its fix under `local.uri`, which is exactly the file the write
 *    changed, so that id goes.
 *  - [GalleryItem.Synced] keeps the same device-file fix under `local.uri` and goes for the same
 *    reason. Its `cloud.linkId` row is a different fix, decrypted from the server XAttr that a device
 *    write does not touch, and the cloud walk flags a link as checked once, so dropping that row would
 *    lose coordinates nothing re-derives. It stays.
 *  - [GalleryItem.CloudOnly] holds only that server-derived fix and has no device file this editor can
 *    write, so it contributes no id.
 *
 * Pure, so the rule is verifiable without a ViewModel or a database.
 */
internal fun staleLocationIds(items: List<GalleryItem>, savedUris: Set<String>): List<String> =
    items.mapNotNull { item ->
        when (item) {
            is GalleryItem.LocalOnly -> item.local.uri.takeIf { it in savedUris }
            is GalleryItem.Synced -> item.local.uri.takeIf { it in savedUris }
            is GalleryItem.CloudOnly -> null
        }
    }.distinct()

/**
 * Backs the metadata editor for one photo or a whole multi-select: it exposes the capture date and
 * place on the same basis the timeline shows, plus the descriptive text tags read from the file, and
 * applies edits to LOCAL (device) photos through [WriteLocalPhotoMetadataUseCase]. Cloud-only photos
 * and photos in an album shared with the user are surfaced read-only, because the Drive backend
 * refuses an in-place metadata rewrite.
 *
 * A single bound photo shows its own current values and edits them in place. Two or more editable
 * photos switch to a "set for all" mode: the date and place start unset, and a chosen value is
 * written to every editable item at once. The date has a second bulk form, [shiftDatesTo], which
 * moves each photo by one shared delta from its OWN date, so a wrongly set camera clock is corrected
 * with the spacing between the shots intact (see [DateShift]).
 *
 * The date and place write the moment their picker closes; a text tag has no such moment, so each
 * text field carries its own apply, and [applyText] is the only thing that writes it. Every write is
 * reflected in the shown state, so the editor never has a separate commit step; foreign-file writes
 * the OS gates raise [MetadataWriteResult.NeedsPermission], which are gathered into one system
 * consent request and replayed once the screen relays the user's approval.
 */
@HiltViewModel
class MetadataEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writeMetadata: WriteLocalPhotoMetadataUseCase,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val hiddenStorage: HiddenStorageManager,
    private val hiddenVaultEditor: HiddenVaultEditor,
) : ViewModel() {

    private val _state = MutableStateFlow(MetadataEditorUiState())
    val state: StateFlow<MetadataEditorUiState> = _state.asStateFlow()

    private val _events = Channel<MetadataEditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Each editable device file whose capture-date edit durably saves: an EXIF-writable image or any
     *  video. An EXIF-unwritable image is held back, since only its MediaStore column would move and the
     *  next scan re-derives the old date from the file. */
    private var dateTargetUris: List<String> = emptyList()

    /** The files a date SHIFT moves, each with the date its delta is added to: [dateTargetUris]
     *  narrowed to the photos whose own date is real (see [DateShift.targets]). Kept in step with
     *  every landed shift, so a second shift measures from where the files now are. */
    private var dateShiftTargets: List<DateShift.Target> = emptyList()

    /** The same targets keyed by uri, so a write resolves its own base date with one lookup. */
    private var dateShiftBaseMs: Map<String, Long> = emptyMap()

    /** The editable image files a place write lands on. Videos are held back: a video container has
     *  no EXIF GPS block to write, so a place edit skips them silently in a mixed selection. */
    private var placeTargetUris: List<String> = emptyList()

    /** The editable files a descriptive text write lands on. Narrower than the place targets: the tags
     *  need a container ExifInterface can write, so HEIC is held back as well as video. */
    private var textTargetUris: List<String> = emptyList()

    /** The bound photos, kept so a landed place write can be mapped back to the stored fix each
     *  subtype records under its own key (see [staleLocationIds]). */
    private var boundItems: List<GalleryItem> = emptyList()

    /** The write awaiting a permission retry after [MetadataWriteResult.NeedsPermission], plus the
     *  exact URIs still to retry once the screen relays the user's consent. */
    private var pendingAction: PendingAction? = null
    private var pendingUris: List<String> = emptyList()

    private sealed interface PendingAction {
        data class Date(val ms: Long) : PendingAction

        /** Every target moved by [deltaMs] from its own date, so the spacing between them survives.
         *  The instant each file receives is resolved per uri at write time. */
        data class DateShift(val deltaMs: Long) : PendingAction

        data class Location(val lat: Double, val lon: Double, val label: String) : PendingAction
        data object ClearLocation : PendingAction
        data class Text(val tag: MetadataTextTag, val value: String) : PendingAction
    }

    /**
     * Binds the editor to [items]. [isReadOnlyAlbum] mirrors the viewer's guest gate: photos in an
     * album shared with the user are fully read-only. A lone editable item shows its own current date
     * and place (resolved off the main thread); two or more editable items start unset in "set for
     * all" mode. Backed-up and cloud-only items are counted as skipped, since Drive refuses an
     * in-place rewrite.
     */
    fun load(items: List<GalleryItem>, isReadOnlyAlbum: Boolean) {
        if (_state.value.loaded || items.isEmpty()) return

        boundItems = items
        // Only device-only photos have a file this screen may write; the rest are read-only here.
        val editable = items.filterIsInstance<GalleryItem.LocalOnly>()
        val editableCount = editable.size
        val skippedCount = items.size - editableCount
        // A lone editable item keeps the single-photo flow exactly; anything else with an editable
        // item is the "set for all" bulk mode.
        val singleMode = items.size == 1 && editableCount == 1
        val bulk = editableCount >= 1 && !singleMode

        // A date write lands only where it durably holds: an editable image whose EXIF takes the stamp
        // or any video (its mvhd or the column), so an EXIF-unwritable image is held back, since only its
        // column would move and the next scan reverts it. A vaulted photo is covered whatever it is: the
        // vault keeps its date in the file name and reads it from there, so the container it happens to
        // be in decides nothing. A place write needs a GPS EXIF block ExifInterface can save, which is
        // the same JPEG / PNG / WebP set the text tags need, so a video (no GPS block) and an unwritable
        // image (HEIC, GIF, RAW) both stay out of the location targets.
        dateTargetUris = editable
            .filter {
                hiddenStorage.isHiddenUri(it.local.uri) ||
                    WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(it.local.mimeType) ||
                    WriteLocalPhotoMetadataUseCase.isVideoMime(it.local.mimeType)
            }
            .map { it.local.uri }
        placeTargetUris = editable
            .filter { WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(it.local.mimeType) }
            .map { it.local.uri }
        textTargetUris = editable
            .filter { WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(it.local.mimeType) }
            .map { it.local.uri }
        val anyEditableImage = placeTargetUris.isNotEmpty()

        // A shift needs each photo's own date to add its delta to, so it covers the date targets whose
        // date is real. Two of them is the least that makes a shift mean anything: on one photo a shift
        // is the absolute date under another name.
        dateShiftTargets = DateShift.targets(items, dateTargetUris.toSet())
        dateShiftBaseMs = dateShiftTargets.associate { it.uri to it.captureMs }

        // A shared-with-me album outranks everything: both fields are read-only. With no editable item
        // every target is backed up / cloud, so both fields lock as CLOUD. Otherwise the date is editable
        // as long as one editable container durably takes it (an EXIF-writable image or any video); an
        // all-HEIC set has only the column, which the next scan reverts, so the date locks as DATE_FORMAT.
        // The place is editable as long as one EXIF-writable image is present. With none, a video set has
        // no GPS block and locks as VIDEO, while an unwritable-image set (HEIC, GIF, RAW) locks as
        // PLACE_FORMAT, since the message differs.
        val dateLock: EditLock?
        val placeLock: EditLock?
        when {
            isReadOnlyAlbum -> { dateLock = EditLock.SHARED; placeLock = EditLock.SHARED }
            editableCount == 0 -> { dateLock = EditLock.CLOUD; placeLock = EditLock.CLOUD }
            else -> {
                dateLock = if (dateTargetUris.isEmpty()) EditLock.DATE_FORMAT else null
                placeLock = when {
                    anyEditableImage -> null
                    editable.any { it.local.mimeType.startsWith("video/") } -> EditLock.VIDEO
                    else -> EditLock.PLACE_FORMAT
                }
            }
        }

        // The text tags share those first two reasons and add their own: with no target the container
        // is what refuses them, and a description is meaningful for one photo only, so it is offered
        // exactly where its stored value is also read back (the single-photo case below).
        val textLock: EditLock? = when {
            isReadOnlyAlbum -> EditLock.SHARED
            editableCount == 0 -> EditLock.CLOUD
            textTargetUris.isEmpty() -> EditLock.FORMAT
            else -> null
        }
        val descriptionLock = textLock ?: EditLock.BULK.takeIf { bulk }

        // Bulk mode starts unset so one photo's value is never presented as if all shared it. A single
        // item (editable or read-only) seeds its own date on the same basis the grid sorts by.
        val seed = items.first()
        val dateMs = if (bulk) System.currentTimeMillis() else seed.captureTimeMs
        val dateSource = if (bulk || seed is GalleryItem.LocalOnly) DateSource.DEVICE else DateSource.CLOUD

        _state.value = MetadataEditorUiState(
            loaded = true,
            captureDateMs = dateMs,
            dateSource = dateSource,
            dateLock = dateLock,
            placeLock = placeLock,
            bulk = bulk,
            editableCount = editableCount,
            skippedCount = skippedCount,
            dateChosen = !bulk,
            placeChosen = !bulk,
            dateShiftAvailable = dateLock == null && dateShiftTargets.size >= 2,
            dateShiftSkippedCount = dateTargetUris.size - dateShiftTargets.size,
            description = MetadataTextFieldState(lock = descriptionLock),
            artist = MetadataTextFieldState(lock = textLock, chosen = !bulk),
            copyright = MetadataTextFieldState(lock = textLock, chosen = !bulk),
        ).withShiftSpan(System.currentTimeMillis())

        // Only a single-item view resolves an existing place and existing text tags; bulk photos
        // differ, so nothing is shown until the user picks a value.
        if (items.size == 1) {
            viewModelScope.launch {
                val mime = mimeOf(seed)
                // One EXIF read backs both seeds for a device image file: the text tags below and the
                // coordinates the place field shows.
                val exif = readLocalExif(seed, mime)
                if (exif != null) seedTextFields(exif)
                val coords = currentCoords(seed, mime, exif)
                if (coords == null) {
                    _state.update { it.copy(hasLocation = false) }
                    return@launch
                }
                val label = OfflineGeocoder.reverseGeocode(context, coords.first, coords.second)
                _state.update {
                    it.copy(
                        hasLocation = true,
                        latitude = coords.first,
                        longitude = coords.second,
                        placeLabel = label,
                    )
                }
            }
        }
    }

    private fun mimeOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }

    /** The EXIF of [item]'s device file, or null when there is none to read (a cloud-only photo, or a
     *  video, whose container carries no EXIF block). Read off the main thread. */
    private suspend fun readLocalExif(item: GalleryItem, mime: String): PhotoMetadata? {
        val uri = when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced -> item.local.uri
            is GalleryItem.CloudOnly -> null
        }
        if (uri == null || mime.startsWith("video/")) return null
        return withContext(Dispatchers.IO) {
            ExifHelper.readMetadata(context, originalUriForExif(context, uri))
        }
    }

    /** Seeds the text fields from [exif], so the user edits the values the file holds instead of always
     *  starting empty. A read-only file seeds them too: the value is worth showing even where the lock
     *  keeps it from being changed. */
    private fun seedTextFields(exif: PhotoMetadata) {
        _state.update {
            it.copy(
                description = it.description.seeded(exif.description),
                artist = it.artist.seeded(exif.artist),
                copyright = it.copyright.seeded(exif.copyright),
            )
        }
    }

    private fun MetadataTextFieldState.seeded(fileValue: String?): MetadataTextFieldState =
        copy(value = fileValue.orEmpty(), stored = fileValue.orEmpty())

    /** Sets the capture date/time to [ms] (device wall-clock) on every editable item. No-op on a
     *  read-only target. */
    fun setDate(ms: Long) {
        if (_state.value.dateLock != null) return
        performBatch(PendingAction.Date(ms), dateTargetUris)
    }

    /**
     * Moves every shiftable photo by the delta that puts the OLDEST of them on [earliestMs], so a
     * wrongly set camera clock is corrected without the selection collapsing onto one instant.
     * Photos whose own date is not real keep it, and the absolute [setDate] still reaches them.
     *
     * No-op where the date is locked, where there is nothing to shift, where the delta would put the
     * newest photo in the future, and where the picked instant is the one the oldest photo already
     * carries.
     */
    fun shiftDatesTo(earliestMs: Long) {
        if (_state.value.dateLock != null) return
        val span = _state.value.dateShiftSpan ?: return
        if (!DateShift.allowsEarliest(span, earliestMs, System.currentTimeMillis())) return
        val deltaMs = DateShift.deltaFor(span, earliestMs)
        if (deltaMs == 0L) return
        performBatch(PendingAction.DateShift(deltaMs), dateShiftTargets.map { it.uri })
    }

    /** Places every editable image at [code]'s country centroid, labelling it with the country name. */
    fun pickCountry(code: String) {
        if (_state.value.placeLock != null) return
        viewModelScope.launch {
            val point = OfflineGeocoder.countryPoint(context, code) ?: run {
                _events.send(MetadataEditorEvent.Failed)
                return@launch
            }
            val label = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
            performBatch(PendingAction.Location(point.first, point.second, label), placeTargetUris)
        }
    }

    /** Places every editable image at [place]'s coordinates, labelling it "City, Country". */
    fun pickCity(place: OfflineGeocoder.GeoPlace) {
        if (_state.value.placeLock != null) return
        val country = Locale("", place.countryCode)
            .getDisplayCountry(Locale.getDefault())
            .ifBlank { place.countryCode }
        performBatch(
            PendingAction.Location(place.latitude, place.longitude, "${place.name}, $country"),
            placeTargetUris,
        )
    }

    /** Strips the GPS location from every editable image. */
    fun removeLocation() {
        if (_state.value.placeLock != null) return
        performBatch(PendingAction.ClearLocation, placeTargetUris)
    }

    /** Records typing in [tag]'s box. The value is capped at the length one EXIF text tag holds, so what
     *  the box shows is always what an apply can store. */
    fun setText(tag: MetadataTextTag, value: String) {
        if (_state.value.textField(tag).lock != null) return
        _state.update { state ->
            state.withTextField(tag) { it.copy(value = value.take(ExifAsciiText.MAX_LENGTH)) }
        }
    }

    /** Writes [tag]'s typed value to every editable image. This is the field's whole commit: unlike the
     *  date and the place, typing has no moment of its own to write on. A locked or unchanged field
     *  writes nothing, so the apply action can never fire a pointless pass over the files. */
    fun applyText(tag: MetadataTextTag) {
        val field = _state.value.textField(tag)
        if (field.lock != null || !field.dirty) return
        performBatch(PendingAction.Text(tag, field.value), textTargetUris)
    }

    /** Replays the writes the OS gated, once the screen relays the user's consent. */
    fun onPermissionGranted() {
        _state.update { it.copy(pendingWriteIntent = null) }
        val action = pendingAction ?: return
        performBatch(action, pendingUris)
    }

    /** Drops a queued write the user declined at the consent dialog. */
    fun clearPendingWriteIntent() {
        pendingAction = null
        pendingUris = emptyList()
        _state.update { it.copy(pendingWriteIntent = null) }
    }

    /**
     * Writes [action] to each of [uris] (a single item is a one-element batch). Foreign files the OS
     * refuses are gathered into one system consent request instead of one dialog per file; on approval
     * the screen calls [onPermissionGranted], which replays exactly the deferred URIs.
     */
    private fun performBatch(action: PendingAction, uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val needsPermission = mutableListOf<Pair<String, IntentSender>>()
            // The URIs that took the write, not just a flag: the count drives the reported outcome and
            // the list is what the capture-date override map is moved onto.
            val succeeded = mutableListOf<String>()
            var failedCount = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    when (val result = writeOne(action, uri)) {
                        is MetadataWriteResult.Success -> succeeded += uri
                        is MetadataWriteResult.NeedsPermission -> needsPermission += uri to result.intentSender
                        is MetadataWriteResult.Failed -> failedCount++
                    }
                }
            }
            _state.update { it.copy(isSaving = false) }

            if (needsPermission.isNotEmpty()) {
                val sender = consentSenderFor(needsPermission)
                if (sender != null) {
                    pendingAction = action
                    pendingUris = needsPermission.map { it.first }
                    // Reflect whatever already landed; the retry covers the rest.
                    if (succeeded.isNotEmpty()) applySuccess(action, succeeded)
                    _state.update { it.copy(pendingWriteIntent = sender) }
                    return@launch
                }
                // No consent request could be built, so those files stay unwritten and count as failed.
                failedCount += needsPermission.size
            }

            pendingAction = null
            pendingUris = emptyList()
            if (succeeded.isNotEmpty()) applySuccess(action, succeeded)
            metadataWriteOutcome(succeeded.size, failedCount)?.let { _events.send(it) }
        }
    }

    /**
     * Carries a landed date write into the one place a vaulted photo keeps its date: its own file name.
     *
     * A vaulted photo has no MediaStore row, so the vault records the capture time in the file name and
     * reads it from there everywhere — the grid it is shown in, and the reveal that writes it back to
     * the device. Both prefer that value over the file's own EXIF, so a date edit that stopped at the
     * EXIF would report a save the user never sees. Restamping the name moves the file, and with it the
     * uri every record and every target list here is keyed by, so the move is applied to those too.
     *
     * Runs after [applySuccess], which reads the uris the write landed on.
     */
    private suspend fun restampVaultedDates(savedUris: List<String>, dateOf: (String) -> Long?) {
        val moved = mutableMapOf<String, String>()
        for (uri in savedUris) {
            if (!hiddenStorage.isHiddenUri(uri)) continue
            val ms = dateOf(uri)?.takeIf { it > 0L } ?: continue
            val newUri = hiddenVaultEditor.restampCaptureTime(uri, ms) ?: continue
            if (newUri != uri) moved[uri] = newUri
        }
        if (moved.isEmpty()) return
        fun List<String>.follow() = map { moved[it] ?: it }
        dateTargetUris = dateTargetUris.follow()
        placeTargetUris = placeTargetUris.follow()
        textTargetUris = textTargetUris.follow()
        pendingUris = pendingUris.follow()
        dateShiftTargets = dateShiftTargets.map { target ->
            moved[target.uri]?.let { target.copy(uri = it) } ?: target
        }
        dateShiftBaseMs = dateShiftTargets.associate { it.uri to it.captureMs }
        boundItems = boundItems.map { item ->
            when (item) {
                is GalleryItem.LocalOnly ->
                    moved[item.local.uri]?.let { GalleryItem.LocalOnly(item.local.copy(uri = it)) } ?: item
                is GalleryItem.Synced, is GalleryItem.CloudOnly -> item
            }
        }
    }

    private suspend fun writeOne(action: PendingAction, uri: String): MetadataWriteResult =
        when (action) {
            is PendingAction.Date -> writeMetadata.writeCaptureDate(uri, action.ms)
            // The delta is shared, the instant is not: each file is written its own date plus the
            // shift, through the same date write, so the spacing between them is what lands.
            is PendingAction.DateShift -> {
                val baseMs = dateShiftBaseMs[uri]
                if (baseMs == null) MetadataWriteResult.Failed("no date to shift")
                else writeMetadata.writeCaptureDate(uri, baseMs + action.deltaMs)
            }
            is PendingAction.Location -> writeMetadata.writeLocation(uri, action.lat, action.lon)
            PendingAction.ClearLocation -> writeMetadata.clearLocation(uri)
            // Only the edited tag is addressed; the other two stay exactly as the file holds them.
            is PendingAction.Text -> {
                val edit = TextTagEdit.SetTo(action.value)
                when (action.tag) {
                    MetadataTextTag.DESCRIPTION -> writeMetadata.writeDescriptiveText(uri, description = edit)
                    MetadataTextTag.ARTIST -> writeMetadata.writeDescriptiveText(uri, artist = edit)
                    MetadataTextTag.COPYRIGHT -> writeMetadata.writeDescriptiveText(uri, copyright = edit)
                }
            }
        }

    /** One consent request covering every foreign URI. Android 11+ builds a single batch write
     *  request; below that there is no batch API, so the first item's own recoverable-security consent
     *  runs and the retry re-raises for any that remain. */
    private fun consentSenderFor(needsPermission: List<Pair<String, IntentSender>>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(
                    context.contentResolver, needsPermission.map { Uri.parse(it.first) },
                ).intentSender
            }.getOrNull()
        } else {
            needsPermission.first().second
        }

    /**
     * Reflects the writes that landed on [savedUris], along with the record each field keeps outside the
     * file itself, on this same path that reports the save, so no other screen can disagree with what the
     * editor says landed. A date write moves the download capture-date override of those files onto the
     * new value; a place write (set or cleared) drops their stored GPS fix.
     */
    private suspend fun applySuccess(action: PendingAction, savedUris: List<String>) {
        when (action) {
            is PendingAction.Date -> retargetDateOverrides(savedUris.associateWith { action.ms })
            is PendingAction.DateShift -> applyLandedShift(action.deltaMs, savedUris)
            is PendingAction.Location, PendingAction.ClearLocation -> invalidateStoredLocations(savedUris)
            is PendingAction.Text -> Unit
        }
        // A vaulted photo's date lives in its file name, not in a column, so a landed date write is only
        // half done until the name carries it. The shift resolves each file's own new date from the
        // record [applyLandedShift] has just moved.
        when (action) {
            is PendingAction.Date -> restampVaultedDates(savedUris) { action.ms }
            is PendingAction.DateShift -> restampVaultedDates(savedUris) { dateShiftBaseMs[it] }
            is PendingAction.Location, PendingAction.ClearLocation, is PendingAction.Text -> Unit
        }
        _state.update {
            when (action) {
                is PendingAction.Date -> it.copy(captureDateMs = action.ms, dateChosen = true)
                // The shown date follows the oldest photo, which is the one the picked instant named;
                // in a partly landed batch that is wherever the selection now starts.
                is PendingAction.DateShift -> {
                    val moved = it.withShiftSpan(System.currentTimeMillis())
                    moved.copy(
                        captureDateMs = moved.dateShiftEarliestMs.takeIf { ms -> ms > 0L }
                            ?: moved.captureDateMs,
                        dateChosen = true,
                    )
                }
                is PendingAction.Location -> it.copy(
                    hasLocation = true,
                    latitude = action.lat,
                    longitude = action.lon,
                    placeLabel = action.label,
                    placeChosen = true,
                )
                PendingAction.ClearLocation -> it.copy(
                    hasLocation = false,
                    latitude = null,
                    longitude = null,
                    placeLabel = null,
                    placeChosen = true,
                )
                // The box takes the ASCII form the file received, so what it shows after a save is
                // exactly what is stored, and the same text offers no second apply.
                is PendingAction.Text -> {
                    val storedForm = ExifAsciiText.transliterate(action.value)
                    it.withTextField(action.tag) { field ->
                        field.copy(value = storedForm, stored = storedForm, chosen = true)
                    }
                }
            }
        }
    }

    /**
     * Points the recorded capture-date override of each uri in [captureMsByUri] at the date that file
     * received. A download whose DATE_TAKEN column MediaStore refused (PNG/WebP) keeps its date only in
     * that map, and the local scan prefers the recorded value whenever the column reads 0, so without
     * this move the grid would keep reporting the date the download recorded while this screen said the
     * edit landed. The dates are per file because a shift gives each one its own; only files that
     * already have an entry move (see [CaptureDateOverride.retarget]); a store failure leaves the landed
     * write intact.
     */
    private suspend fun retargetDateOverrides(captureMsByUri: Map<String, Long>) {
        if (captureMsByUri.isEmpty()) return
        runCatching {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] ?: return@edit
                CaptureDateOverride.retarget(current, captureMsByUri)?.let {
                    prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] = it
                }
            }
        }
    }

    /**
     * Moves the shift's own record of the files [savedUris] landed on by [deltaMs], and points their
     * recorded overrides at the same dates. The record has to move with the files: a second shift adds
     * its delta to what is held here, so a base left behind would move a file twice. Files the batch
     * did not reach keep theirs, which is where a deferred consent retry then measures from.
     */
    private suspend fun applyLandedShift(deltaMs: Long, savedUris: List<String>) {
        val saved = savedUris.toSet()
        dateShiftTargets = DateShift.shifted(dateShiftTargets, deltaMs, saved)
        dateShiftBaseMs = dateShiftTargets.associate { it.uri to it.captureMs }
        retargetDateOverrides(dateShiftBaseMs.filterKeys { it in saved })
    }

    /** [this] carrying the span [dateShiftTargets] now cover, and the ceiling a pick may not cross
     *  measured from [nowMs]. Every value is 0 when there is nothing to shift. */
    private fun MetadataEditorUiState.withShiftSpan(nowMs: Long): MetadataEditorUiState {
        val span = if (dateShiftAvailable) DateShift.span(dateShiftTargets) else null
        return copy(
            dateShiftEarliestMs = span?.earliestMs ?: 0L,
            dateShiftLatestMs = span?.latestMs ?: 0L,
            dateShiftMaxEarliestMs = span?.let { DateShift.maxEarliestMs(it, nowMs) } ?: 0L,
        )
    }

    /**
     * Drops the stored GPS fix of the files [uris] landed on. The map, the search groupings and the
     * location screen all plot `photo_location` rows, and the backfill skips a file that already has
     * one, so a row left standing keeps every one of them on the coordinates the file no longer holds.
     * Removing it both clears the stale point from the live map query and puts the file back in the
     * backfill's queue, where it re-reads the place the write just stored. Only the ids the landed
     * write actually made stale are dropped ([staleLocationIds]); a delete failure leaves the written
     * file intact and costs a stale point until the row is dropped again.
     */
    private suspend fun invalidateStoredLocations(uris: List<String>) {
        val ids = staleLocationIds(boundItems, uris.toSet())
        if (ids.isEmpty()) return
        val userId = accountManager.getPrimaryUserId().first()?.id ?: return
        runCatching { ids.forEachSqlChunk { photoLocationDao.deleteByIds(userId, it) } }
    }

    /** Current coordinates on the same resolution order the viewer's details use: a device image reads
     *  its EXIF, a device video its container, and a backed-up / cloud photo its stored fix. [exif] is
     *  the read the caller already made for the same file, so the image path parses it once. */
    private suspend fun currentCoords(
        item: GalleryItem,
        mime: String,
        exif: PhotoMetadata?,
    ): Pair<Double, Double>? {
        val userId = accountManager.getPrimaryUserId().first()?.id
        return when (item) {
            is GalleryItem.LocalOnly ->
                withContext(Dispatchers.IO) { PhotoGpsResolver.localGps(context, item.local.uri, mime, exif) }
            is GalleryItem.Synced ->
                withContext(Dispatchers.IO) { PhotoGpsResolver.localGps(context, item.local.uri, mime, exif) }
                    ?: userId?.let { PhotoGpsResolver.cloudGps(photoLocationDao, it, item.cloud.linkId) }
            is GalleryItem.CloudOnly ->
                userId?.let { PhotoGpsResolver.cloudGps(photoLocationDao, it, item.cloud.linkId) }
        }
    }
}
