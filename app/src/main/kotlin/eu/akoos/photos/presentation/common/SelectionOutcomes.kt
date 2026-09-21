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

package eu.akoos.photos.presentation.common

import android.content.Context
import androidx.annotation.StringRes
import eu.akoos.photos.R

/**
 * A string resource with its format arguments. Letting a decision name the message without holding a
 * Context is what keeps the choice below plain Kotlin, so every surface picks the same words.
 */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(res, *args.toTypedArray())
}

/** How a share-to-other-apps batch ended, counted over the items the selection asked for. */
sealed class ShareOutcome {
    /** Every selected item resolved to a shareable uri; the chooser opening is the confirmation. */
    data object AllShared : ShareOutcome()
    data class SomeShared(val shared: Int, val failed: Int) : ShareOutcome()
    /** Nothing resolved, so no chooser opens and the selection clears with nothing to show for it. */
    data object NoneShared : ShareOutcome()
}

fun shareOutcome(shared: Int, failed: Int): ShareOutcome = when {
    failed <= 0 -> ShareOutcome.AllShared
    shared <= 0 -> ShareOutcome.NoneShared
    else -> ShareOutcome.SomeShared(shared, failed)
}

/** What the user is owed after a share batch, or null when the chooser already speaks for it. */
fun ShareOutcome.message(): UiMessage? = when (this) {
    is ShareOutcome.AllShared -> null
    is ShareOutcome.NoneShared -> UiMessage(R.string.share_selection_failed)
    is ShareOutcome.SomeShared -> UiMessage(R.string.share_selection_partial, listOf(shared, failed))
}

/**
 * How a batch metadata strip ended. [StripOutcome.Done.skipped] counts the photos the strip never
 * applied to (cloud-only items with no local bytes, files the OS would not let the app write), while
 * [StripOutcome.Failed.failed] counts the writes that were attempted and did not work. The two read
 * differently to the user, so they stay apart.
 */
sealed class StripOutcome {
    data class Done(val stripped: Int, val skipped: Int) : StripOutcome()
    data class Failed(val stripped: Int, val skipped: Int, val failed: Int) : StripOutcome()
}

fun stripOutcome(stripped: Int, skipped: Int, failed: Int): StripOutcome =
    if (failed > 0) StripOutcome.Failed(stripped, skipped, failed)
    else StripOutcome.Done(stripped, skipped)

/**
 * How a batch favourite ended, counted over the photos whose heart the press had to change.
 *
 * A device-only photo settles in the device-side set and cannot fail; a backed-up one needs Drive to
 * take PhotoTag 0, and offline, signed out or a server that refused all end here.
 */
sealed class FavoriteOutcome {
    /** Every heart landed. The tiles and the button already show it, so nothing is said. */
    data object AllChanged : FavoriteOutcome()
    data class SomeChanged(val changed: Int, val failed: Int) : FavoriteOutcome()
    /** Not one write landed, so the hearts are exactly where they were before the press. */
    data object NoneChanged : FavoriteOutcome()
}

fun favoriteOutcome(changed: Int, failed: Int): FavoriteOutcome = when {
    failed <= 0 -> FavoriteOutcome.AllChanged
    changed <= 0 -> FavoriteOutcome.NoneChanged
    else -> FavoriteOutcome.SomeChanged(changed, failed)
}

/**
 * What the user is owed after a batch favourite, or null when the hearts speak for themselves.
 *
 * Favouriting is cheap, visible on every tile it touched and undone by pressing again, so a message
 * on a clean run would be noise on an action a user runs repeatedly. A write Drive refused is the
 * opposite: the heart goes back where it was and nothing on screen would say why.
 */
fun FavoriteOutcome.message(): UiMessage? = when (this) {
    is FavoriteOutcome.AllChanged -> null
    is FavoriteOutcome.NoneChanged -> UiMessage(R.string.favorite_selection_failed)
    is FavoriteOutcome.SomeChanged ->
        UiMessage(R.string.favorite_selection_partial, listOf(changed, failed))
}

fun StripOutcome.Failed.message(): UiMessage = when {
    stripped <= 0 -> UiMessage(R.string.gallery_strip_all_failed, listOf(failed))
    skipped > 0 -> UiMessage(
        R.string.gallery_stripped_with_skipped_and_failed,
        listOf(stripped, skipped, failed),
    )
    else -> UiMessage(R.string.gallery_stripped_with_failed, listOf(stripped, failed))
}
