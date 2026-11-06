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
package eu.akoos.photos.data.face

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts the user's edits of people and faces (a name, a merge, a "not this person", a move, a removal, a
 * manual add, a cover), so a full regroup that read the faces before an edit landed never writes its grouping
 * over it. A regroup takes a [mark] before it reads anything and writes only while [unchangedSince] holds for
 * it, asked inside its write transaction. Every edit runs inside [edit], which moves the count as it starts
 * and again as it ends and holds the regroup off while it runs, so an edit that commits before the regroup's
 * write is always seen, and one that starts after the check waits for the regroup's transaction to commit.
 */
@Singleton
class FaceEditCounter @Inject constructor() {
    @PublishedApi internal val generation = AtomicLong(0L)
    @PublishedApi internal val inFlight = AtomicInteger(0)

    /** The count a regroup takes before it reads the faces, names and marks it will rewrite. */
    fun mark(): Long = generation.get()

    /** Whether no edit has started, ended or is running since [mark] was taken. */
    fun unchangedSince(mark: Long): Boolean = inFlight.get() == 0 && generation.get() == mark

    /** Runs one edit of people or faces, counted as described above. */
    inline fun <T> edit(block: () -> T): T {
        inFlight.incrementAndGet()
        generation.incrementAndGet()
        try {
            return block()
        } finally {
            generation.incrementAndGet()
            inFlight.decrementAndGet()
        }
    }
}
