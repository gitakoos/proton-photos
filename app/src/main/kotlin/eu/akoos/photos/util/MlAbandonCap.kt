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

import java.util.concurrent.atomic.AtomicInteger

/** How many sessions of one model a process may abandon to the inference watchdog before its background
 *  walk stops opening new ones. */
const val ML_ABANDON_CAP = 2

/**
 * Counts the sessions of one model the inference watchdog has abandoned in this process. An abandoned
 * session stays open while its wedged native call still runs on it and is closed only once that call
 * returns, so a call that never returns keeps its memory until the process ends; each further one stacks
 * another model's worth. Once [cap] are abandoned the background
 * walk stops opening sessions for the rest of the process, and the next process starts a fresh count, so
 * the usual resume on the next start picks the walk up again. Held by a process-wide scheduler, so a
 * sign-out does not reset it: the leaked sessions outlive the account. Pure so it is unit-tested.
 */
class MlAbandonCap(private val cap: Int = ML_ABANDON_CAP) {
    private val abandoned = AtomicInteger(0)

    /** Counts one more abandoned session and returns the new total. */
    fun recordAbandon(): Int = abandoned.incrementAndGet()

    /** How many sessions have been abandoned in this process. */
    fun count(): Int = abandoned.get()

    /** Whether the walk must open no more sessions in this process. */
    fun reached(): Boolean = abandoned.get() >= cap
}
