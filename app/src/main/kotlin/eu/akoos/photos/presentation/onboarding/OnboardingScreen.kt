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

package eu.akoos.photos.presentation.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.LanguagePrefsBoot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.presentation.lock.AppLockManager
import eu.akoos.photos.presentation.lock.AppLockSettings
import eu.akoos.photos.presentation.lock.AppLockWizardWrite
import eu.akoos.photos.presentation.lock.WIZARD_LOCK_TIMEOUT_SECONDS
import eu.akoos.photos.presentation.lock.appLockWizardStart
import eu.akoos.photos.presentation.lock.appLockWizardWrite
import eu.akoos.photos.presentation.lock.showAppLockPrompt
import eu.akoos.photos.presentation.onboarding.components.ProgressDots
import eu.akoos.photos.presentation.onboarding.steps.AboutStep
import eu.akoos.photos.presentation.onboarding.steps.AlbumMirrorStep
import eu.akoos.photos.presentation.onboarding.steps.AppLockStep
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.onboarding.steps.AppearanceStep
import eu.akoos.photos.presentation.onboarding.steps.BackupModeStep
import eu.akoos.photos.presentation.onboarding.steps.DoneStep
import eu.akoos.photos.presentation.onboarding.steps.FaqStep
import eu.akoos.photos.presentation.onboarding.steps.FolderPickerStep
import eu.akoos.photos.presentation.onboarding.steps.AllFilesAccessStep
import eu.akoos.photos.presentation.onboarding.steps.ManageMediaStep
import eu.akoos.photos.presentation.onboarding.steps.MlIntroStep
import eu.akoos.photos.presentation.onboarding.steps.MirrorOptInStep
import eu.akoos.photos.presentation.onboarding.steps.NotificationsStep
import eu.akoos.photos.presentation.onboarding.steps.PhotosAccessStep
import eu.akoos.photos.presentation.onboarding.steps.PrivacyStep
import eu.akoos.photos.presentation.onboarding.steps.WelcomeStep
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.service.ModelDownloadService
import eu.akoos.photos.util.MediaPermissions
import eu.akoos.photos.util.ModelDownloadKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Folder discovery + wizard state
// ─────────────────────────────────────────────────────────────────────────────

data class OnboardingFolder(val name: String, val coverUri: String?, val itemCount: Int)

enum class BackupMode { Everything, ChooseLater, NothingForNow }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
    private val appLockManager: AppLockManager,
) : ViewModel() {

    /** Where the app lock step starts: the stored settings ([appLockWizardStart]). */
    suspend fun appLockStart(): AppLockSettings = appLockWizardStart(appLockManager.settings.first())

    /**
     * Forces the device-folder list to re-query. The [folders] flow seeds an empty
     * list while the media permission is unset (MediaStore returns nothing), and a
     * permission grant does not fire a content-change notification for media that was
     * already present — so without an explicit poke the folder picker would stay empty
     * after the user grants access until the app restarts or a new photo is captured.
     * Called from the permission-result callback.
     */
    fun onMediaPermissionChanged() {
        localMediaRepo.notifyMediaChanged()
    }

    /**
     * Live appearance selections backed by DataStore — single source of truth so
     * the picker UI always reflects the most recently written value and survives
     * recomposition without a local shadow `rememberSaveable`. Initial values
     * mirror the persisted defaults; `stringResource` calls re-resolve via
     * `LocaleOverride` when the language flow re-emits.
     */
    val themeMode: StateFlow<ThemeMode> = context.settingsDataStore.data
        .map { prefs ->
            val key = prefs[SettingsKeys.THEME_MODE]
                ?: when (prefs[SettingsKeys.DARK_MODE]) {
                    true  -> "dark"
                    false -> "light"
                    null  -> "system"
                }
            ThemeMode.fromKey(key)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThemeMode.System)

    val palette: StateFlow<ThemePalette> = context.settingsDataStore.data
        .map { ThemePalette.fromKey(it[SettingsKeys.THEME_PALETTE]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThemePalette.Default)

    val language: StateFlow<String> = context.settingsDataStore.data
        .map { it[SettingsKeys.LANGUAGE] ?: "system" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "system")

    /**
     * Bucket list for the folder picker step. Empty list while the photos read
     * permission hasn't been granted (MediaStore returns nothing) — the step UI
     * shows a "grant permission first" placeholder in that case. Once granted,
     * a single Flow emission populates the list and the step re-renders.
     */
    val folders: StateFlow<List<OnboardingFolder>> = localMediaRepo.observeLocalMedia()
        .map { items ->
            items.asSequence()
                .filter { it.bucketName != null }
                .groupBy { it.bucketName!! }
                .map { (name, group) ->
                    val sorted = group.sortedByDescending { it.dateTaken }
                    OnboardingFolder(
                        name = name,
                        coverUri = sorted.firstOrNull()?.uri,
                        itemCount = sorted.size,
                    )
                }
                .sortedByDescending { it.itemCount }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Canonical DataStore write + boot-mirror update. We DELIBERATELY do not call
     * AppCompatDelegate.setDefaultNightMode here: it forces an Activity recreate
     * which the user perceives as a screen-scale-from-center animation. The Compose
     * tree re-themes via the DataStore flow [MainActivity] collects, so the in-app
     * surface flips immediately without any recreate. ProtonCore login Activities
     * pick up the right theme at the next cold start through [App.applyStoredThemeMode]
     * — they never appear during the onboarding flow itself.
     */
    fun setThemeLive(mode: ThemeMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit {
                it[SettingsKeys.THEME_MODE] = mode.storageKey
                it.remove(SettingsKeys.DARK_MODE)
            }
            ThemePrefsBoot.write(context, mode.storageKey)
        }
    }

    /** Palette is purely an in-Compose accent swap — no boot-mirror, no AppCompat. */
    fun setPaletteLive(palette: ThemePalette) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.THEME_PALETTE] = palette.storageKey }
        }
    }

    /**
     * Language switch — canonical DataStore write plus boot-mirror update so the
     * next cold start hands the right locale to ProtonCore login Activities. Does
     * NOT call `AppCompatDelegate.setApplicationLocales`: that triggers an Activity
     * recreate which loses navigation state and plays a reload animation. The
     * in-app Compose tree re-resolves strings via [eu.akoos.photos.presentation.util.LocaleOverride]
     * wired up in `MainActivity` — DataStore change → flow re-emits → override key
     * changes → recomposition picks up the new locale.
     */
    fun setLanguageLive(tag: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = tag }
            LanguagePrefsBoot.write(context, tag)
        }
    }

    /**
     * Commits every onboarding choice in a single DataStore transaction. Theme,
     * palette, and language are already persisted (live above) but we re-write them
     * here so a partial mid-wizard write paired with a process death still ends up
     * with the user's final selections after they re-walk the wizard.
     *
     * The app lock choice goes through the rule Settings applies, against the stored
     * settings ([appLockWizardWrite]): one that weakens the lock waits for
     * [reauthenticate] to report a successful authentication, and without one nothing is
     * written and the wizard stays open, so Finish can ask again.
     */
    fun finishOnboarding(
        backupMode: BackupMode,
        excludedFolders: Set<String>,
        selectedFolders: Set<String>,
        stripMetadata: Boolean,
        stripGps: Boolean,
        stripCamera: Boolean,
        stripTimestamp: Boolean,
        stripSoftware: Boolean,
        renameOnUpload: Boolean,
        mirrorToLocal: Boolean,
        deleteAfterBackup: Boolean,
        mlFace: Boolean,
        mlSemantic: Boolean,
        mlOcr: Boolean,
        themeMode: ThemeMode,
        palette: ThemePalette,
        language: String,
        appLockEnabled: Boolean,
        appLockTimeoutSeconds: Int,
        albumMirrorSelection: Set<String>,
        albumMirrorCustom: Set<String>,
        albumMirrorOptOut: Set<String>,
        reauthenticate: (onAuthenticated: () -> Unit) -> Unit,
        onAppliedLocale: () -> Unit,
    ) {
        val lockSeconds = appLockTimeoutSeconds.coerceAtLeast(0)

        suspend fun commit(writeLock: Boolean) {
            context.settingsDataStore.edit { p ->
                when (backupMode) {
                    BackupMode.Everything -> {
                        p[SettingsKeys.BACKUP_EVERYTHING] = true
                        p[SettingsKeys.ALBUM_ALL_BY_DEFAULT] = true
                        p[SettingsKeys.EXCLUDED_FOLDER_NAMES] = excludedFolders
                    }
                    BackupMode.ChooseLater -> {
                        p[SettingsKeys.BACKUP_EVERYTHING] = false
                        if (selectedFolders.isNotEmpty()) {
                            p[SettingsKeys.SYNC_FOLDER_NAMES] = selectedFolders
                        } else {
                            // Empty pick collapses to NothingForNow semantically.
                            p[SettingsKeys.SYNC_FOLDER_NAMES] = emptySet()
                        }
                    }
                    BackupMode.NothingForNow -> {
                        p[SettingsKeys.BACKUP_EVERYTHING] = false
                        p[SettingsKeys.SYNC_FOLDER_NAMES] = emptySet()
                    }
                }
                p[SettingsKeys.STRIP_ON_UPLOAD] = stripMetadata
                p[SettingsKeys.STRIP_GPS] = stripGps
                p[SettingsKeys.STRIP_CAMERA_INFO] = stripCamera
                p[SettingsKeys.STRIP_TIMESTAMP] = stripTimestamp
                p[SettingsKeys.STRIP_SOFTWARE_INFO] = stripSoftware
                p[SettingsKeys.RENAME_TO_CAPTURE_DATE] = renameOnUpload
                p[SettingsKeys.MIRROR_STRIP_TO_LOCAL] = mirrorToLocal
                p[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] = deleteAfterBackup
                if (mlFace || mlSemantic || mlOcr) p[SettingsKeys.AI_FEATURES_ENABLED] = true
                p[SettingsKeys.THEME_MODE] = themeMode.storageKey
                p[SettingsKeys.THEME_PALETTE] = palette.storageKey
                p[SettingsKeys.LANGUAGE] = language
                if (writeLock) {
                    p[SettingsKeys.APP_LOCK_ENABLED] = appLockEnabled
                    p[SettingsKeys.APP_LOCK_TIMEOUT_SECONDS] = lockSeconds
                }
                // Album-mirror opt-in: persist the chosen folder names (the
                // custom set is already a subset of the selection because every
                // add-custom flow auto-checks the new row) and flip the
                // migration flag so the first-run migration doesn't run
                // afterwards and clobber the user's explicit pick. The
                // albumMirrorCustom parameter at the call site is intentionally
                // accepted but unused here: its values are already merged into
                // albumMirrorSelection by the step composable.
                p[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = albumMirrorSelection
                p[SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES] = albumMirrorOptOut
                p[SettingsKeys.ALBUM_OPT_IN_MIGRATED] = true
                p[SettingsKeys.ONBOARDING_COMPLETE] = true
            }
            // Final defensive sync of the boot mirrors. Both were already written by
            // the live setters above, but a user who skipped the Appearance step (back
            // button, system back, etc.) and let it submit defaults would otherwise
            // leave the boot store stale.
            ThemePrefsBoot.write(context, themeMode.storageKey)
            LanguagePrefsBoot.write(context, language)
            // Each chosen feature fetches its models on the download service, which records the
            // consent, queues the kinds one behind another and turns the feature on once the files verify.
            if (mlFace) ModelDownloadService.start(context, ModelDownloadKind.FACE)
            if (mlSemantic) ModelDownloadService.start(context, ModelDownloadKind.SEMANTIC)
            if (mlOcr) ModelDownloadService.start(context, ModelDownloadKind.OCR)
            onAppliedLocale()
        }

        viewModelScope.launch {
            val lockWrite = appLockWizardWrite(
                current = appLockManager.settings.first(),
                chosen = AppLockSettings(enabled = appLockEnabled, timeoutMs = lockSeconds * 1000L),
            )
            when (lockWrite) {
                AppLockWizardWrite.Keep -> commit(writeLock = false)
                AppLockWizardWrite.Write -> commit(writeLock = true)
                AppLockWizardWrite.WriteAfterAuth -> reauthenticate {
                    viewModelScope.launch { commit(writeLock = true) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── User choices ────────────────────────────────────────────────────────
    var backupMode by rememberSaveable { mutableStateOf(BackupMode.NothingForNow) }
    var selectedFolders by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var excludedFolders by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var albumMirrorSelection by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var albumMirrorCustom by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    // Set once the user changes the album picks themselves; until then the picks follow the backup mode.
    var albumMirrorTouched by rememberSaveable { mutableStateOf(false) }
    var stripMetadata by rememberSaveable { mutableStateOf(false) }
    var stripGps by rememberSaveable { mutableStateOf(false) }
    var stripCamera by rememberSaveable { mutableStateOf(false) }
    var stripTimestamp by rememberSaveable { mutableStateOf(false) }
    var stripSoftware by rememberSaveable { mutableStateOf(false) }
    var renameOnUpload by rememberSaveable { mutableStateOf(false) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(false) }
    var mlFace by rememberSaveable { mutableStateOf(false) }
    var mlSemantic by rememberSaveable { mutableStateOf(false) }
    var mlOcr by rememberSaveable { mutableStateOf(false) }
    // Theme / palette / language are read from the VM (DataStore-backed) instead of
    // shadow `rememberSaveable` state — the live setters below already persist to
    // DataStore, so a single source of truth keeps the picker UI consistent with the
    // active theme + locale of the rest of the app (and survives process death).
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var appLockEnabled by rememberSaveable { mutableStateOf(false) }
    var appLockTimeoutSeconds by rememberSaveable { mutableIntStateOf(WIZARD_LOCK_TIMEOUT_SECONDS) }
    // The lock step starts from the stored settings, so walking through the wizard (a guest signing in
    // reaches it too) keeps an enabled lock on. A choice made on the step before the read lands wins.
    var appLockPrefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (appLockPrefilled) return@LaunchedEffect
        val start = runCatching { viewModel.appLockStart() }.getOrNull() ?: return@LaunchedEffect
        if (appLockPrefilled) return@LaunchedEffect
        appLockEnabled = start.enabled
        appLockTimeoutSeconds = (start.timeoutMs / 1000L).toInt()
        appLockPrefilled = true
    }

    var notificationGranted by rememberSaveable { mutableStateOf(false) }
    var mediaGranted by rememberSaveable {
        mutableStateOf(hasMediaPermission(context))
    }
    var manageMediaGranted by rememberSaveable {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)
        )
    }
    var mirrorToLocal by rememberSaveable { mutableStateOf(false) }
    var allFilesGranted by rememberSaveable {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                android.os.Environment.isExternalStorageManager()
        )
    }

    val mediaPermissions = remember { MediaPermissions.requestSet(Build.VERSION.SDK_INT) }

    val showManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val showNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Step list — order is About → Welcome → FAQ → Appearance → Photos perm →
    // Backup mode → conditional Folder picker → Privacy → Machine learning → App lock → Notifications →
    // Manage media → Done. Photos perm comes before the folder picker so when
    // we hit that step MediaStore can actually enumerate buckets; otherwise the
    // list would render empty.
    val steps = buildList {
        add(OnboardingStep.About)
        add(OnboardingStep.Welcome)
        add(OnboardingStep.Faq)
        add(OnboardingStep.Appearance)
        add(OnboardingStep.PhotosAccess)
        // All-files access right after the media grant — the app asks for it by default (not only
        // behind the mirror opt-in) so restored hidden photos can return to their real folders.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(OnboardingStep.AllFilesAccess)
        add(OnboardingStep.BackupMode)
        if (backupMode != BackupMode.NothingForNow) {
            add(OnboardingStep.FolderPicker)
            add(OnboardingStep.AlbumMirrorOptIn)
        }
        add(OnboardingStep.Privacy)
        // Mirror needs all-files access (API 30+) and only matters when something backs up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && backupMode != BackupMode.NothingForNow) {
            add(OnboardingStep.MirrorOptIn)
        }
        add(OnboardingStep.MachineLearning)
        add(OnboardingStep.AppLock)
        if (showNotifications) add(OnboardingStep.Notifications)
        if (showManageMedia) add(OnboardingStep.ManageMedia)
        add(OnboardingStep.Done)
    }
    val totalSteps = steps.size

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalSteps })
    val currentPage = pagerState.currentPage.coerceIn(0, steps.lastIndex)
    val currentStep = steps[currentPage]

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val readResult = MediaPermissions.readResults(result, Build.VERSION.SDK_INT)
        mediaGranted = readResult.values.all { it }
        // Re-query device folders now that access is granted: an existing-media
        // permission grant fires no content-change notification, so the folder
        // picker's flow needs an explicit poke or it stays on its pre-grant empty
        // snapshot until an app restart.
        if (readResult.values.any { it }) viewModel.onMediaPermissionChanged()
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }
    val manageMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        manageMediaGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            MediaStore.canManageMedia(context)
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }
    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp),
        ) {
            ProgressDots(currentIndex = currentPage, total = totalSteps)
            Spacer(Modifier.height(20.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { it },
            ) { pageIndex ->
                // Step lookup uses the PAGER's pageIndex (not the outer
                // currentStep) so each page composes its OWN step content —
                // otherwise pages on either side of `currentPage` would all
                // render the same content for one frame (the original
                // "double-skip" symptom).
                val step = steps.getOrNull(pageIndex) ?: OnboardingStep.Done
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                ) {
                    when (step) {
                        OnboardingStep.About -> AboutStep()
                        OnboardingStep.Welcome -> WelcomeStep()
                        OnboardingStep.Faq -> FaqStep()
                        OnboardingStep.Appearance -> AppearanceStep(
                            themeMode = themeMode,
                            palette = palette,
                            language = language,
                            onThemeMode = { viewModel.setThemeLive(it) },
                            onPalette = { viewModel.setPaletteLive(it) },
                            onLanguage = { viewModel.setLanguageLive(it) },
                        )
                        OnboardingStep.PhotosAccess -> PhotosAccessStep(
                            granted = mediaGranted,
                            onAllow = { mediaLauncher.launch(mediaPermissions) },
                        )
                        OnboardingStep.BackupMode -> BackupModeStep(
                            selected = backupMode,
                            onSelect = { backupMode = it },
                        )
                        OnboardingStep.FolderPicker -> FolderPickerStep(
                            viewModel = viewModel,
                            mode = backupMode,
                            mediaGranted = mediaGranted,
                            selectedFolders = selectedFolders,
                            excludedFolders = excludedFolders,
                            onSelectedChange = { selectedFolders = it },
                            onExcludedChange = { excludedFolders = it },
                        )
                        OnboardingStep.AlbumMirrorOptIn -> {
                            val deviceFolders by viewModel.folders.collectAsStateWithLifecycle()
                            // Backing up everything means every folder, so its albums are ticked to begin with (the folders
                            // left out of the backup excepted); a user's own change is kept from then on.
                            LaunchedEffect(backupMode, deviceFolders, excludedFolders) {
                                if (!albumMirrorTouched) {
                                    albumMirrorSelection = if (backupMode == BackupMode.Everything) {
                                        deviceFolders.map { it.name }.filter { it !in excludedFolders }.toSet() + albumMirrorCustom
                                    } else {
                                        albumMirrorCustom
                                    }
                                }
                            }
                            AlbumMirrorStep(
                                availableBuckets = deviceFolders.map { it.name },
                                selected = albumMirrorSelection,
                                customNames = albumMirrorCustom,
                                onToggle = { name ->
                                    albumMirrorTouched = true
                                    albumMirrorSelection = if (name in albumMirrorSelection) {
                                        albumMirrorSelection - name
                                    } else {
                                        albumMirrorSelection + name
                                    }
                                },
                                onAddCustom = { name ->
                                    albumMirrorTouched = true
                                    albumMirrorCustom = albumMirrorCustom + name
                                    albumMirrorSelection = albumMirrorSelection + name
                                },
                                onRemoveCustom = { name ->
                                    albumMirrorTouched = true
                                    albumMirrorCustom = albumMirrorCustom - name
                                    albumMirrorSelection = albumMirrorSelection - name
                                },
                            )
                        }
                        OnboardingStep.Privacy -> PrivacyStep(
                            stripMetadata = stripMetadata,
                            stripGps = stripGps,
                            stripCamera = stripCamera,
                            stripTimestamp = stripTimestamp,
                            stripSoftware = stripSoftware,
                            renameOnUpload = renameOnUpload,
                            deleteAfterBackup = deleteAfterBackup,
                            onStripChange = { stripMetadata = it },
                            onStripGps = { stripGps = it },
                            onStripCamera = { stripCamera = it },
                            onStripTimestamp = { stripTimestamp = it },
                            onStripSoftware = { stripSoftware = it },
                            onRenameChange = { renameOnUpload = it },
                            onDeleteChange = { deleteAfterBackup = it },
                        )
                        OnboardingStep.MirrorOptIn -> MirrorOptInStep(
                            enabled = mirrorToLocal,
                            granted = allFilesGranted,
                            onEnabledChange = { mirrorToLocal = it },
                            onGrant = {
                                allFilesLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            },
                        )
                        OnboardingStep.MachineLearning -> MlIntroStep(
                            face = mlFace,
                            semantic = mlSemantic,
                            ocr = mlOcr,
                            onFaceChange = { mlFace = it },
                            onSemanticChange = { mlSemantic = it },
                            onOcrChange = { mlOcr = it },
                        )
                        OnboardingStep.AppLock -> AppLockStep(
                            enabled = appLockEnabled,
                            timeoutSeconds = appLockTimeoutSeconds,
                            onEnabledChange = {
                                appLockEnabled = it
                                appLockPrefilled = true
                            },
                            onTimeoutChange = {
                                appLockTimeoutSeconds = it
                                appLockPrefilled = true
                            },
                        )
                        OnboardingStep.Notifications -> NotificationsStep(
                            granted = notificationGranted,
                            onAllow = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                        OnboardingStep.AllFilesAccess -> AllFilesAccessStep(
                            granted = allFilesGranted,
                            onAllow = {
                                allFilesLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            },
                        )
                        OnboardingStep.ManageMedia -> ManageMediaStep(
                            granted = manageMediaGranted,
                            onAllow = {
                                manageMediaLauncher.launch(
                                    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            },
                        )
                        OnboardingStep.Done -> DoneStep()
                    }
                }
            }

            // ── Bottom nav ──────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            val isLastStep = currentStep == OnboardingStep.Done
            val isPermissionStep = currentStep == OnboardingStep.Notifications ||
                currentStep == OnboardingStep.PhotosAccess ||
                currentStep == OnboardingStep.AllFilesAccess ||
                currentStep == OnboardingStep.ManageMedia
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentPage > 0 && !isLastStep) {
                    SecondaryButton(
                        label = stringResource(R.string.onboarding_back),
                        onClick = { scope.launch { pagerState.animateScrollToPage(currentPage - 1) } },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // A permission step's CTA reads "Continue" once its permission is granted,
                // "Skip" while it's still outstanding.
                val currentStepGranted = when (currentStep) {
                    OnboardingStep.Notifications -> notificationGranted
                    OnboardingStep.PhotosAccess  -> mediaGranted
                    OnboardingStep.AllFilesAccess -> allFilesGranted
                    OnboardingStep.ManageMedia   -> manageMediaGranted
                    else -> false
                }
                val ctaLabel: String
                val ctaAction: () -> Unit
                when {
                    isLastStep -> {
                        ctaLabel = stringResource(R.string.onboarding_finish)
                        ctaAction = {
                            viewModel.finishOnboarding(
                                backupMode = backupMode,
                                excludedFolders = excludedFolders,
                                selectedFolders = selectedFolders,
                                stripMetadata = stripMetadata,
                                stripGps = stripGps,
                                stripCamera = stripCamera,
                                stripTimestamp = stripTimestamp,
                                stripSoftware = stripSoftware,
                                renameOnUpload = renameOnUpload,
                                mirrorToLocal = mirrorToLocal,
                                deleteAfterBackup = deleteAfterBackup,
                                mlFace = mlFace,
                                mlSemantic = mlSemantic,
                                mlOcr = mlOcr,
                                themeMode = themeMode,
                                palette = palette,
                                language = language,
                                appLockEnabled = appLockEnabled,
                                appLockTimeoutSeconds = appLockTimeoutSeconds,
                                albumMirrorSelection = albumMirrorSelection,
                                albumMirrorCustom = albumMirrorCustom,
                                // Backing up everything makes every folder an album unless it was unticked, a folder
                                // created later included, so the unticked ones are recorded as switched off.
                                albumMirrorOptOut = if (backupMode == BackupMode.Everything) {
                                    viewModel.folders.value.map { it.name }.toSet() - albumMirrorSelection - excludedFolders
                                } else {
                                    emptySet()
                                },
                                // Weakening the lock asks for the app lock prompt first, as Settings does.
                                reauthenticate = { onAuthenticated ->
                                    showAppLockPrompt(context, onSuccess = onAuthenticated)
                                },
                                onAppliedLocale = onComplete,
                            )
                        }
                    }
                    isPermissionStep -> {
                        ctaLabel = stringResource(
                            if (currentStepGranted) R.string.onboarding_continue
                            else R.string.onboarding_skip,
                        )
                        ctaAction = {
                            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                        }
                    }
                    else -> {
                        ctaLabel = stringResource(R.string.onboarding_continue)
                        ctaAction = {
                            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                        }
                    }
                }
                PrimaryButton(
                    label = ctaLabel,
                    onClick = ctaAction,
                    modifier = Modifier.weight(1.5f),
                )
            }
        }
    }
}

private enum class OnboardingStep {
    About, Welcome, Faq, Appearance, PhotosAccess, AllFilesAccess, BackupMode, FolderPicker,
    AlbumMirrorOptIn, Privacy, MirrorOptIn, MachineLearning, AppLock, Notifications, ManageMedia, Done,
}

private fun hasMediaPermission(context: Context): Boolean =
    MediaPermissions.readSet(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
