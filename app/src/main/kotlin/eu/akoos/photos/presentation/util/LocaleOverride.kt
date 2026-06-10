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

package eu.akoos.photos.presentation.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import java.util.Locale

/**
 * Walk the [ContextWrapper.baseContext] chain to find the hosting [Activity].
 * Needed because [LocaleOverride] wraps [LocalContext] in a [ContextWrapper] —
 * code that previously wrote `LocalContext.current as FragmentActivity` would
 * see the wrapper class, not the Activity, and ClassCastException out.
 *
 * Returns null if no Activity is in the chain (e.g., during preview).
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Convenience for the biometric / fragment paths — same chain walk, narrower type. */
fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

/**
 * Re-resolves [androidx.compose.ui.res.stringResource] calls under [content]
 * against the user-selected locale without triggering an Activity recreate.
 *
 * Why this exists: the obvious approach —
 * `AppCompatDelegate.setApplicationLocales(...)` — recreates every Activity in
 * the task and wipes the in-memory navigation state. The user would land back
 * on the Gallery screen mid-onboarding and watch a reload animation; the same
 * happens from the Settings screen. By overriding the [LocalContext]
 * composition local under our root, we keep the Activity intact and let
 * Compose recompose the affected subtree with the new locale.
 *
 * Three mechanisms cooperate to make the override complete:
 *  1. The [CompositionLocalProvider] below wraps [LocalContext] in a
 *     locale-specific [ContextWrapper], so `stringResource(R.string.X)` calls
 *     under [content] resolve in the chosen language.
 *  2. A [DisposableEffect] keyed on [language] calls [Locale.setDefault] for
 *     [Locale.Category.FORMAT], which transparently fixes every
 *     `SimpleDateFormat(..., Locale.getDefault())` site (calendar, gallery,
 *     viewer, search, scrubber) without touching the formatters themselves.
 *     The boot-time format locale is captured once and restored when the user
 *     reverts to `"system"`.
 *  3. The same effect calls [AppCompatDelegate.setApplicationLocales] so the
 *     Hilt-injected `@ApplicationContext` Context's resources also track the
 *     override — any `applicationContext.getString(R.string.X)` callers (e.g.
 *     `friendlyNetworkError` paths in view-models) pick up the right strings.
 *     Activity recreation on that call is suppressed by the manifest's
 *     `android:configChanges="locale|layoutDirection|..."` on MainActivity,
 *     so the in-memory navigation state and the LocaleOverride wrapper above
 *     survive the locale flip and just recompose with the new strings.
 *
 * The `"system"` tag short-circuits the Compose wrapper so RTL, regional
 * resources, and any non-string [Configuration] bits flow through unchanged.
 *
 * Cold-start parity for the externally-launched ProtonCore login activities
 * (XML-based, outside this Compose tree) is provided by a single
 * `AppCompatDelegate.setApplicationLocales` call in [eu.akoos.photos.App.onCreate]
 * driven by the [eu.akoos.photos.data.preferences.LanguagePrefsBoot] mirror.
 *
 * @param language BCP-47 tag (`"en"`, `"hu"`, etc.) or `"system"` for OS default.
 */
@Composable
fun LocaleOverride(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    // Snapshot the boot-time format locale exactly once per composition, BEFORE any
    // setDefault call below runs. Used to restore the OS default when the user
    // picks "system" after previously selecting an explicit BCP-47 tag.
    val systemFormatLocale = remember { Locale.getDefault(Locale.Category.FORMAT) }

    // Drive Locale.getDefault() and AppCompatDelegate.setApplicationLocales off the
    // current language tag. Together they cover the two surfaces the Compose-side
    // LocalContext wrapper alone cannot reach:
    //   - Locale.getDefault(): every cached SimpleDateFormat / NumberFormat / etc.
    //     that was built once at process start. Bumping the default makes the next
    //     format() call render in the chosen language.
    //   - setApplicationLocales(): mutates the configuration of the singleton
    //     Hilt @ApplicationContext, so code that reaches for
    //     applicationContext.resources.getString(...) outside the Compose tree
    //     (view-models, workers, error-message helpers) also resolves correctly.
    //     The cooperating configChanges flag in the manifest absorbs the
    //     would-be Activity recreate.
    DisposableEffect(language) {
        if (language.isEmpty() || language == "system") {
            Locale.setDefault(Locale.Category.FORMAT, systemFormatLocale)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val tagLocale = Locale.forLanguageTag(language)
            Locale.setDefault(Locale.Category.FORMAT, tagLocale)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
        }
        onDispose { }
    }

    // ALWAYS provide LocalContext — even for "system". When the wrapper is added
    // or removed from the composition (early-return on "system") Compose treats it
    // as a structural rearrangement and the downstream subtree animates. Keeping
    // the provider constantly in the tree (with `context` itself for the no-op
    // case) means switching to or from "system" is just a value change like any
    // other locale swap, and the in-place recompose is invisible.
    val localizedContext = remember(context, language, configuration) {
        if (language.isEmpty() || language == "system") {
            context
        } else {
            val locale = Locale.forLanguageTag(language)
            val newConfig = Configuration(configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            // Build a configuration-specific context for its localized Resources, then
            // wrap ONLY the resources/assets back over the original Activity context.
            // Returning the raw createConfigurationContext result via LocalContext would
            // hand Compose a plain ContextImpl with no baseContext chain — Hilt's
            // HiltViewModelFactory walks ContextWrapper.baseContext to find the hosting
            // Activity and crashes with "Expected an activity context for creating a
            // HiltViewModelFactory" any time a hiltViewModel() call recomposes under
            // the override.
            val resourcesForLocale = context.createConfigurationContext(newConfig).resources
            object : ContextWrapper(context) {
                override fun getResources(): Resources = resourcesForLocale
                override fun getAssets() = resourcesForLocale.assets
            }
        }
    }
    // We deliberately do NOT also provide LocalConfiguration. Pushing a new
    // Configuration object through a CompositionLocalProvider invalidates every
    // composable downstream that even glances at the configuration — many of them
    // do indirectly via material widgets — and the cascade reads as a fade / scale
    // on switch. LocalContext alone is enough: stringResource resolves via
    // LocalContext.current.resources, so providing the wrapped context (whose
    // resources are localized) is what actually makes the strings flip.
    CompositionLocalProvider(LocalContext provides localizedContext) {
        content()
    }
}
