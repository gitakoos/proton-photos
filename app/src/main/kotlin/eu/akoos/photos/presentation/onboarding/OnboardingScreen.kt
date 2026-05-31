/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.presentation.onboarding.components.ProgressDots
import eu.akoos.photos.presentation.onboarding.steps.AboutStep
import eu.akoos.photos.presentation.onboarding.steps.AppLockStep
import eu.akoos.photos.presentation.onboarding.steps.AppearanceStep
import eu.akoos.photos.presentation.onboarding.steps.BackupModeStep
import eu.akoos.photos.presentation.onboarding.steps.DoneStep
import eu.akoos.photos.presentation.onboarding.steps.FolderPickerStep
import eu.akoos.photos.presentation.onboarding.steps.ManageMediaStep
import eu.akoos.photos.presentation.onboarding.steps.NotificationsStep
import eu.akoos.photos.presentation.onboarding.steps.PhotosAccessStep
import eu.akoos.photos.presentation.onboarding.steps.PrivacyStep
import eu.akoos.photos.presentation.onboarding.steps.WelcomeStep
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    localMediaRepo: LocalMediaRepository,
) : ViewModel() {

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
     * Theme + palette are written immediately on selection so the wizard re-themes
     * live — the user sees the choice the moment they pick. They never trigger an
     * Activity recreate, unlike language. Wrapped in viewModelScope so the
     * composition stays responsive.
     */
    fun setThemeLive(mode: ThemeMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.THEME_MODE] = mode.storageKey }
        }
    }

    fun setPaletteLive(palette: ThemePalette) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.THEME_PALETTE] = palette.storageKey }
        }
    }

    /**
     * Language is applied live so the wizard itself relocalizes the moment a
     * locale is picked. `setApplicationLocales` triggers an Activity recreate;
     * the onboarding state (rememberSaveable backupMode, folder picks, etc.) and
     * the PagerState both survive the recreate, so the user lands back on the
     * Appearance step in the new language.
     */
    fun setLanguageLive(tag: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = tag }
            applyLocale(tag)
        }
    }

    /**
     * Commits every onboarding choice in a single DataStore transaction. Theme +
     * palette are already persisted (live above), but we re-write them here so a
     * partial mid-wizard write paired with a process death still ends up with
     * the user's final selections after they re-walk the wizard.
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
        deleteAfterBackup: Boolean,
        themeMode: ThemeMode,
        palette: ThemePalette,
        language: String,
        appLockEnabled: Boolean,
        appLockTimeoutMinutes: Int,
        onAppliedLocale: () -> Unit,
    ) {
        viewModelScope.launch {
            context.settingsDataStore.edit { p ->
                when (backupMode) {
                    BackupMode.Everything -> {
                        p[SettingsKeys.BACKUP_EVERYTHING] = true
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
                p[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] = deleteAfterBackup
                p[SettingsKeys.THEME_MODE] = themeMode.storageKey
                p[SettingsKeys.THEME_PALETTE] = palette.storageKey
                p[SettingsKeys.LANGUAGE] = language
                p[SettingsKeys.APP_LOCK_ENABLED] = appLockEnabled
                p[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] = appLockTimeoutMinutes
                p[SettingsKeys.ONBOARDING_COMPLETE] = true
            }
            applyLocale(language)
            onAppliedLocale()
        }
    }

    private fun applyLocale(tag: String) {
        val locales = if (tag == "system" || tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
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
    var backupMode by rememberSaveable { mutableStateOf(BackupMode.Everything) }
    var selectedFolders by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var excludedFolders by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var stripMetadata by rememberSaveable { mutableStateOf(true) }
    var stripGps by rememberSaveable { mutableStateOf(true) }
    var stripCamera by rememberSaveable { mutableStateOf(false) }
    var stripTimestamp by rememberSaveable { mutableStateOf(false) }
    var stripSoftware by rememberSaveable { mutableStateOf(false) }
    var renameOnUpload by rememberSaveable { mutableStateOf(false) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(false) }
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var palette by rememberSaveable { mutableStateOf(ThemePalette.Default) }
    var language by rememberSaveable { mutableStateOf("system") }
    var appLockEnabled by rememberSaveable { mutableStateOf(false) }
    var appLockTimeoutMinutes by rememberSaveable { mutableIntStateOf(5) }

    var notificationGranted by rememberSaveable { mutableStateOf(false) }
    var mediaGranted by rememberSaveable {
        mutableStateOf(hasMediaPermission(context))
    }
    var manageMediaGranted by rememberSaveable {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)
        )
    }

    val mediaPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val showManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val showNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Step list — order is About → Welcome → Appearance → Photos perm → Backup
    // mode → conditional Folder picker → Privacy → App lock → Notifications →
    // Manage media → Done. Photos perm comes before the folder picker so when
    // we hit that step MediaStore can actually enumerate buckets; otherwise the
    // list would render empty.
    val steps = buildList {
        add(OnboardingStep.About)
        add(OnboardingStep.Welcome)
        add(OnboardingStep.Appearance)
        add(OnboardingStep.PhotosAccess)
        add(OnboardingStep.BackupMode)
        if (backupMode != BackupMode.NothingForNow) {
            add(OnboardingStep.FolderPicker)
        }
        add(OnboardingStep.Privacy)
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
        mediaGranted = result.values.all { it }
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }
    val manageMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        manageMediaGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            MediaStore.canManageMedia(context)
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
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
                        OnboardingStep.Appearance -> AppearanceStep(
                            themeMode = themeMode,
                            palette = palette,
                            language = language,
                            onThemeMode = {
                                themeMode = it
                                viewModel.setThemeLive(it)
                            },
                            onPalette = {
                                palette = it
                                viewModel.setPaletteLive(it)
                            },
                            onLanguage = {
                                language = it
                                viewModel.setLanguageLive(it)
                            },
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
                        OnboardingStep.AppLock -> AppLockStep(
                            enabled = appLockEnabled,
                            timeoutMinutes = appLockTimeoutMinutes,
                            onEnabledChange = { appLockEnabled = it },
                            onTimeoutChange = { appLockTimeoutMinutes = it },
                        )
                        OnboardingStep.Notifications -> NotificationsStep(
                            granted = notificationGranted,
                            onAllow = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
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
                currentStep == OnboardingStep.ManageMedia
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentPage > 0 && !isLastStep) {
                    TextButton(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.onboarding_back), color = colors.fgDim, fontSize = 14.sp)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
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
                                deleteAfterBackup = deleteAfterBackup,
                                themeMode = themeMode,
                                palette = palette,
                                language = language,
                                appLockEnabled = appLockEnabled,
                                appLockTimeoutMinutes = appLockTimeoutMinutes,
                                onAppliedLocale = onComplete,
                            )
                        }
                    }
                    isPermissionStep -> {
                        ctaLabel = stringResource(R.string.onboarding_skip)
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
                Button(
                    onClick = ctaAction,
                    modifier = Modifier.weight(1.5f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(ctaLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private enum class OnboardingStep {
    About, Welcome, Appearance, PhotosAccess, BackupMode, FolderPicker,
    Privacy, AppLock, Notifications, ManageMedia, Done,
}

private fun hasMediaPermission(context: Context): Boolean {
    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return perms.all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
