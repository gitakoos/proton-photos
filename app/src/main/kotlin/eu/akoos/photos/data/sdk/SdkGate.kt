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

package eu.akoos.photos.data.sdk

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the Proton Drive SDK can run in this process. The SDK declares minSdk 29 (the app keeps 26
 * through a manifest override) and ships native libraries for arm64-v8a, armeabi-v7a and x86_64 only,
 * so every path into the SDK checks [isCapable] first. The ABI that counts is the one this process runs
 * as: the 32-bit x86 APK installed on an x86_64 device runs as x86 and carries no SDK library.
 */
@Singleton
class SdkGate @Inject constructor() {
    val isCapable: Boolean = isSdkCapable(
        Build.VERSION.SDK_INT,
        (if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
            ?.toList().orEmpty(),
    )
}

/**
 * Android 10+ with a primary ABI the SDK ships a native library for, which is every ABI but 32-bit
 * x86. An empty ABI list counts as unsupported. Pure, so it is unit-tested directly.
 */
internal fun isSdkCapable(sdkInt: Int, supportedAbis: List<String>): Boolean {
    val primaryAbi = supportedAbis.firstOrNull() ?: return false
    return sdkInt >= Build.VERSION_CODES.Q && !primaryAbi.equals("x86", ignoreCase = true)
}
