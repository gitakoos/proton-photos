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

/** Heap one stored face costs a full regroup at its peak: its unpacked 512-float vector (2 KiB and the
 *  array header) plus its share of the per-face bookkeeping (the id and photo strings, the list slots and
 *  the map entries the rewrite keeps), rounded up. */
internal const val REGROUP_HEAP_BYTES_PER_FACE = 3L * 1024L

/** Heap a full regroup leaves free beyond its faces: the page of rows being read, the clusterer's
 *  per-cluster arrays, and room for the rest of the app to keep allocating while it runs. */
internal const val REGROUP_HEAP_RESERVE_BYTES = 48L * 1024L * 1024L

/** The heap a full regroup of [faceCount] stored faces needs free before it starts. */
internal fun regroupHeapNeedBytes(faceCount: Int): Long =
    faceCount.coerceAtLeast(0).toLong() * REGROUP_HEAP_BYTES_PER_FACE + REGROUP_HEAP_RESERVE_BYTES

/** Whether a regroup needing [needBytes] fits the heap still free: the runtime's ceiling [maxHeapBytes]
 *  less the [usedHeapBytes] already taken. Pure so it is unit-tested without Android. */
internal fun regroupFitsHeap(needBytes: Long, maxHeapBytes: Long, usedHeapBytes: Long): Boolean =
    needBytes <= maxHeapBytes - usedHeapBytes
