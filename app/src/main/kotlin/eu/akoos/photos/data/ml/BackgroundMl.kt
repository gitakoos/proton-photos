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


package eu.akoos.photos.data.ml

import android.os.Process
import java.util.concurrent.ThreadFactory

/**
 * Intra-op thread cap for the ONNX sessions of the background scans (faces, image embeddings). Left
 * at the Runtime default a session spawns one worker per core and competes with the UI thread and the
 * render thread; two keeps a scan moving while the foreground stays smooth.
 */
internal const val BACKGROUND_ML_INTRA_OP_THREADS = 2

/** A daemon thread factory whose threads run at background priority, set inside the thread because the
 *  priority is per thread. */
internal fun backgroundMlThreadFactory(name: String): ThreadFactory = ThreadFactory { r ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, name).apply { isDaemon = true }
}
