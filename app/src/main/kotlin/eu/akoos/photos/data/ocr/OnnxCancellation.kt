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

/*
 * Derived from mobile_ocr, which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException

/**
 * Aborts inference that is already inside the native runtime.
 *
 * A model run is one blocking call, so a cancelled coroutine cannot interrupt it and a viewer that
 * has moved to the next photo would otherwise wait out a detection nobody is going to look at. Each
 * run is registered here with its own run options, and [cancel] terminates every registered run,
 * which is the only handle the runtime offers on work in progress.
 */
class OnnxCancellation {

    private val lock = Any()
    private val activeRuns = mutableSetOf<OrtSession.RunOptions>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            activeRuns.forEach { runCatching { it.setTerminate(true) } }
        }
    }

    /** Throws if this signal has already been cancelled, for the gaps between runs. */
    fun ensureActive() {
        if (cancelled) throw CancellationException(MESSAGE)
    }

    /** Runs [inputs] through [session], registered so an in-flight run can be terminated. */
    fun run(session: OrtSession, inputs: Map<String, OnnxTensorLike>): OrtSession.Result {
        ensureActive()
        val options = OrtSession.RunOptions()
        synchronized(lock) {
            if (cancelled) {
                options.close()
                throw CancellationException(MESSAGE)
            }
            activeRuns.add(options)
        }
        try {
            val result = session.run(inputs, options)
            // A terminated run can still return a partial result rather than throwing.
            if (cancelled) {
                result.close()
                throw CancellationException(MESSAGE)
            }
            return result
        } catch (error: OrtException) {
            if (cancelled) throw CancellationException(MESSAGE).also { it.initCause(error) }
            throw error
        } finally {
            synchronized(lock) {
                activeRuns.remove(options)
                options.close()
            }
        }
    }

    private companion object {
        const val MESSAGE = "text detection was cancelled"
    }
}
