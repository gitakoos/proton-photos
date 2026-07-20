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

package eu.akoos.photos.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.paletteAccent
import eu.akoos.photos.util.copySensitiveText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Opt-in screenshot quick-actions overlay. Enabled from Settings; while enabled it watches MediaStore
 * for a freshly saved screenshot (from any app) and floats a small action bar over the current screen,
 * so the shot can be edited, shared, uploaded to Drive, or turned into a public link without leaving
 * whatever app is in front. Started and stopped by the Settings toggle, and restarted on app launch
 * when the toggle is on and the draw-over-other-apps grant is in place.
 *
 * The overlay has three states inside one WindowManager view. COMPACT is a bottom bar (thumbnail plus
 * Edit, Share and a round close). PREVIEW centres the shot on a dimmed, blurred scrim with a round icon
 * action row (Edit, Upload, Link, Copy) and Share / Delete in the top corner; because the window stays
 * non-focusable, taps outside the shot fall through to the app behind. DRAW turns the annotation layer
 * touch-enabled and swaps the bottom row for the markup tools (pen, pixelate, eraser); picking a tool
 * reveals a size slider, and the pen adds a color button that opens a scrollable palette. Undo and Done
 * sit in the top-right, and the annotation stays painted in PREVIEW so a finished markup shows on the shot.
 *
 * Upload backs the shot up through the normal backup pipeline and confirms in place; Link uploads then
 * mints a public share link, copies it, and shows it with Copy / Share. Copy and Share export the
 * ANNOTATED shot (the markup composited onto a full-resolution copy) when the layer has content, else
 * the original screenshot. Delete asks MediaStore to remove the screenshot (system confirmation on R+).
 * Detection is a name/path heuristic over the newest MediaStore image within a short recent window; the
 * overlay's own saved edits are skipped so an upload or link never re-triggers it.
 *
 * Mirrors [BackgroundSyncService] for the foreground + channel handling and reuses the MediaStore
 * ContentObserver pattern used elsewhere in the app.
 */
@AndroidEntryPoint
class ScreenshotOverlayService : Service() {

    @Inject lateinit var forceUpload: ForceUploadLocalUrisUseCase
    @Inject lateinit var driveRepo: DrivePhotoRepository
    @Inject lateinit var syncStateRepo: SyncStateRepository
    @Inject lateinit var accountManager: AccountManager

    // Service-lifetime scope for the upload/link polls so they stop with the service instead of
    // outliving it on the application scope. Cancelled in onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Cached theme, resolved off the main thread at service start and read as the overlay builds.
    // Defaults to the dark palette so an overlay built before the read lands still looks right.
    @Volatile private var isLightTheme: Boolean = false
    @Volatile private var accentColor: Int = 0xFF8B7CFF.toInt()

    // Dedicated background looper for the observer so the media query stays off the main thread.
    private var observerThread: HandlerThread? = null
    private var observerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var observer: ContentObserver? = null
    // Drops the overlay when the user leaves to home / recents / the notification shade.
    private var leaveReceiver: BroadcastReceiver? = null
    private var overlayView: View? = null

    // The root FrameLayout plus its two children, kept so the state toggle can crossfade them and
    // the window can be grown/shrunk. The DrawView and expanded ImageView are held for touch/paint
    // and for pushing the full bitmap in once it is decoded. actionContainer is the swappable strip
    // under the image: it holds the PREVIEW action row in PREVIEW and the draw toolbar in DRAW.
    private var overlayRoot: FrameLayout? = null
    private var compactView: View? = null
    private var compactImage: ImageView? = null
    private var expandedView: View? = null
    private var expandedImage: ImageView? = null
    private var drawView: DrawView? = null
    private var actionContainer: LinearLayout? = null
    // The DRAW-mode options area sat ABOVE the tools row: the active tool's size slider (and, for the
    // pen, its color button). Empty and hidden in PREVIEW.
    private var optionsContainer: LinearLayout? = null
    // The color palette, shown in place of the tools and options when the pen color button is tapped.
    private var rgbContainer: LinearLayout? = null
    private var expandedClose: View? = null
    private var expandedTopRight: LinearLayout? = null
    // A non-interactive dot centred over the shot that previews the current brush thickness while the
    // size slider is dragged. Hidden the rest of the time.
    private var sizePreview: View? = null
    // Full-resolution decode of the current shot, held so a shared/copied annotation composites the
    // strokes onto the sharp original rather than the on-screen downscale.
    private var fullBitmap: Bitmap? = null
    // The compact bar's small thumbnail, held so it can be recycled with the overlay.
    private var thumbBitmap: Bitmap? = null

    // Three states from two flags: COMPACT (expanded=false), PREVIEW (expanded, drawing=false),
    // DRAW (expanded, drawing=true). The action-strip contents and the DrawView touch-enabled
    // state are routed off these.
    private var expanded = false
    private var drawing = false
    // The active draw tool (null means TOOL-SELECT, no tool chosen), and whether the color palette is open.
    private var selectedTool: Mode? = null
    private var showingRgb = false
    // Per-tool size levels (1..10), kept so reopening a tool restores its slider position.
    private var penLevel = 4
    private var eraserLevel = 4
    private var pixelLevel = 4
    // drawColor is the current pen color new strokes are painted with and the color button shows.
    private var drawColor = 0xFFFF5252.toInt()
    private var currentShotUri: Uri? = null

    // LINK state: while a public link is minted the bottom bar shows a status/result/error panel
    // instead of the PREVIEW actions. linkUrl holds the finished link (result sub-state); linkFailed
    // marks the error sub-state; with both clear the panel shows the in-progress status.
    private var linking = false
    private var linkUrl: String? = null
    private var linkFailed = false
    // Progress label for the in-progress sub-state ("Uploading..." then "Creating link...").
    private var linkStage = ""
    // Upload success sub-state (no link, just an "Uploaded to Drive" confirmation kept in the panel).
    private var uploadDone = false

    // Preset pen colors for the scrollable palette: white, greys, black, then reds through pinks and browns.
    private val paletteColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF757575.toInt(), 0xFF000000.toInt(),
        0xFFF44336.toInt(), 0xFFFF5252.toInt(), 0xFFE91E63.toInt(), 0xFFFF9800.toInt(),
        0xFFFFC107.toInt(), 0xFFFFEB3B.toInt(), 0xFFCDDC39.toInt(), 0xFF8BC34A.toInt(),
        0xFF4CAF50.toInt(), 0xFF009688.toInt(), 0xFF00BCD4.toInt(), 0xFF03A9F4.toInt(),
        0xFF2196F3.toInt(), 0xFF3F51B5.toInt(), 0xFF673AB7.toInt(), 0xFF9C27B0.toInt(),
        0xFFBA68C8.toInt(), 0xFFFF80AB.toInt(), 0xFF795548.toInt(), 0xFFA1887F.toInt(),
    )

    // Debounce: a single screenshot write fires several onChange bursts, and the same image id
    // must only trigger one overlay. Track the last handled id + when the last overlay showed.
    private var lastHandledId: Long = -1L
    private var lastShownAtMs: Long = 0L
    // The overlay's own last saved-edit Uri, so the observer can cheaply skip the write it just made.
    @Volatile private var lastSelfWriteUri: String? = null

    private val autoDismissRunnable = Runnable { removeOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val foregrounded = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground denied: ${e.message}")
            false
        }
        if (!foregrounded) {
            stopSelf()
            return
        }

        // The overlay cannot be drawn without the SYSTEM_ALERT_WINDOW grant. Bail cleanly
        // rather than crash on the first addView when the permission is missing.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; stopping")
            stopSelf()
            return
        }

        registerObserver()
        registerLeaveReceiver()
        serviceScope.launch(Dispatchers.Default) { runCatching { computeTheme() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainHandler.removeCallbacks(autoDismissRunnable)
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        leaveReceiver?.let { runCatching { unregisterReceiver(it) } }
        leaveReceiver = null
        removeOverlay()
        observerThread?.quitSafely()
        observerThread = null
        observerHandler = null
    }

    private fun registerObserver() {
        val thread = HandlerThread("screenshot-overlay-observer").also { it.start() }
        val handler = Handler(thread.looper)
        observerThread = thread
        observerHandler = handler
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Cheap early-out: skip the query for the annotated copy the overlay itself just wrote.
                if (uri != null && uri.toString() == lastSelfWriteUri) return
                checkLatestImage()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants: camera/screenshot writes hit child item URIs
            obs,
        )
        observer = obs
    }

    /**
     * The system broadcasts ACTION_CLOSE_SYSTEM_DIALOGS when the user opens home, recents, or the
     * notification shade. Drop the overlay then so it does not linger over another screen.
     */
    private fun registerLeaveReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // ACTION_CLOSE_SYSTEM_DIALOGS also fires at screenshot time and on notifications, which
                // would kill the overlay the instant it appears. Only dismiss when the user actually
                // leaves via recents or home (the "reason" extra).
                val reason = intent?.getStringExtra("reason")
                if (reason == "recentapps" || reason == "homekey") {
                    removeOverlay()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        leaveReceiver = receiver
    }

    /**
     * Reads the most-recently-added image and, when it looks like a screenshot taken in the last
     * few seconds, shows the overlay. Runs on the observer's background looper. The observer can fire
     * before the row is fully written, so a first pass that finds no row or a stale (non-recent)
     * newest image schedules ONE short re-check before giving up.
     */
    private fun checkLatestImage(allowRetry: Boolean = true) {
        val usePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (usePath) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            pathColumn,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // No "LIMIT 1" in the sort string: Android 11+ MediaStore rejects a LIMIT clause in the
        // sortOrder (it throws), so just sort newest-first and read the first row via moveToFirst.
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val hit = runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                val path = cursor.getColumnIndex(pathColumn)
                    .takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                val dateAddedSec = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    .takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
                Triple(id, isScreenshotPath(name, path), dateAddedSec)
            }
        }.getOrNull()

        // No row yet: the write likely has not landed. Re-check once before giving up.
        if (hit == null) {
            if (allowRetry) observerHandler?.postDelayed({ checkLatestImage(allowRetry = false) }, RECHECK_DELAY_MS)
            return
        }

        val (id, looksLikeScreenshot, dateAddedSec) = hit
        val recentlyAdded = (System.currentTimeMillis() / 1000L) - dateAddedSec <= RECENT_WINDOW_SEC
        // Newest row is stale: the screenshot row may not be written yet. Re-check once.
        if (!recentlyAdded) {
            if (allowRetry) observerHandler?.postDelayed({ checkLatestImage(allowRetry = false) }, RECHECK_DELAY_MS)
            return
        }
        if (!looksLikeScreenshot) return

        // Debounce: same id, or another overlay within the burst window, is ignored.
        val now = System.currentTimeMillis()
        if (id == lastHandledId) return
        if (now - lastShownAtMs < DEBOUNCE_MS) return
        lastHandledId = id
        lastShownAtMs = now

        // Decode the compact thumbnail here on the observer's background looper, so the main thread
        // only builds and adds the overlay with a ready bitmap.
        val shotUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val thumb = decodeThumb(shotUri)
        mainHandler.post { showOverlay(shotUri, thumb) }
    }

    /**
     * Treats the newest image as a screenshot when its display name or relative path mentions
     * "screenshot" (case-insensitive), or when a path segment is a "Screenshots" folder.
     */
    private fun isScreenshotPath(name: String, path: String): Boolean {
        val nameLc = name.lowercase()
        val pathLc = path.lowercase()
        // Ignore the overlay's own saved edits ("Screenshot_edit_<ms>.jpg") so persisting an annotated
        // copy for upload or link never re-triggers the overlay on that write.
        if (nameLc.contains("_edit_")) return false
        if (nameLc.contains("screenshot") || pathLc.contains("screenshot")) return true
        return pathLc.split('/', '\\').any { it.trim() == "screenshots" }
    }

    /**
     * Best-effort compact thumbnail. loadThumbnail is Q+ and any failure just yields a bar with no
     * image. Decoded off the main thread (see [checkLatestImage]) so building the overlay never blocks
     * on IO.
     */
    private fun decodeThumb(shotUri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.loadThumbnail(shotUri, Size(dp(160), dp(160)), null) }.getOrNull()
        } else {
            null
        }

    /**
     * Builds and adds the overlay via WindowManager. Only one overlay is ever up at a time. The
     * thumbnail is decoded off the main thread and handed in.
     */
    private fun showOverlay(shotUri: Uri, thumb: Bitmap?) {
        removeOverlay()

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        currentShotUri = shotUri
        thumbBitmap = thumb

        // One FrameLayout root holds both states; only the compact child is visible on entry.
        // clipChildren off so the compact widget card's elevation shadow is not clipped away.
        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val compact = buildCompactView(thumb)
        val expandedCard = buildExpandedView()
        root.addView(
            compact,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        root.addView(
            expandedCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        expandedCard.visibility = View.GONE

        overlayRoot = root
        compactView = compact
        expandedView = expandedCard
        expanded = false
        drawing = false

        val params = compactParams()
        val added = runCatching { wm.addView(root, params) }.isSuccess
        if (!added) {
            clearReferences()
            return
        }
        overlayView = root

        // Show at full alpha immediately. A background process can freeze view-property animations,
        // which would leave an alpha-0 fade-in permanently invisible (the window is added but never
        // appears), so the overlay must not depend on an animation to become visible.
        root.alpha = 1f
        root.translationY = 0f

        mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    /** Compact-state window params: wraps the bar, pinned to the bottom center with a margin. */
    private fun compactParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Pinned bottom-centre, a small margin up from the navigation area, in easy thumb reach.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }

    /**
     * Expanded-state window params: a CONTAINED card centred on screen (screen width minus side
     * margins, height wraps the capped image plus the action row), NOT a full-screen window. Because
     * the window only wraps the card and stays non-focusable, taps outside the card fall through to
     * the app behind, so the expanded state never feels frozen.
     */
    private fun expandedParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // FLAG_DIM_BEHIND dims the apps behind; on API 31+ FLAG_BLUR_BEHIND blurs them (this is the
            // dialog recipe the blur pipeline needs). The scrim view is only a light fallback dim on
            // devices where cross-window blur is off.
            dimAmount = 0.35f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = dp(40)
            }
        }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        val view = overlayView
        // Remove the window view first, then clear + recycle, so no attached view is left holding a
        // recycled bitmap.
        if (view != null) {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            if (wm != null) runCatching { wm.removeView(view) }
        }
        clearReferences()
    }

    private fun clearReferences() {
        drawView?.release()
        // Detach the bitmaps from their views before recycling so a live view never draws a recycled one.
        expandedImage?.setImageDrawable(null)
        compactImage?.setImageDrawable(null)
        fullBitmap?.let { if (!it.isRecycled) it.recycle() }
        fullBitmap = null
        thumbBitmap?.let { if (!it.isRecycled) it.recycle() }
        thumbBitmap = null
        overlayView = null
        overlayRoot = null
        compactView = null
        compactImage = null
        expandedView = null
        expandedImage = null
        drawView = null
        actionContainer = null
        optionsContainer = null
        rgbContainer = null
        expandedClose = null
        expandedTopRight = null
        sizePreview = null
        expanded = false
        drawing = false
        selectedTool = null
        showingRgb = false
        currentShotUri = null
        linking = false
        linkUrl = null
        linkFailed = false
        linkStage = ""
        uploadDone = false
    }

    /**
     * Compact state: a single horizontal rounded pill floating bottom-centre. Left to right: a
     * bordered square thumbnail of the shot (tap opens PREVIEW), an accent Edit button (opens PREVIEW
     * then DRAW), a Share button, and a round close X. The three controls reuse [addIconButton] so
     * they match the expanded corner controls.
     */
    private fun buildCompactView(thumb: Bitmap?): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(pillColor())
                setStroke(dp(1), pillBorder())
            }
            elevation = dp(14).toFloat()
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }

        // Bordered square thumbnail: a frame with a rounded 1px stroke wraps the rounded CENTER_CROP
        // image (clipToOutline rounds the picture to the frame's inner corners). Tapping opens PREVIEW.
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#4A4A50"))
            }
            setPadding(dp(1), dp(1), dp(1), dp(1))
            isClickable = true
            setOnClickListener { expand() }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            thumb?.let { setImageBitmap(it) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#2A2A2E"))
            }
            clipToOutline = true
        }
        compactImage = image
        frame.addView(image, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        pill.addView(
            frame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        addIconButton(pill, eu.akoos.photos.R.drawable.ic_ovl_edit, accent = true) { expand(); startDraw() }
        addIconButton(pill, eu.akoos.photos.R.drawable.ic_ovl_share, accent = false) { shareShot() }
        addIconButton(pill, eu.akoos.photos.R.drawable.ic_ovl_close, accent = false) { removeOverlay() }
        return pill
    }

    /**
     * Expanded card shared by PREVIEW and DRAW: the screenshot big at the top, a draw layer over it
     * (hidden and non-touchable until DRAW), and a swappable action strip below. The strip is filled
     * by [fillPreviewActions] in PREVIEW and by [fillDrawActions] in DRAW; the image and draw layer
     * share a FrameLayout so the strokes land exactly on the picture.
     */
    private fun buildExpandedView(): View {
        // No card: the shot floats CENTRED on the full-screen blurred scrim, and the controls sit in
        // the SCREEN corners with their own pill backgrounds. imageStack consumes touches so tapping
        // the shot does not reach the scrim; the DrawView captures touches only in DRAW, and a
        // finished annotation stays painted in PREVIEW.
        val imageStack = FrameLayout(this).apply { isClickable = true }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val draw = DrawView(this)
        imageStack.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        imageStack.addView(
            draw,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Size preview: a small dot centred over the shot, shown only while the size slider is dragged.
        // Non-interactive so it never eats touches from the draw layer under it.
        val preview = View(this).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        imageStack.addView(preview, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        sizePreview = preview
        expandedImage = image
        drawView = draw

        // Top-LEFT of the screen: the full-close X (own pill background). Hidden while drawing.
        val cornerClose = buildCornerClose()
        expandedClose = cornerClose

        // Top-RIGHT of the screen: a swappable pill row, filled per state (Share and Delete in PREVIEW,
        // Undo and Done in DRAW). Kept visible in both states.
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        expandedTopRight = topRight

        // Bottom-centre: a FIXED-height bar (see the verticalRoot add below) so switching tools never
        // resizes the shot above. An options area (the size slider, plus the pen color button) sits on
        // a fixed sub-height over the tools row; an RGB picker takes the whole bar when open. Both the
        // options area and the picker are empty and hidden in PREVIEW.
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        optionsContainer = options
        // Shared toolbar pill: both the PREVIEW action row and the DRAW tools row live inside this one
        // rounded container so they read as a single bar. Buttons inside are transparent (see
        // [addBarButton]); only the accent/active one keeps a fill.
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(pillColor())
                setStroke(dp(1), pillBorder())
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        actionContainer = tools
        val rgb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Consumes taps so adjusting the picker never falls through to the scrim and collapses DRAW.
            isClickable = true
            visibility = View.GONE
        }
        rgbContainer = rgb
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        bottomBar.addView(
            options,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
        )
        bottomBar.addView(
            tools,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(60)),
        )
        bottomBar.addView(
            rgb,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // Dark scrim: dims everything behind (the window also blurs behind on API 31+). Tapping it
        // outside the shot collapses back to the compact bar.
        val scrim = View(this).apply {
            // Light fallback dim only; the main dim + blur come from the window flags (FLAG_DIM_BEHIND
            // + FLAG_BLUR_BEHIND). Kept transparent-ish so the blur behind stays visible.
            setBackgroundColor(Color.parseColor("#22000000"))
            isClickable = true
            setOnClickListener { collapse() }
        }

        // Vertical layout so the shot sits equidistant from the controls: a fixed top spacer clears the
        // top corner buttons, the image takes the middle with weight (so it centres between the zones),
        // then the bottom bar. The top spacer and the bar's top margin are the same, so the visible gap
        // above and below the shot matches in PREVIEW and DRAW alike, whatever the bar's height.
        val verticalRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        verticalRoot.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)),
        )
        verticalRoot.addView(
            imageStack,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = dp(20); rightMargin = dp(20)
            },
        )
        verticalRoot.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(108),
            ).apply { leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(12); bottomMargin = dp(40) },
        )

        val container = FrameLayout(this)
        container.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            verticalRoot,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            cornerClose,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(40); marginStart = dp(16)
            },
        )
        container.addView(
            topRight,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(40); marginEnd = dp(16) },
        )
        return container
    }

    /**
     * PREVIEW action row: Edit (opens DRAW), the Upload/Link stubs and Copy. Share and Delete live in
     * the top-right, so they are not repeated here. The options row is cleared and hidden in PREVIEW.
     */
    private fun fillPreviewActions() {
        val strip = actionContainer ?: return
        strip.removeAllViews()
        strip.gravity = Gravity.CENTER
        strip.visibility = View.VISIBLE
        // Restore the compact WRAP_CONTENT pill width (the LINK panel grows it to full width).
        strip.layoutParams = strip.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
        addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_edit, accent = true) { startDraw() }
        addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_upload, accent = false) { onUploadTapped() }
        addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_link, accent = false) { onLinkTapped() }
        addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_copy, accent = false) { copyShot() }
        optionsContainer?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }
        rgbContainer?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }
    }

    /** PREVIEW top-right: Share and Delete, each on the current shot (annotated when drawn on). */
    private fun fillPreviewTopRight() {
        val row = expandedTopRight ?: return
        row.removeAllViews()
        addIconButton(row, eu.akoos.photos.R.drawable.ic_ovl_share, accent = false) { shareShot() }
        addIconButton(row, eu.akoos.photos.R.drawable.ic_ovl_delete, accent = false) { deleteShot() }
    }

    /** DRAW top-right: Undo the last stroke, then Done back to PREVIEW. */
    private fun fillDrawTopRight() {
        val row = expandedTopRight ?: return
        row.removeAllViews()
        addIconButton(row, eu.akoos.photos.R.drawable.ic_ovl_undo, accent = false) { drawView?.undo() }
        addIconButton(row, eu.akoos.photos.R.drawable.ic_ovl_check, accent = true) { finishDraw() }
    }

    /**
     * DRAW bottom bar, rebuilt on every state change. With no tool active it shows the three tools
     * (TOOL-SELECT). With a tool active it hides the other two, keeps the active one (accent pill,
     * tap to deselect), and shows a size slider above it (plus a color button for the pen). When the
     * color palette is open it takes over the whole bar. Undo and Done sit in the top-right, not here.
     */
    private fun fillDrawActions() {
        val tools = actionContainer ?: return
        val options = optionsContainer ?: return
        val rgb = rgbContainer ?: return
        tools.removeAllViews()
        options.removeAllViews()
        rgb.removeAllViews()

        if (showingRgb) {
            options.visibility = View.GONE
            tools.visibility = View.GONE
            rgb.visibility = View.VISIBLE
            fillColorPanel(rgb)
            updateInteractive()
            return
        }

        rgb.visibility = View.GONE
        options.visibility = View.VISIBLE
        tools.visibility = View.VISIBLE

        val active = selectedTool
        if (active == null) {
            addBarButton(tools, eu.akoos.photos.R.drawable.ic_ovl_edit, accent = false) { selectTool(Mode.PEN) }
            addBarButtonDrawable(tools, pixelateIconDrawable(), accent = false) { selectTool(Mode.PIXELATE) }
            addBarButton(tools, eu.akoos.photos.R.drawable.ic_ovl_clear, accent = false) { selectTool(Mode.ERASER) }
            updateInteractive()
            return
        }

        val slider = buildSeekBar(
            9,
            levelFor(active) - 1,
            accentColor,
            onStart = { showSizePreview(active) },
            onStop = { hideSizePreview() },
        ) { progress ->
            setLevelFor(active, progress + 1)
            applyWidth(active)
            updateSizePreview(active)
        }
        options.addView(slider, LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (active == Mode.PEN) {
            addColorDot(options, drawColor, sizeDp = 34) { openRgb() }
        }
        when (active) {
            Mode.PEN -> addBarButton(tools, eu.akoos.photos.R.drawable.ic_ovl_edit, accent = true) { selectTool(Mode.PEN) }
            Mode.PIXELATE -> addBarButtonDrawable(tools, pixelateIconDrawable(), accent = true) { selectTool(Mode.PIXELATE) }
            Mode.ERASER -> addBarButton(tools, eu.akoos.photos.R.drawable.ic_ovl_clear, accent = true) { selectTool(Mode.ERASER) }
        }
        updateInteractive()
    }

    /** Selects a tool, or deselects it (back to TOOL-SELECT) when it is already active. */
    private fun selectTool(mode: Mode) {
        if (selectedTool == mode) {
            selectedTool = null
        } else {
            selectedTool = mode
            drawView?.setMode(mode)
            applyWidth(mode)
        }
        showingRgb = false
        fillDrawActions()
    }

    /** Opens the color palette over the whole bar (pen only). */
    private fun openRgb() {
        showingRgb = true
        fillDrawActions()
    }

    /** Closes the color palette back to the pen TOOL-ACTIVE bar. */
    private fun closeRgb() {
        showingRgb = false
        fillDrawActions()
    }

    /**
     * The color palette: a back control plus a horizontal scroll of preset dots. Picking a dot sets the
     * pen color, pushes it to the draw layer and returns to the pen tool bar (which redraws the color
     * button with the new color). Fills the whole fixed bar.
     */
    private fun fillColorPanel(panel: LinearLayout) {
        panel.setPadding(dp(8), dp(6), dp(8), dp(6))
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(pillColor())
            setStroke(dp(1), pillBorder())
        }

        val back = ImageView(this).apply {
            setImageResource(eu.akoos.photos.R.drawable.ic_ovl_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(primaryGlyph())
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = pillBackground(accent = false)
            isClickable = true
            setOnClickListener { closeRgb() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (color in paletteColors) {
            addColorDot(strip, color, sizeDp = 34) {
                drawColor = color
                drawView?.setColor(color)
                closeRgb()
            }
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                strip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** The draw layer captures touches only while drawing with a tool chosen and the picker closed. */
    private fun updateInteractive() {
        drawView?.interactive = drawing && selectedTool != null && !showingRgb
    }

    private fun levelFor(mode: Mode): Int = when (mode) {
        Mode.PEN -> penLevel
        Mode.ERASER -> eraserLevel
        Mode.PIXELATE -> pixelLevel
    }

    private fun setLevelFor(mode: Mode, level: Int) {
        val clamped = level.coerceIn(1, 10)
        when (mode) {
            Mode.PEN -> penLevel = clamped
            Mode.ERASER -> eraserLevel = clamped
            Mode.PIXELATE -> pixelLevel = clamped
        }
    }

    /** Maps a stored 1..10 level to a per-tool stroke width and pushes it to the draw layer. */
    private fun applyWidth(mode: Mode) {
        val width = levelToWidth(mode, levelFor(mode))
        when (mode) {
            Mode.PEN -> drawView?.setPenWidth(width)
            Mode.ERASER -> drawView?.setEraserWidth(width)
            Mode.PIXELATE -> drawView?.setPixelateWidth(width)
        }
    }

    private fun levelToWidth(mode: Mode, level: Int): Float {
        val fraction = (level.coerceIn(1, 10) - 1) / 9f
        val range = when (mode) {
            Mode.PEN -> 1 to 14
            Mode.ERASER -> 8 to 60
            Mode.PIXELATE -> 10 to 70
        }
        val minPx = dp(range.first).toFloat()
        val maxPx = dp(range.second).toFloat()
        return minPx + fraction * (maxPx - minPx)
    }

    /**
     * A minimally styled SeekBar tinted with [tint]. The progress is seeded BEFORE the listener is
     * attached, so seeding never fires [onProgress] (only real drags do). [onStart] and [onStop]
     * bracket a drag, used to show and hide the live size preview.
     */
    private fun buildSeekBar(
        max: Int,
        progress: Int,
        tint: Int,
        onStart: (() -> Unit)? = null,
        onStop: (() -> Unit)? = null,
        onProgress: (Int) -> Unit,
    ): SeekBar {
        val stateTint = ColorStateList.valueOf(tint)
        return SeekBar(this).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
            progressTintList = stateTint
            thumbTintList = stateTint
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    onProgress(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { onStart?.invoke() }
                override fun onStopTrackingTouch(seekBar: SeekBar) { onStop?.invoke() }
            })
        }
    }

    /** Shows the size-preview dot for [mode] and syncs it to the current width. */
    private fun showSizePreview(mode: Mode) {
        val preview = sizePreview ?: return
        preview.visibility = View.VISIBLE
        updateSizePreview(mode)
    }

    /**
     * Resizes and repaints the size-preview dot to the current [mode] width: a filled circle for pen
     * (in the pen color) and pixelate (grey), a hollow ring for the eraser so the shot shows through.
     * A no-op while the dot is hidden.
     */
    private fun updateSizePreview(mode: Mode) {
        val preview = sizePreview ?: return
        if (preview.visibility != View.VISIBLE) return
        // Diameter equals the actual stroke width (a round-cap stroke of width W makes a dot of
        // diameter W), floored only enough to stay visible, so the dot matches what gets drawn.
        val diameter = levelToWidth(mode, levelFor(mode)).toInt().coerceAtLeast(dp(3))
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (mode) {
                Mode.ERASER -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), withAlpha(primaryGlyph(), 0xE6))
                }
                Mode.PIXELATE -> {
                    setColor(Color.parseColor("#B0BDBDBD"))
                    setStroke(dp(1), withAlpha(primaryGlyph(), 0x66))
                }
                Mode.PEN -> {
                    setColor(withAlpha(drawColor, 0xDD))
                    setStroke(dp(1), withAlpha(primaryGlyph(), 0x66))
                }
            }
        }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        lp.width = diameter
        lp.height = diameter
        preview.layoutParams = lp
    }

    /** Hides the size-preview dot. */
    private fun hideSizePreview() {
        sizePreview?.visibility = View.GONE
    }

    /** Returns [color] with its alpha replaced by [alpha] (0..255). */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * COMPACT to PREVIEW: crossfade to the expanded card with drawing OFF, grow the window, load
     * the full bitmap, pause auto-dismiss. This is the first tap on the thumbnail/Edit, so it only
     * enlarges the shot and shows more actions; it does NOT start drawing.
     */
    private fun expand() {
        if (expanded) return
        val compact = compactView ?: return
        val expandedCard = expandedView ?: return
        val root = overlayRoot ?: return
        expanded = true
        drawing = false
        // Auto-dismiss stays paused for the whole expanded life (PREVIEW and DRAW).
        mainHandler.removeCallbacks(autoDismissRunnable)

        // PREVIEW: the draw layer stays present (so a finished annotation keeps showing) but stops
        // capturing touches; the corner close and the top-right actions are shown.
        drawView?.interactive = false
        expandedClose?.visibility = View.VISIBLE
        expandedTopRight?.visibility = View.VISIBLE
        fillPreviewTopRight()
        fillPreviewActions()

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            runCatching { wm.updateViewLayout(root, expandedParams()) }
        }

        expandedCard.alpha = 0f
        expandedCard.visibility = View.VISIBLE
        expandedCard.animate().alpha(1f).setDuration(200).start()
        compact.animate().alpha(0f).setDuration(200).withEndAction {
            if (expanded) compact.visibility = View.GONE
        }.start()

        loadFullBitmap()
    }

    /** PREVIEW to DRAW: swap in the DRAW top-right and open on TOOL-SELECT (no tool chosen yet, so the
     *  draw layer only starts capturing touches once a tool is picked). */
    private fun startDraw() {
        if (!expanded || drawing) return
        drawing = true
        selectedTool = null
        showingRgb = false
        drawView?.setColor(drawColor)
        expandedClose?.visibility = View.GONE
        expandedTopRight?.visibility = View.VISIBLE
        fillDrawTopRight()
        fillDrawActions()
    }

    /** DRAW to PREVIEW: stop capturing touches (strokes stay painted so PREVIEW shows the finished
     *  annotation) and restore the PREVIEW actions. */
    private fun finishDraw() {
        if (!drawing) return
        drawing = false
        selectedTool = null
        showingRgb = false
        drawView?.interactive = false
        expandedClose?.visibility = View.VISIBLE
        expandedTopRight?.visibility = View.VISIBLE
        fillPreviewTopRight()
        fillPreviewActions()
    }

    /**
     * PREVIEW (or DRAW) back to COMPACT: crossfade to the bar, shrink the window, drop out of
     * drawing, and restart a fresh auto-dismiss now that the overlay is compact again.
     */
    private fun collapse() {
        if (!expanded) return
        val compact = compactView ?: return
        val expandedCard = expandedView ?: return
        val root = overlayRoot ?: return
        expanded = false
        drawing = false
        selectedTool = null
        showingRgb = false
        linking = false
        linkUrl = null
        linkFailed = false
        linkStage = ""
        uploadDone = false
        drawView?.interactive = false

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            runCatching { wm.updateViewLayout(root, compactParams()) }
        }

        compact.visibility = View.VISIBLE
        compact.alpha = 0f
        compact.animate().alpha(1f).setDuration(200).start()
        expandedCard.animate().alpha(0f).setDuration(200).withEndAction {
            if (!expanded) expandedCard.visibility = View.GONE
        }.start()

        mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    /**
     * Loads a larger, sharp-but-memory-safe bitmap for the expanded image off the observer thread
     * and posts the result back to the main thread. Best-effort: any failure just leaves the big
     * image blank while the toolbar still works.
     */
    private fun loadFullBitmap() {
        val uri = currentShotUri ?: return
        val bg = observerHandler ?: mainHandler
        bg.post {
            // Decode the ORIGINAL bytes (not a thumbnail) so the preview and any shared annotation are
            // full quality; a very large source is downsampled just enough to stay memory-safe.
            val full: Bitmap? = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (longest / sample > 2600) sample *= 2
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(
                        stream, null, BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }.getOrNull()
            if (full != null) {
                mainHandler.post {
                    fullBitmap = full
                    drawView?.setSource(full)
                    if (expanded) expandedImage?.setImageBitmap(full)
                }
            }
        }
    }

    /** A round color chip filled with [color], used as the pen's color button (it opens the RGB
     *  picker). Sized in dp. */
    private fun addColorDot(
        parent: LinearLayout,
        color: Int,
        sizeDp: Int = 34,
        onClick: () -> Unit,
    ) {
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(2), Color.parseColor("#66FFFFFF"))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(
            dot,
            LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { leftMargin = dp(8) },
        )
    }

    /**
     * A toolbar-pill button used INSIDE the shared [actionContainer] pill: like [addIconButton] but
     * transparent when not accent, so the buttons read as one bar. The accent/active button keeps the
     * accent fill. The CORNER controls (X, Share/Delete, Undo/Done) keep [addIconButton]'s own pill.
     */
    private fun addBarButton(parent: LinearLayout, iconRes: Int, accent: Boolean, onClick: () -> Unit) {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(if (accent) onAccent() else primaryGlyph())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = if (accent) pillBackground(accent = true) else null
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(
            icon,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4); rightMargin = dp(4) },
        )
    }

    /** Drawable-source variant of [addBarButton] for the code-built pixelate glyph. */
    private fun addBarButtonDrawable(
        parent: LinearLayout,
        drawable: android.graphics.drawable.Drawable,
        accent: Boolean,
        onClick: () -> Unit,
    ) {
        val icon = ImageView(this).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(if (accent) onAccent() else primaryGlyph())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = if (accent) pillBackground(accent = true) else null
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(
            icon,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4); rightMargin = dp(4) },
        )
    }

    /** A small block-grid glyph for the pixelate tool; the button's color filter tints it. */
    private fun pixelateIconDrawable(): android.graphics.drawable.Drawable {
        val cells = 3
        val cell = dp(5)
        val gap = dp(2)
        val size = (cells * cell + (cells - 1) * gap).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (row in 0 until cells) {
            for (col in 0 until cells) {
                val x = (col * (cell + gap)).toFloat()
                val y = (row * (cell + gap)).toFloat()
                canvas.drawRect(x, y, x + cell, y + cell, p)
            }
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** A round icon action button: an accent-filled or subtle circle with a white-tinted glyph. */
    private fun addIconButton(parent: LinearLayout, iconRes: Int, accent: Boolean, onClick: () -> Unit) {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // The accent pill uses the in-app dark glyph on violet; the rest are a white glyph on the
            // in-app dark pill (see [pillBackground]).
            setColorFilter(if (accent) onAccent() else primaryGlyph())
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = pillBackground(accent)
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(
            icon,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) },
        )
    }

    // Theme-aware color tokens the overlay routes every non-content surface through. Resolved once by
    // [computeTheme]; the pill fill/border, on-accent glyph and primary glyph each pick light or dark.
    private fun pillColor(): Int = if (isLightTheme) 0xF5EAEAEC.toInt() else 0xEA1C1C1E.toInt()
    private fun pillBorder(): Int = if (isLightTheme) 0xFFB4B4BC.toInt() else 0xFF2C2C2E.toInt()
    private fun onAccent(): Int = if (isLightTheme) 0xFFFFFFFF.toInt() else 0xFF0F0D18.toInt()
    private fun primaryGlyph(): Int = if (isLightTheme) 0xFF131318.toInt() else 0xFFECEBF3.toInt()

    /**
     * Resolves the light/dark flag and accent color once, off the main thread. "light"/"dark" are taken
     * as written; "system" falls back to the device night bits because the app forces its own night
     * mode elsewhere. The accent comes from the saved palette so the overlay matches the app.
     */
    private suspend fun computeTheme() {
        val mode = ThemePrefsBoot.read(this)
        val light = when (mode) {
            "light" -> true
            "dark" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_NO
        }
        val key = runCatching { settingsDataStore.data.first()[SettingsKeys.THEME_PALETTE] }.getOrNull()
        isLightTheme = light
        accentColor = paletteAccent(ThemePalette.fromKey(key), light).toArgb()
    }

    /** The app's pill background: the accent fill, or the opaque in-app pill with a subtle border,
     *  matching the in-app chips/buttons so the overlay looks native. */
    private fun pillBackground(accent: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (accent) {
            setColor(accentColor)
        } else {
            // Opaque in-app pill so the top corner buttons look as solid as the bottom row
            // instead of see-through over the image.
            setColor(pillColor())
            setStroke(dp(1), pillBorder())
        }
    }

    /** The round close sat in the expanded image's top-end corner: a dark circle with a close glyph. */
    private fun buildCornerClose(): View =
        ImageView(this).apply {
            setImageResource(eu.akoos.photos.R.drawable.ic_ovl_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(primaryGlyph())
            setPadding(dp(11), dp(11), dp(11), dp(11))
            // Same in-app dark pill as the other icon buttons, so the corner controls all match.
            background = pillBackground(accent = false)
            isClickable = true
            setOnClickListener { removeOverlay() }
        }

    /** Freehand tool the draw layer paints with. */
    private enum class Mode { PEN, ERASER, PIXELATE }

    /**
     * Annotation layer sat directly over the expanded image. Every segment is composited straight
     * onto an offscreen [layer] bitmap the size of the view, so the eraser can CLEAR just the pixels
     * under the drag (a real eraser, not a wipe-all) and the pixelate tool can paint the blocky mosaic
     * of the shot along the stroke. PREVIEW keeps the layer painted but non-interactive so taps fall
     * through; DRAW sets [interactive] true to capture touches. Only alive while the expanded child is
     * shown, so in the compact state it is gone with the card.
     */
    private inner class DrawView(context: Context) : View(context) {

        // Offscreen annotation buffer the size of the view; segments are drawn straight onto it.
        private var layer: Bitmap? = null
        private var layerCanvas: Canvas? = null

        // The shot shown under the layer: used to (re)build the pixelate mosaic and to map the layer
        // onto the full-resolution export.
        private var source: Bitmap? = null

        // A blocky, view-sized copy of the shot the pixelate stroke paints through a BitmapShader.
        private var mosaic: Bitmap? = null

        // Bounded undo snapshots; each ACTION_DOWN pushes the pre-stroke layer state.
        private val undoStack = ArrayDeque<Bitmap>()

        private var activeColor = 0xFFFF5252.toInt()
        // Current stroke widths, exposed so the options row can mark the active thickness. Declared
        // before the paints so the paint initializers can read them.
        var penWidth = dp(5).toFloat()
            private set
        var eraserWidth = dp(24).toFloat()
            private set
        private var lastX = 0f
        private var lastY = 0f
        private var dirty = false

        var mode = Mode.PEN
            private set

        var interactive = false

        private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = penWidth
            color = activeColor
        }
        private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = eraserWidth
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val pixelatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(28).toFloat()
            // Until the mosaic shader is ready an early stroke should paint nothing rather than black.
            color = Color.TRANSPARENT
        }
        private val exportPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
        }

        fun setColor(color: Int) {
            activeColor = color
            penPaint.color = color
        }

        fun setPenWidth(px: Float) {
            penWidth = px
            penPaint.strokeWidth = px
        }

        fun setEraserWidth(px: Float) {
            eraserWidth = px
            eraserPaint.strokeWidth = px
        }

        fun setPixelateWidth(px: Float) {
            pixelatePaint.strokeWidth = px
        }

        fun setMode(m: Mode) {
            mode = m
        }

        fun hasContent(): Boolean = dirty

        fun setSource(src: Bitmap) {
            source = src
            if (width > 0 && height > 0) buildMosaic()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val old = layer
            val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val freshCanvas = Canvas(fresh)
            // A resize (rotation, or entering DRAW) must not wipe the annotation: carry the old layer
            // into the new bitmap scaled to the new size, then drop the old one.
            val carried = if (old != null && !old.isRecycled) {
                freshCanvas.drawBitmap(old, null, Rect(0, 0, w, h), exportPaint)
                true
            } else {
                false
            }
            layer = fresh
            layerCanvas = freshCanvas
            old?.recycle()
            // The pre-resize undo snapshots no longer match the new size, so drop them.
            clearUndo()
            if (!carried) dirty = false
            buildMosaic()
            invalidate()
        }

        /** The FIT_CENTER rect of the source inside the view (same math the export uses). */
        fun dispRect(): Rect {
            val src = source
            val vw = width
            val vh = height
            if (src == null || vw <= 0 || vh <= 0 || src.width <= 0 || src.height <= 0) {
                return Rect(0, 0, vw.coerceAtLeast(0), vh.coerceAtLeast(0))
            }
            val fit = minOf(vw.toFloat() / src.width, vh.toFloat() / src.height)
            val dw = src.width * fit
            val dh = src.height * fit
            val left = (vw - dw) / 2f
            val top = (vh - dh) / 2f
            return Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        }

        /** Draws the layer's image region scaled to fill [0,0,dstW,dstH] onto the export canvas. */
        fun bakeOnto(canvas: Canvas, dstW: Int, dstH: Int) {
            val l = layer ?: return
            canvas.drawBitmap(l, dispRect(), Rect(0, 0, dstW, dstH), exportPaint)
        }

        // Renders the shot FIT_CENTER into a view-sized buffer, downscales it hard, then upscales with
        // no filtering so it comes back blocky, and points the pixelate paint at the result.
        private fun buildMosaic() {
            val src = source ?: return
            val vw = width
            val vh = height
            if (vw <= 0 || vh <= 0 || src.width <= 0 || src.height <= 0) return
            val fitted = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            Canvas(fitted).drawBitmap(src, null, dispRect(), exportPaint)
            val smallW = (vw / 16).coerceAtLeast(1)
            val smallH = (vh / 16).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(fitted, smallW, smallH, true)
            val blocky = Bitmap.createScaledBitmap(small, vw, vh, false)
            val old = mosaic
            mosaic = blocky
            pixelatePaint.shader = BitmapShader(blocky, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // The shader supplies the colors, but a zero-alpha paint color multiplies them away. Now
            // that a mosaic exists, use an opaque color so the pixelate stroke actually paints.
            pixelatePaint.color = Color.BLACK
            old?.recycle()
            fitted.recycle()
            if (small != blocky) small.recycle()
        }

        private fun paintFor(m: Mode): Paint = when (m) {
            Mode.PEN -> penPaint
            Mode.ERASER -> eraserPaint
            Mode.PIXELATE -> pixelatePaint
        }

        private fun pushUndo() {
            val l = layer ?: return
            undoStack.addLast(l.copy(Bitmap.Config.ARGB_8888, false))
            while (undoStack.size > UNDO_CAP) undoStack.removeFirst().recycle()
        }

        private fun clearUndo() {
            while (undoStack.isNotEmpty()) undoStack.removeLast().recycle()
        }

        fun undo() {
            val lc = layerCanvas ?: return
            val snap = undoStack.removeLastOrNull()
            lc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (snap != null) {
                lc.drawBitmap(snap, 0f, 0f, null)
                snap.recycle()
            }
            dirty = undoStack.isNotEmpty()
            invalidate()
        }

        fun clearAll() {
            val lc = layerCanvas ?: return
            lc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            clearUndo()
            dirty = false
            invalidate()
        }

        fun release() {
            layer?.recycle()
            layer = null
            layerCanvas = null
            mosaic?.recycle()
            mosaic = null
            pixelatePaint.shader = null
            clearUndo()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!interactive) return false
            val lc = layerCanvas ?: return false
            // Pixelate paints through the mosaic shader; with no mosaic yet the stroke would be nothing,
            // so consume the touch but skip it rather than push an empty undo step.
            if (mode == Mode.PIXELATE && mosaic == null) return true
            val paint = paintFor(mode)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pushUndo()
                    lastX = event.x
                    lastY = event.y
                    lc.drawPoint(event.x, event.y, paint)
                    dirty = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lc.drawLine(lastX, lastY, event.x, event.y, paint)
                    lastX = event.x
                    lastY = event.y
                    dirty = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lc.drawLine(lastX, lastY, event.x, event.y, paint)
                    dirty = true
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    /**
     * Deletes the screenshot from MediaStore and dismisses the overlay. Succeeds silently when the
     * app holds the media-management grant; otherwise the delete just fails and the overlay stays up.
     */
    private fun deleteShot() {
        val uri = currentShotUri ?: return
        val deleted = runCatching { contentResolver.delete(uri, null, null) }.getOrDefault(0)
        if (deleted > 0) {
            toast(getString(eu.akoos.photos.R.string.screenshot_overlay_deleted))
            removeOverlay()
        } else {
            toast(getString(eu.akoos.photos.R.string.screenshot_overlay_delete_failed))
        }
    }

    /**
     * When the shot was annotated, bakes the annotation layer onto a full-resolution copy and returns
     * a shareable content Uri from cacheDir/fullres via the share FileProvider; with an empty layer it
     * just returns the original shot Uri. So Copy and Share always act on WHAT THE USER SEES. Switches
     * to Dispatchers.IO for the bitmap copy + JPEG encode + file write since callers run on the
     * main-dispatched service scope.
     */
    private suspend fun exportAnnotatedUri(): Uri? = withContext(Dispatchers.IO) {
        val src = fullBitmap
        val dv = drawView
        if (src == null || dv == null || !dv.hasContent()) return@withContext currentShotUri
        runCatching {
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            dv.bakeOnto(canvas, out.width, out.height)
            val dir = java.io.File(cacheDir, "fullres").apply { mkdirs() }
            val file = java.io.File(dir, "annotated_${out.width}x${out.height}.jpg")
            java.io.FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            androidx.core.content.FileProvider.getUriForFile(this@ScreenshotOverlayService, "$packageName.share.fileprovider", file)
        }.getOrElse { currentShotUri }
    }

    /**
     * Bakes the annotation (when present) onto a full-resolution copy and inserts it into MediaStore as
     * a fresh image, returning its content Uri; with no annotation it returns the original shot Uri
     * unchanged so the upload backs up exactly what the user sees. Switches to Dispatchers.IO for the
     * bitmap copy + JPEG encode + MediaStore insert, since callers run on the main-dispatched service
     * scope.
     */
    private suspend fun persistShotForUpload(): Uri? = withContext(Dispatchers.IO) {
        val dv = drawView
        val src = fullBitmap
        if (dv == null || src == null || !dv.hasContent()) return@withContext currentShotUri
        runCatching {
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            dv.bakeOnto(Canvas(out), out.width, out.height)
            val now = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_edit_$now.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, eu.akoos.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            // Remember this write so the observer can skip re-querying for the overlay's own saved edit.
            lastSelfWriteUri = uri.toString()
            contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: error("openOutputStream returned null")
            out.recycle()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }

    /** Upload the shot (annotated when drawn on) through the real backup pipeline, showing the progress
     *  in the panel and an "Uploaded to Drive" confirmation in place (no toast-and-dismiss). */
    private fun onUploadTapped() {
        if (linking) return
        linking = true
        linkUrl = null
        linkFailed = false
        uploadDone = false
        linkStage = getString(eu.akoos.photos.R.string.screenshot_overlay_uploading)
        expandedTopRight?.let { it.removeAllViews(); it.visibility = View.GONE }
        expandedClose?.visibility = View.VISIBLE
        fillLinkPanel()
        serviceScope.launch {
            runCatching { runUpload() }.onFailure { e ->
                if (e is CancellationException) throw e
                mainHandler.post { showLinkError() }
            }
        }
    }

    private suspend fun runUpload() {
        val uid = accountManager.getPrimaryUserId().first()
        if (uid == null) { mainHandler.post { showLinkError() }; return }
        val uri = persistShotForUpload()
        if (uri == null) { mainHandler.post { showLinkError() }; return }
        forceUpload.forceUpload(uid, listOf(uri.toString()))
        // Wait until the pipeline actually finishes the upload (a cloud id appears) so the panel shows
        // real progress, not just a fire-and-forget queue.
        val cloudId = withTimeoutOrNull(60_000L) {
            var st = syncStateRepo.getByUri(uri.toString())
            while (st?.cloudFileId == null) {
                delay(500)
                st = syncStateRepo.getByUri(uri.toString())
            }
            st?.cloudFileId
        }
        if (cloudId == null) { mainHandler.post { showLinkError() }; return }
        mainHandler.post { showUploadDone() }
    }

    /** Upload finished: show an "Uploaded to Drive" confirmation in the panel; the corner X closes it. */
    private fun showUploadDone() {
        if (!linking) return
        uploadDone = true
        fillLinkPanel()
    }

    /**
     * Switches the expanded card into the LINK state (the panel stays up, no toast-and-dismiss) and
     * runs the real upload + link mint, routing every outcome to [fillLinkPanel]. Hides the PREVIEW
     * actions and the top-right Share/Delete; the corner X stays so the panel can be closed.
     */
    private fun onLinkTapped() {
        if (linking) return
        linking = true
        linkUrl = null
        linkFailed = false
        uploadDone = false
        linkStage = getString(eu.akoos.photos.R.string.screenshot_overlay_uploading)
        expandedTopRight?.let { it.removeAllViews(); it.visibility = View.GONE }
        expandedClose?.visibility = View.VISIBLE
        fillLinkPanel()
        serviceScope.launch {
            runCatching { runLink() }.onFailure { e ->
                if (e is CancellationException) throw e
                mainHandler.post { showLinkError() }
            }
        }
    }

    private suspend fun runLink() {
        val uid = accountManager.getPrimaryUserId().first()
        if (uid == null) { mainHandler.post { showLinkError() }; return }
        val uri = persistShotForUpload()
        if (uri == null) { mainHandler.post { showLinkError() }; return }
        forceUpload.forceUpload(uid, listOf(uri.toString()))
        // Poll the sync row until the pipeline assigns a cloud id (or give up), then mint the link.
        val cloudId = withTimeoutOrNull(40_000L) {
            var st = syncStateRepo.getByUri(uri.toString())
            while (st?.cloudFileId == null) {
                delay(500)
                st = syncStateRepo.getByUri(uri.toString())
            }
            st?.cloudFileId
        }
        if (cloudId == null) { mainHandler.post { showLinkError() }; return }
        mainHandler.post { linkStage = getString(eu.akoos.photos.R.string.screenshot_overlay_creating_link); fillLinkPanel() }
        val url = driveRepo.createPhotoShareLink(uid, cloudId)
        mainHandler.post { showLinkResult(url) }
    }

    /** Success: keep the overlay OPEN, auto-copy the link, and show it in the panel with Copy + Share
     *  so the result stays visible in place (no auto-close). */
    private fun showLinkResult(url: String) {
        if (!linking) return
        linkUrl = url
        linkFailed = false
        copyLinkToClipboard(url)
        fillLinkPanel()
    }

    /** Failure or timeout: keep the panel up with an error line; the corner X closes it. */
    private fun showLinkError() {
        if (!linking) return
        linkUrl = null
        linkFailed = true
        fillLinkPanel()
    }

    /**
     * LINK bottom bar, rebuilt for the three sub-states off [linking]/[linkUrl]/[linkFailed]:
     * in-progress ("Creating link..."), result (the link on one ellipsized line plus Copy and Share
     * pills), and error ("Could not create link"). Everything sits inside the shared toolbar pill,
     * grown to full width, so the fixed bar height never changes.
     */
    private fun fillLinkPanel() {
        if (!linking) return
        val strip = actionContainer ?: return
        strip.removeAllViews()
        strip.gravity = Gravity.CENTER
        strip.visibility = View.VISIBLE
        strip.layoutParams = strip.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        optionsContainer?.let { it.removeAllViews(); it.visibility = View.GONE }
        rgbContainer?.let { it.removeAllViews(); it.visibility = View.GONE }

        val url = linkUrl
        if (url != null) {
            val label = linkLabel(url).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            strip.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_copy, accent = false) {
                copyLinkToClipboard(url)
                toast(getString(eu.akoos.photos.R.string.screenshot_overlay_link_copied))
            }
            addBarButton(strip, eu.akoos.photos.R.drawable.ic_ovl_share, accent = false) { shareLink(url) }
        } else {
            val text = when {
                uploadDone -> getString(eu.akoos.photos.R.string.screenshot_overlay_uploaded)
                linkFailed -> getString(eu.akoos.photos.R.string.screenshot_overlay_link_failed)
                else -> linkStage.ifEmpty { getString(eu.akoos.photos.R.string.screenshot_overlay_working) }
            }
            val label = linkLabel(text).apply { gravity = Gravity.CENTER }
            strip.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** A status/URL label for the LINK panel, tinted like the other overlay glyphs. */
    private fun linkLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(primaryGlyph())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(10), 0, dp(10), 0)
    }

    /**
     * Shares the finished link as plain text through the system chooser, then dismisses the overlay
     * (it would otherwise sit over the share sheet), the same way [shareShot] does for the image.
     */
    private fun shareLink(url: String) {
        val launched = runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            val chooser = Intent.createChooser(send, getString(eu.akoos.photos.R.string.screenshot_overlay_share_link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        }.isSuccess
        if (launched) removeOverlay()
    }

    private fun copyLinkToClipboard(url: String) {
        runCatching {
            copySensitiveText(this, "Photo link", url)
        }
    }

    /** Copies the shot (annotated when drawn on) to the clipboard as a content Uri. The annotated
     *  export runs off the main thread; the clipboard write then lands back on the service scope. */
    private fun copyShot() {
        serviceScope.launch {
            val uri = exportAnnotatedUri() ?: return@launch
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            val ok = runCatching {
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Screenshot", uri))
            }.isSuccess
            if (ok) toast(getString(eu.akoos.photos.R.string.screenshot_overlay_copied))
        }
    }

    /**
     * Opens the system share sheet for the screenshot (annotated when drawn on). The annotated export
     * runs off the main thread; the share intent then launches back on the service scope.
     * FLAG_ACTIVITY_NEW_TASK is required to start an Activity from a Service.
     */
    private fun shareShot() {
        serviceScope.launch {
            val uri = exportAnnotatedUri() ?: return@launch
            val launched = runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // ClipData carries the read grant through the chooser to the target app; without it a
                    // FileProvider Uri often opens as a blank image in the receiver.
                    clipData = ClipData.newUri(contentResolver, "Screenshot", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, getString(eu.akoos.photos.R.string.screenshot_overlay_share_image)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(chooser)
            }.isSuccess
            // Our overlay would sit on top of the system share sheet, so dismiss it once the sheet opens.
            if (launched) removeOverlay()
        }
    }

    private fun toast(text: String) {
        // Posted so it is safe to call from the background launches, not just the main thread.
        mainHandler.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(eu.akoos.photos.R.drawable.ic_notification)
        .setContentTitle(getString(eu.akoos.photos.R.string.settings_screenshot_overlay))
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val TAG = "screenshot_overlay"
        const val CHANNEL_ID = "screenshot_overlay"
        const val NOTIFICATION_ID = 4245

        // A screenshot write fires several onChange events; ignore repeats inside this window.
        private const val DEBOUNCE_MS = 3_000L

        // Only treat the newest image as a fresh screenshot when added within this many seconds.
        private const val RECENT_WINDOW_SEC = 15L

        // The observer can fire before the screenshot row is fully written; re-check once after this.
        private const val RECHECK_DELAY_MS = 400L

        // The overlay clears itself after this delay even if the X is never tapped.
        private const val AUTO_DISMISS_MS = 6_000L

        // Most recent draw actions kept for Undo; older snapshots are evicted so memory stays bounded.
        private const val UNDO_CAP = 8

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "start requested without overlay permission")
                return
            }
            val intent = Intent(context, ScreenshotOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenshotOverlayService::class.java)
            runCatching { context.stopService(intent) }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(eu.akoos.photos.R.string.settings_screenshot_overlay),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
