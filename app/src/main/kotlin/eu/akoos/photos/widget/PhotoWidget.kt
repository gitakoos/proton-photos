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

package eu.akoos.photos.widget

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.io.File
import eu.akoos.photos.MainActivity

class PhotoWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val prefs      = currentState<Preferences>()
        val cachedPath = prefs[PhotoWidgetKeys.CACHED_BITMAP_PATH]
        val bitmap     = cachedPath?.let { loadCachedBitmap(it) }
        val currentUri = prefs[PhotoWidgetKeys.CURRENT_URI]

        // Light/dark surfaces follow the system. The image (when present) covers the full
        // background, so the bg only shows during the placeholder state — but we still want
        // that placeholder to be legible on both light and dark home screens.
        val isLight = isSystemLight()
        val backgroundColor = if (isLight) Color(0xFFF5F5F5) else Color(0xFF0E0E0E)
        val placeholderColor = if (isLight) Color(0xFF4A4A55) else Color(0xFFA4A1B4)

        // Tap target: open MainActivity with the current photo's URI so the gallery can
        // route straight to the viewer for that photo. The CURRENT_URI prefs key gets
        // rewritten every widget tick by PhotoWidgetUpdater, so the intent always opens
        // whatever the widget is showing right now.
        val context = LocalContext.current
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            // CLEAR_TOP + SINGLE_TOP so a foreground app doesn't pile up a second instance
            // — onNewIntent picks up the URI in MainActivity and re-forwards to NavGraph.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (currentUri != null) {
                putExtra(MainActivity.EXTRA_WIDGET_PHOTO_URI, currentUri)
            }
        }

        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .cornerRadius(16.dp)
                    .clickable(actionStartActivity(tapIntent)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        provider        = ImageProvider(bitmap),
                        contentDescription = null,
                        contentScale    = ContentScale.Crop,
                        modifier        = GlanceModifier.fillMaxSize(),
                    )
                } else {
                    // Placeholder when no photo is loaded yet
                    Text(
                        text  = "Photos for Proton",
                        style = TextStyle(
                            color      = ColorProvider(placeholderColor),
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.padding(16.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun isSystemLight(): Boolean {
        val ctx = LocalContext.current
        val nightMask = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMask != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        /**
         * Re-decoding a 480px JPEG from disk on every recompose (every state read, every
         * configuration change) costs ~5–15ms and a fresh allocation each time. Cache the
         * decoded Bitmap keyed by absolute path + lastModified so the next widget update with
         * the same file reuses it. The worker rewrites the file when it advances to the next
         * photo, which changes lastModified ⇒ stale entries are dropped automatically.
         */
        @Volatile private var cachedPath: String? = null
        @Volatile private var cachedLastModified: Long = 0L
        @Volatile private var cachedBitmap: Bitmap? = null

        @JvmStatic
        @Synchronized
        fun loadCachedBitmap(path: String): Bitmap? {
            val file = File(path)
            if (!file.exists()) return null
            val lastMod = file.lastModified()
            if (cachedPath == path && cachedLastModified == lastMod && cachedBitmap != null) {
                return cachedBitmap
            }
            val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
            cachedPath = path
            cachedLastModified = lastMod
            cachedBitmap = decoded
            return decoded
        }
    }
}
