package eu.akoos.photos.widget

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
import androidx.glance.action.actionStartActivity
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
import androidx.glance.layout.wrapContentSize
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

        // Light/dark surfaces follow the system. The image (when present) covers the full
        // background, so the bg only shows during the placeholder state — but we still want
        // that placeholder to be legible on both light and dark home screens.
        val isLight = isSystemLight()
        val backgroundColor = if (isLight) Color(0xFFF5F5F5) else Color(0xFF0E0E0E)
        val placeholderColor = if (isLight) Color(0xFF4A4A55) else Color(0xFFA4A1B4)

        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .cornerRadius(16.dp)
                    .clickable(actionStartActivity<MainActivity>()),
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
                        text  = "Proton Photos",
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
