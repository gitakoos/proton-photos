package me.proton.photos.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Central helper that advances the widget to its next photo, loads + caches the
 * bitmap, persists updated state, and triggers a Glance redraw.
 */
object PhotoWidgetUpdater {

    /** Max long edge for cached widget bitmaps (RGB_565 → ~450 KB at 480 px, safe under 1 MB Binder limit). */
    private const val MAX_BITMAP_PX = 480

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun update(context: Context, appWidgetId: Int) = withContext(Dispatchers.IO) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val glanceId = manager.getGlanceIdBy(appWidgetId)

            // 1. Read current Glance state
            val prefs = androidx.glance.appwidget.state.getAppWidgetState(
                context, PreferencesGlanceStateDefinition, glanceId,
            )

            val mode = WidgetMode.valueOf(
                prefs[PhotoWidgetKeys.MODE] ?: WidgetMode.ALL_PHOTOS.name,
            )
            val selectedUrisRaw = prefs[PhotoWidgetKeys.SELECTED_URIS] ?: ""
            val selectedUris    = if (selectedUrisRaw.isBlank()) emptyList()
                                  else selectedUrisRaw.split(PhotoWidgetKeys.URI_SEPARATOR)
            val albumName       = prefs[PhotoWidgetKeys.ALBUM_NAME]?.takeIf { it.isNotBlank() }
            val currentIndex    = prefs[PhotoWidgetKeys.CURRENT_INDEX] ?: 0

            // 2. Resolve the photo list for this mode
            val photos = resolvePhotos(context, mode, selectedUris, albumName)
            if (photos.isEmpty()) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }

            // 3. Advance index (wrap around)
            val nextIndex = if (mode == WidgetMode.ALL_PHOTOS) {
                // Pure random for ALL_PHOTOS — ignore stored index
                (0 until photos.size).random()
            } else {
                (currentIndex + 1) % photos.size
            }
            val photoUri = photos[nextIndex]

            // 4. Load, scale, save bitmap to private cache
            val bitmap   = loadScaledBitmap(context, photoUri)
            val cachePath = if (bitmap != null) {
                saveBitmapToCache(context, appWidgetId, bitmap)
            } else null

            // 5. Persist state changes
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().also { mp ->
                    mp[PhotoWidgetKeys.CURRENT_INDEX] = nextIndex
                    mp[PhotoWidgetKeys.CURRENT_URI]   = photoUri
                    if (cachePath != null) mp[PhotoWidgetKeys.CACHED_BITMAP_PATH] = cachePath
                    else mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                }
            }

            // 6. Ask Glance to redraw
            PhotoWidget().update(context, glanceId)

        } catch (_: Exception) {
            // Widget update failure is non-fatal — will retry next cycle
        }
    }

    /** Clean up cached file and state when widget is removed. */
    suspend fun cleanup(context: Context, appWidgetId: Int) {
        try {
            File(context.cacheDir, cacheFileName(appWidgetId)).delete()
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().also { mp ->
                    mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                    mp.remove(PhotoWidgetKeys.CURRENT_URI)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun clearBitmapState(context: Context, glanceId: GlanceId, id: Int) {
        File(context.cacheDir, cacheFileName(id)).delete()
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().also { mp ->
                mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                mp.remove(PhotoWidgetKeys.CURRENT_URI)
            }
        }
        PhotoWidget().update(context, glanceId)
    }

    private fun resolvePhotos(
        context: Context,
        mode: WidgetMode,
        selectedUris: List<String>,
        albumName: String?,
    ): List<String> = when (mode) {
        WidgetMode.SELECTED   -> selectedUris.ifEmpty { queryAllImages(context) }
        WidgetMode.ALBUM      -> albumName?.let { queryAlbumImages(context, it) } ?: queryAllImages(context)
        WidgetMode.ALL_PHOTOS -> queryAllImages(context)
    }

    private fun queryAllImages(context: Context): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                result += Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(col).toString(),
                ).toString()
            }
        }
        return result
    }

    private fun queryAlbumImages(context: Context, album: String): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            arrayOf(album),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                result += Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(col).toString(),
                ).toString()
            }
        }
        return result
    }

    fun loadScaledBitmap(context: Context, uriString: String): Bitmap? = try {
        val uri  = Uri.parse(uriString)
        // First pass: read dimensions only
        val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts1)
        }
        val longest   = maxOf(opts1.outWidth, opts1.outHeight).coerceAtLeast(1)
        val sample    = Integer.highestOneBit((longest / MAX_BITMAP_PX).coerceAtLeast(1))
        // Second pass: decode at reduced size, using memory-efficient RGB_565
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize       = sample
            inPreferredConfig  = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts2)
        }
    } catch (_: Exception) { null }

    private fun saveBitmapToCache(context: Context, id: Int, bitmap: Bitmap): String {
        val file = File(context.cacheDir, cacheFileName(id))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    private fun cacheFileName(id: Int) = "widget_$id.jpg"
}
