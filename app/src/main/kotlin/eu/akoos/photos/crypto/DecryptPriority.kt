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

package eu.akoos.photos.crypto

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Relative urgency of a decrypt at the process-global crypto gate.
 *
 * [FOREGROUND] is interactive work a user is waiting on (a visible thumbnail, a full-size open);
 * [BACKGROUND] is indexing/backfill that can yield its place. Absence of the context element
 * resolves to [FOREGROUND], so any caller that does not opt in is treated as interactive.
 */
enum class DecryptPriority { FOREGROUND, BACKGROUND }

/**
 * Carries a [DecryptPriority] down a coroutine subtree. A caller marks background work with
 * `withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) { ... }`; leaf decrypts read it
 * back through [currentDecryptPriority].
 */
class DecryptPriorityContext(val priority: DecryptPriority) :
    AbstractCoroutineContextElement(DecryptPriorityContext) {
    companion object Key : CoroutineContext.Key<DecryptPriorityContext>
}

/** The caller's [DecryptPriority] from the coroutine context, defaulting to [DecryptPriority.FOREGROUND]. */
suspend fun currentDecryptPriority(): DecryptPriority =
    coroutineContext[DecryptPriorityContext]?.priority ?: DecryptPriority.FOREGROUND
