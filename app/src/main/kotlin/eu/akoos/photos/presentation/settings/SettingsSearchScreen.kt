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

package eu.akoos.photos.presentation.settings

import androidx.annotation.StringRes
import eu.akoos.photos.R
import eu.akoos.photos.navigation.Screen

/**
 * One searchable setting. [titleRes] is the localized name shown as the result; [breadcrumbRes] names
 * the group or page it lives under (the result's subtitle, so its home is obvious); [keywords] are
 * lowercase match hints — the obvious English technical term plus a Hungarian synonym where it helps —
 * matched accent-insensitively alongside the title; [route] is the NavGraph destination the tap opens;
 * [cloud] flags a cloud-only destination, hidden from search while signed out.
 */
data class SettingsSearchEntry(
    @StringRes val titleRes: Int,
    @StringRes val breadcrumbRes: Int,
    val keywords: List<String>,
    val route: String,
    val cloud: Boolean = false,
)

/**
 * The curated map of what Settings holds: every top-level page plus the key toggles and concepts that
 * moved inside the six consolidated groups, each pointing at the page that hosts it. Several concepts
 * share one route (the compression, naming and metadata toggles all live on the backup page), which is
 * enough for v1 — the search takes the user to the page, not the exact control.
 */
fun settingsSearchIndex(): List<SettingsSearchEntry> = listOf(
    // ── Account ────────────────────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.settings_account_section, R.string.settings_title,
        listOf("account", "profile", "sign out", "log out", "subscription", "fiók", "kijelentkezés"),
        Screen.Account.route,
        cloud = true,
    ),

    // ── Backup & storage ───────────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.sync_open_settings, R.string.settings_backup_storage_section,
        listOf("backup", "upload", "sync", "biztonsági mentés", "feltöltés"),
        Screen.SyncSettings.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.activity_title, R.string.settings_backup_storage_section,
        listOf("activity", "transfers", "progress", "uploads", "downloads", "folyamat"),
        "activity?tab=uploads",
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_storage_section, R.string.settings_backup_storage_section,
        listOf("storage", "space", "quota", "usage", "gb", "capacity", "full", "free space", "tárhely", "hely", "kapacitás", "megtelt"),
        Screen.StorageSettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_recently_deleted, R.string.settings_backup_storage_section,
        listOf("trash", "deleted", "recently deleted", "bin", "kuka", "törölt"),
        "trash?tab=device",
    ),
    SettingsSearchEntry(
        R.string.settings_find_duplicates, R.string.settings_backup_storage_section,
        listOf("duplicate", "duplicates", "similar", "copies", "duplikátum", "másolat"),
        Screen.DuplicateFinder.route,
    ),
    SettingsSearchEntry(
        R.string.settings_storage_free_up, R.string.settings_storage_section,
        listOf("free up space", "reclaim", "remove local", "hely felszabadítás"),
        Screen.FreeUpSpace.route,
        cloud = true,
    ),
    // Concepts that live inside the backup page.
    SettingsSearchEntry(
        R.string.settings_continuous_backup, R.string.sync_section,
        listOf("automatic", "continuous", "background", "folyamatos", "automatikus"),
        Screen.SyncSettings.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_backup_folders, R.string.sync_section,
        listOf("folders", "sources", "albums", "mappák"),
        Screen.SyncFolders.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_excluded_folders, R.string.sync_section,
        listOf("exclude", "ignore", "skip", "kizárt", "mappák"),
        Screen.ExcludedFolders.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_sync_wifi_only, R.string.sync_section,
        listOf("wifi", "wi-fi", "network", "mobile data", "cellular", "data", "roaming", "internet", "hálózat", "mobil", "adat"),
        Screen.SyncSettings.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_rename_on_upload, R.string.settings_metadata,
        listOf("rename", "filename", "file name", "capture date", "date taken", "átnevezés", "fájlnév", "dátum"),
        Screen.BackupProcessing.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_strip_metadata_upload, R.string.settings_metadata,
        listOf("metadata", "exif", "strip", "remove", "gps", "location", "coordinates", "metaadat", "helyadat", "eltávolítás"),
        Screen.BackupProcessing.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_compress_photos, R.string.settings_metadata,
        listOf("compress", "compression", "quality", "size", "resolution", "reduce", "shrink", "jpeg", "megapixel", "tömörítés", "minőség", "méret", "felbontás", "kicsinyítés"),
        Screen.BackupProcessing.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_compress_videos, R.string.settings_metadata,
        listOf("video", "compress", "compression", "quality", "resolution", "bitrate", "1080p", "4k", "videó", "tömörítés", "minőség", "felbontás"),
        Screen.BackupProcessing.route,
        cloud = true,
    ),

    // ── Machine learning ───────────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.settings_ai_section, R.string.settings_title,
        listOf("ai", "machine learning", "ml", "on-device", "mesterséges intelligencia"),
        Screen.AiSettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_ai_ocr, R.string.settings_ai_section,
        listOf("text", "ocr", "copy text", "read text", "scan", "szöveg"),
        Screen.AiSettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_face_section, R.string.settings_ai_section,
        listOf("face", "faces", "people", "recognition", "arc", "emberek"),
        Screen.FaceRecognition.route,
    ),

    // ── Privacy & security ─────────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.settings_privacy_security, R.string.settings_title,
        listOf("privacy", "security", "adatvédelem", "biztonság"),
        Screen.PrivacySecuritySettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_app_lock, R.string.settings_privacy_security,
        listOf("lock", "app lock", "biometric", "pin", "fingerprint", "zár", "biometria"),
        Screen.PrivacySecuritySettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_clear_cache_on_close, R.string.settings_privacy_security,
        listOf("cache", "clear cache", "gyorsítótár"),
        Screen.PrivacySecuritySettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_strip_share, R.string.settings_privacy_security,
        listOf("metadata", "exif", "strip", "share", "sharing", "location", "gps", "megosztás", "metaadat", "helyadat"),
        Screen.ShareMetadata.route,
    ),
    SettingsSearchEntry(
        R.string.settings_offline_photos, R.string.settings_privacy_security,
        listOf("offline", "download", "available offline", "pinned", "letöltés"),
        Screen.Offline.route,
        cloud = true,
    ),
    SettingsSearchEntry(
        R.string.settings_hidden_photos, R.string.settings_privacy_security,
        listOf("hidden", "hide", "vault", "private", "secret", "conceal", "rejtett", "széf", "titkos", "elrejtés"),
        Screen.HiddenAlbum.route,
    ),
    SettingsSearchEntry(
        R.string.permissions_title, R.string.settings_privacy_security,
        listOf("permissions", "access", "grant", "engedélyek"),
        Screen.Permissions.route,
    ),
    SettingsSearchEntry(
        R.string.notifications_title, R.string.settings_privacy_security,
        listOf("notifications", "alerts", "értesítések"),
        Screen.NotificationSettings.route,
    ),

    // ── Appearance & language ──────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.settings_appearance, R.string.settings_title,
        listOf("appearance", "display", "megjelenés"),
        Screen.AppearanceSettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_theme_palette, R.string.settings_appearance,
        listOf("theme", "palette", "color", "colour", "dark", "light", "night", "amoled", "black", "accent", "appearance", "téma", "szín", "sötét", "világos", "éjszakai", "kinézet"),
        Screen.ThemeSettings.route,
    ),
    SettingsSearchEntry(
        R.string.language_section, R.string.settings_appearance,
        listOf("language", "locale", "nyelv"),
        Screen.LanguageSettings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_timeline, R.string.settings_appearance,
        listOf("timeline", "layout", "grid", "categories", "idővonal"),
        Screen.TimelineFilter.route,
    ),

    // ── Help & about ───────────────────────────────────────────────────────────
    SettingsSearchEntry(
        R.string.whats_new_title, R.string.settings_help_about_section,
        listOf("whats new", "changelog", "release notes", "updates", "újdonságok"),
        Screen.WhatsNewHistory.route,
    ),
    SettingsSearchEntry(
        R.string.news_title, R.string.settings_help_about_section,
        listOf("news", "announcements", "hírek"),
        Screen.News.route,
    ),
    SettingsSearchEntry(
        R.string.faq_settings_entry, R.string.settings_help_about_section,
        listOf("faq", "help", "questions", "support", "súgó"),
        Screen.Faq.route,
    ),
    SettingsSearchEntry(
        R.string.about_title, R.string.settings_help_about_section,
        listOf("about", "version", "license", "info", "névjegy", "verzió"),
        Screen.About.route,
    ),
    SettingsSearchEntry(
        R.string.update_check_settings_row, R.string.settings_help_about_section,
        listOf("update", "check updates", "version", "frissítés"),
        Screen.Settings.route,
    ),
    SettingsSearchEntry(
        R.string.settings_screenshot_overlay, R.string.settings_help_about_section,
        listOf("screenshot", "overlay", "quick actions", "képernyőkép"),
        Screen.Settings.route,
    ),
)
