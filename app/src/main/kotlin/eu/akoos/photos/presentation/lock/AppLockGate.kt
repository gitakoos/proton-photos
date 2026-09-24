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

package eu.akoos.photos.presentation.lock

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import eu.akoos.photos.presentation.theme.Bg0
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What a guarded screen shows. */
internal enum class LockGateView {
    /** The lock settings are not read yet, so whether the lock is on is unknown: a cover, no prompt. */
    Cover,

    /** The lock is due: the unlock screen, which asks for authentication. */
    Lock,

    /** The lock is off, or open. */
    Content,
}

/**
 * The lock presentation of one guarded screen. [locked] holds from a due lock until this screen's own
 * unlock, [pending] until the lock settings are first known, and [opened] records that the content has
 * shown on this screen at least once.
 */
internal data class LockGateFlags(
    val locked: Boolean = false,
    val pending: Boolean = true,
    val opened: Boolean = false,
) {
    val view: LockGateView
        get() = when {
            locked -> LockGateView.Lock
            pending -> LockGateView.Cover
            else -> LockGateView.Content
        }

    /**
     * A settings read, or a start with the settings known, where [unlocked] is the session's answer. A
     * due lock shows. An open session never lifts a lock this screen already shows: only its unlock does.
     */
    fun settle(unlocked: Boolean): LockGateFlags = next(locked = locked || !unlocked, pending = false)

    /** A successful authentication on this screen. */
    fun unlock(): LockGateFlags = next(locked = false, pending = pending)

    /**
     * Whether the guarded content is composed. Content kept under the lock ([keepUnderLock]) always is, so
     * its state survives the lock and the cover. Other content waits until the gate has opened once, so
     * nothing it reads is touched before an unlock, and then stays composed under a later lock.
     */
    fun composesContent(keepUnderLock: Boolean): Boolean = keepUnderLock || opened

    private fun next(locked: Boolean, pending: Boolean): LockGateFlags {
        val flags = LockGateFlags(locked = locked, pending = pending, opened = opened)
        return if (flags.view == LockGateView.Content) flags.copy(opened = true) else flags
    }
}

/**
 * The app lock as the content under an [AppLockGate] sees it, through [LocalAppLock]. A window the content
 * adds once the lock shows, and any activity it starts, opens above the lock. So whatever the content opens
 * on its own rather than in answer to a tap waits for the lock: a dialog or a sheet while [isLocked], a
 * system prompt or another activity through [runWhenOpen].
 */
@Stable
interface AppLockStatus {
    /** True while the lock or its cover shows. */
    val isLocked: Boolean

    /**
     * True while an activity started now could open above the lock: while [isLocked], and while the host is
     * out of the foreground with the lock on, since a lock due on the return is decided only as it comes back.
     */
    val holdsLaunches: Boolean
}

/** What the content sees outside an [AppLockGate]: nothing is held back. */
private object NoAppLock : AppLockStatus {
    override val isLocked: Boolean get() = false
    override val holdsLaunches: Boolean get() = false
}

/** The app lock over the content; see [AppLockStatus]. */
val LocalAppLock = staticCompositionLocalOf<AppLockStatus> { NoAppLock }

/**
 * Runs [open] once [AppLockStatus.holdsLaunches] turns false, and not at all when the caller is cancelled
 * first. On the main thread, where a composition's effects run, the last check and [open] share one turn,
 * so a lock the host decides in the meantime still holds it.
 */
suspend fun AppLockStatus.runWhenOpen(open: () -> Unit) {
    while (true) {
        snapshotFlow { holdsLaunches }.first { !it }
        if (!holdsLaunches) {
            open()
            return
        }
    }
}

/**
 * [AppLockStatus.holdsLaunches] for a screen showing [view], with its host [resumed] or not and the lock
 * [lockEnabled], null until its setting is first read. Only a lock known to be off lets a paused host launch.
 */
internal fun lockHoldsLaunches(view: LockGateView, resumed: Boolean, lockEnabled: Boolean?): Boolean =
    view != LockGateView.Content || (!resumed && lockEnabled != false)

/**
 * Whether a host secures its window over a pause: below Android 13 ([sdkInt]), where nothing else keeps the
 * screen out of the Recents thumbnail and the starting window, while the lock is on or not read yet.
 */
internal fun securesWindowWhilePaused(sdkInt: Int, lockEnabled: Boolean?): Boolean =
    sdkInt < Build.VERSION_CODES.TIRAMISU && lockEnabled != false

/**
 * One guarded screen's side of the app-wide [AppLockSession]. The host activity calls [followSettings]
 * from onCreate and [onStart] / [onResume] / [onPause] / [onStop] from its own callbacks, and hands it to
 * [AppLockGate], which reports a successful authentication through [onUnlocked] and passes the lock's
 * state on to the content as [LocalAppLock].
 */
class AppLockGateState(private val session: AppLockSession) : AppLockStatus {

    private var flags by mutableStateOf(LockGateFlags())

    /** Whether the host is resumed, from [onResume] and [onPause]. */
    private var resumed by mutableStateOf(false)

    /** The lock's enabled setting as last read, null until the first read. */
    private var lockEnabled by mutableStateOf<Boolean?>(null)

    /** FLAG_SECURE held over a pause below Android 13; see [onPause]. */
    private var pauseSecureHold: SecureFlagHold? = null

    internal val view: LockGateView get() = flags.view

    /** True while the lock or its cover shows; the content under them is inert. */
    val isInert: Boolean get() = flags.view != LockGateView.Content

    override val isLocked: Boolean get() = isInert

    override val holdsLaunches: Boolean get() = lockHoldsLaunches(flags.view, resumed, lockEnabled)

    internal fun composesContent(keepUnderLock: Boolean): Boolean = flags.composesContent(keepUnderLock)

    /**
     * Follows the lock settings while [activity] lives. The first read settles a cold start or a
     * process-death restore: with no unlock in this process the session is locked, so the lock shows, and
     * the content stays covered until then. The session only emits real changes, and switching the lock on
     * drops its unlock, so turning it on locks here too. Returns from the background are decided in
     * [onStart].
     */
    fun followSettings(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            session.settings.filterNotNull().collect { settings ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Keeps the screen out of the Recents thumbnail while the lock is on. FLAG_SECURE stays
                    // scoped to the lock and vault screens, so screenshots remain possible. Older releases
                    // secure the window over a pause instead ([onPause]).
                    activity.setRecentsScreenshotEnabled(!settings.enabled)
                }
                lockEnabled = settings.enabled
                flags = flags.settle(session.isUnlocked(SystemClock.elapsedRealtime()))
            }
        }
    }

    /**
     * For the host's onStart. Decided here, synchronously, so the first frame after a return already
     * carries the lock instead of showing the content for a frame. The session runs on the monotonic clock,
     * so setting the system time back cannot hold the app open. Before the first settings read the content
     * stays covered and [followSettings] decides.
     */
    fun onStart() {
        if (session.settings.value == null) return
        flags = flags.settle(session.onForeground(SystemClock.elapsedRealtime()))
    }

    /**
     * For the host's onResume. The pause's FLAG_SECURE goes back the way [onPause] found it, so the lock
     * screen and the vault, which secure the window for themselves, keep it.
     */
    fun onResume() {
        pauseSecureHold?.release()
        pauseSecureHold = null
        resumed = true
    }

    /**
     * For the host's onPause. Below Android 13 the system snapshots the window for the Recents thumbnail
     * and the starting window as the host leaves, so while the lock is on the window is secured over the
     * pause ([securesWindowWhilePaused]). A configuration change is no leave, and keeps the window as it is.
     */
    fun onPause(activity: Activity) {
        resumed = false
        if (pauseSecureHold == null &&
            !activity.isChangingConfigurations &&
            securesWindowWhilePaused(Build.VERSION.SDK_INT, lockEnabled)
        ) {
            pauseSecureHold = SecureFlagHold.acquire(activity.window)
        }
    }

    /** For the host's onStop. */
    fun onStop() {
        session.markBackgrounded(SystemClock.elapsedRealtime())
    }

    internal fun onUnlocked() {
        session.markUnlocked(SystemClock.elapsedRealtime())
        flags = flags.unlock()
    }
}

/**
 * Guards [content] with the app lock.
 *
 * Dialogs, sheets and menus are windows of their own above the host's, so a lock drawn inside the host
 * would leave one opened earlier visible and usable on top of it. The lock and its cover therefore show in
 * a full-screen window of their own, added when they appear, which sits above every window opened before.
 * Under it the content is also covered in the host's window, from the frame the lock is decided in, and
 * inert: out of the accessibility tree and unreachable by focus and keys.
 *
 * A window the content opens later still lands above the lock, so the content reads the lock's state from
 * [LocalAppLock] and holds back what it would open on its own until the unlock.
 *
 * Back leaves through [onLeave] while the lock or the cover shows. [keepContentUnderLock] keeps the content
 * composed under them, which the main screen needs for its navigation state; without it the content is
 * composed only once the gate has opened, so nothing it reads is touched before an unlock.
 */
@Composable
fun AppLockGate(
    state: AppLockGateState,
    onLeave: () -> Unit,
    keepContentUnderLock: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = state.view
    val inert = view != LockGateView.Content
    val focusManager = LocalFocusManager.current
    val unlockFocus = remember { FocusRequester() }
    LaunchedEffect(inert) {
        // Drops focus (and with it the keyboard) from a field behind the lock.
        if (inert) focusManager.clearFocus(force = true)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // The wrapper stays in place so only its modifier changes; moving the content to another spot in
        // the tree would drop its state.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (inert) Modifier.inertUnderLock(unlockFocus) else Modifier),
        ) {
            if (state.composesContent(keepContentUnderLock)) {
                CompositionLocalProvider(LocalAppLock provides state, content = content)
            }
        }
        if (inert) AppLockCover()
    }
    // After the content, so a window it opens in the same frame is still added below the lock's.
    when (view) {
        LockGateView.Cover -> LockWindow(onLeave) { AppLockCover() }
        LockGateView.Lock -> LockWindow(onLeave) {
            AppLockScreen(onUnlocked = { state.onUnlocked() }, unlockFocusRequester = unlockFocus)
        }
        LockGateView.Content -> Unit
    }
}

/**
 * The lock's own full-screen window. FLAG_SECURE keeps it out of screenshots and the Recents thumbnail,
 * and Back leaves, as it does in the host while locked.
 */
@Composable
private fun LockWindow(onLeave: () -> Unit, content: @Composable () -> Unit) {
    // Strings resolve through the host's context, as the content's do, which carries the in-app language.
    val hostContext = LocalContext.current
    val leave by rememberUpdatedState(onLeave)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val backOwner = view.findViewTreeOnBackPressedDispatcherOwner()
        DisposableEffect(backOwner) {
            // Registered after the window's own callback, which ignores Back here, so this one runs.
            val callback = backOwner?.onBackPressedDispatcher?.addCallback { leave() }
            onDispose { callback?.remove() }
        }
        val lightBackground = Bg0.luminance() > 0.5f
        DisposableEffect(view, lightBackground) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                // Opaque, so nothing needs dimming behind it; the bar icons contrast with its background.
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBackground
                    isAppearanceLightNavigationBars = lightBackground
                }
            }
            onDispose { }
        }
        CompositionLocalProvider(LocalContext provides hostContext, content = content)
    }
}

/**
 * Cuts the content under the lock off from assistive tech and the keyboard: no semantics for TalkBack and
 * no key event for whatever inside still holds focus. Focus search never enters it. The unlock button lives
 * in the lock's window, so a search that tries hands focus to it there ([lockFocus]) once it is composed.
 */
private fun Modifier.inertUnderLock(lockFocus: FocusRequester): Modifier = this
    .clearAndSetSemantics { }
    .onPreviewKeyEvent { true }
    .focusProperties {
        onEnter = {
            // Throws while the button is not composed yet, which leaves only the refusal below.
            runCatching { lockFocus.requestFocus(FocusDirection.Enter) }
            cancelFocusChange()
        }
    }
    .focusGroup()
