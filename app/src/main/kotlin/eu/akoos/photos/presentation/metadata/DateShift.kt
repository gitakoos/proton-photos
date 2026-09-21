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

package eu.akoos.photos.presentation.metadata

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.TimestampSanity

/**
 * The decisions behind the metadata editor's bulk date shift: which of the bound photos a shift can
 * move, the span their dates cover, how far the user may move them, and where each one lands.
 *
 * One absolute date written over a whole selection puts every photo on the same instant. A camera
 * whose clock was set wrong needs the opposite: each frame moved by the SAME delta, so the minutes
 * and hours between the shots, which are the record of the day, survive the correction. A shift is
 * that operation, expressed as the instant the user wants the OLDEST photo to carry.
 *
 * A photo whose own date is not real ([TimestampSanity.isReal], the floor every photo list already
 * groups by) has nothing to add a delta to, so a shift leaves it alone rather than moving it off a
 * sentinel; the absolute date still reaches it.
 *
 * Free of Android types, and the current instant is a parameter rather than a clock read, so every
 * rule here is verifiable on the JVM.
 */
object DateShift {

    /** One photo a shift moves: the device file the write lands on, and the date the delta is added
     *  to. [captureMs] is the effective capture time every other list sorts that photo by. */
    data class Target(val uri: String, val captureMs: Long)

    /** The oldest and newest date across the shifted photos. [widthMs] is the spread a shift carries
     *  along unchanged, and is 0 for a single photo. */
    data class Span(val earliestMs: Long, val latestMs: Long) {
        val widthMs: Long get() = latestMs - earliestMs
    }

    /**
     * The photos of [items] a shift can move, each with its own current date.
     *
     * [dateTargetUris] is the set of device files a capture-date write durably holds on, so this is
     * always a filter ON TOP of it and never reaches a file the plain date write would not: a
     * cloud-only photo has no device file here at all, and one whose date is not real is dropped,
     * since a delta added to a sentinel produces a date describing nothing. One entry per file.
     */
    fun targets(items: List<GalleryItem>, dateTargetUris: Set<String>): List<Target> {
        if (dateTargetUris.isEmpty()) return emptyList()
        val seen = HashSet<String>(dateTargetUris.size)
        return items.mapNotNull { item ->
            val uri = deviceUri(item)
            if (uri == null || uri !in dateTargetUris) return@mapNotNull null
            val captureMs = item.captureTimeMs
            if (!TimestampSanity.isReal(captureMs) || !seen.add(uri)) return@mapNotNull null
            Target(uri, captureMs)
        }
    }

    /** The span [targets] cover, or null when there is nothing to shift. */
    fun span(targets: List<Target>): Span? =
        if (targets.isEmpty()) null
        else Span(targets.minOf { it.captureMs }, targets.maxOf { it.captureMs })

    /**
     * The latest instant the user may put the OLDEST photo on, given the current instant [nowMs].
     * Past it the newest photo of [span] would land in the future, which no capture date describes,
     * so the whole selection is what the ceiling is measured for rather than the photo being picked.
     */
    fun maxEarliestMs(span: Span, nowMs: Long): Long = nowMs - span.widthMs

    /** True when putting the oldest photo on [earliestMs] keeps the newest one at or before [nowMs].
     *  The boundary itself is allowed: it puts the newest photo exactly on the current instant. */
    fun allowsEarliest(span: Span, earliestMs: Long, nowMs: Long): Boolean =
        earliestMs <= maxEarliestMs(span, nowMs)

    /** The delta every photo moves by when the oldest one is put on [earliestMs]. Negative moves the
     *  selection back in time, positive forward, 0 leaves it where it is. */
    fun deltaFor(span: Span, earliestMs: Long): Long = earliestMs - span.earliestMs

    /** [span] moved by [deltaMs], which is the range the shifted photos end up covering. The width is
     *  untouched, so this is the preview of a shift that keeps the spacing. */
    fun shifted(span: Span, deltaMs: Long): Span =
        Span(span.earliestMs + deltaMs, span.latestMs + deltaMs)

    /**
     * [targets] after a shift of [deltaMs] landed on [savedUris]. Each saved photo moves by the same
     * delta from its OWN date, which is what keeps the spacing between them; the rest are returned
     * unchanged, so a batch the OS or a file refused leaves those photos measured from where they
     * still are.
     */
    fun shifted(targets: List<Target>, deltaMs: Long, savedUris: Set<String>): List<Target> =
        if (deltaMs == 0L || savedUris.isEmpty()) {
            targets
        } else {
            targets.map { if (it.uri in savedUris) it.copy(captureMs = it.captureMs + deltaMs) else it }
        }

    /** The device file behind [item], or null for a photo that lives only in Drive. */
    private fun deviceUri(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> null
    }
}
