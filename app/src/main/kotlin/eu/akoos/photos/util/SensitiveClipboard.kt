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

package eu.akoos.photos.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies text that should not be shown back on screen.
 *
 * A share link carries its own decryption material, so the preview the keyboard shows on a copy
 * would put the full secret on screen, where a bystander or a screen recording picks it up.
 * Flagging the clip as sensitive suppresses that preview.
 *
 * This is a display guard, not an access guard: the clipboard stays readable to other apps
 * exactly as before, so it is not a reason to relax anything else about link handling.
 *
 * The Compose clipboard cannot carry the flag (it takes only text), which is why link copying
 * goes through the platform clipboard here.
 */
fun copySensitiveText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    // The constant only exists from API 33, but the key it holds is honoured further back, so
    // older readers get the same flag through its literal name rather than no flag at all.
    val sensitiveKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
        else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
    clipboard.setPrimaryClip(clip)
}

/**
 * Re-flags whatever is currently on the clipboard as sensitive.
 *
 * Some copies are made by machinery this app does not own: the photo text reader lets the platform's
 * selection write the picked text to the clipboard itself, so [copySensitiveText] cannot be used.
 * Calling this right after that copy adds the same display guard, leaving the copied text untouched,
 * so a phone number, ID or password lifted off a photo is not shown back in the clipboard preview.
 */
fun markPrimaryClipSensitive(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = clipboard.primaryClip ?: return
    val sensitiveKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
        else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = (clip.description.extras ?: PersistableBundle()).apply {
        putBoolean(sensitiveKey, true)
    }
    clipboard.setPrimaryClip(clip)
}
