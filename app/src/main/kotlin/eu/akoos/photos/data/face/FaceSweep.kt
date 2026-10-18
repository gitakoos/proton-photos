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

import eu.akoos.photos.domain.model.FaceBoxNorm

/**
 * What a "find more photos of this person" sweep reports as it re-scans the photos the walk found no
 * face on, at the sensitive detector setting, keeping only faces that match the person.
 */
sealed interface FaceSweepEvent {

    /** How far the sweep has got: [done] of [total] photos looked at. */
    data class Progress(val done: Int, val total: Int) : FaceSweepEvent

    /**
     * A face that matched the person, carried with everything needed to persist it later if the user
     * adds it: its photo, its index on that photo, its 0..1 [box], and the raw detection outputs
     * ([landmarks], [embedding], [score], [blur]) so no re-detection is needed on add. Not persisted by
     * the sweep itself, so a candidate the user does not pick never touches the index.
     */
    class Match(
        val photoKey: String,
        val index: Int,
        val box: FaceBoxNorm,
        val landmarks: String,
        val embedding: ByteArray,
        val score: Float,
        val blur: Float?,
    ) : FaceSweepEvent

    /** The sweep ended for [reason] with [done] of [total] photos looked at, short of the end. */
    data class Stopped(val reason: SweepStop, val done: Int, val total: Int) : FaceSweepEvent
}

/** How a "find more photos" sweep ended, with the fixed label the copied diagnostics print. */
enum class SweepStop(val label: String) {
    COMPLETED("completed"),

    /** The process abandoned its cap of stalled model pairs, so no more photos could be looked at. */
    MODEL_STALLED("model-stalled"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}

/**
 * How a sweep whose workers have all returned ended: [done] of [total] photos looked at, and whether the
 * process has abandoned its cap of stalled model pairs ([abandonCapReached]), which stops the workers short
 * of the end. Pure so it is unit-tested without Android.
 */
internal fun sweepStopReason(done: Int, total: Int, abandonCapReached: Boolean): SweepStop =
    if (done < total && abandonCapReached) SweepStop.MODEL_STALLED else SweepStop.COMPLETED
