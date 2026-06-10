/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "eu.akoos.photos.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark refuses to run on an emulator or a debuggable app by default
        // because the timings won't match a real release build. For baseline-profile
        // generation we don't care about absolute numbers — we just need a trace of
        // which methods got called — so we suppress the safety checks the library
        // would otherwise fail on.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,DEBUGGABLE,UNLOCKED"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The Macrobenchmark instrumentation attaches to a specific variant of the target
    // app. AGP's Baseline Profile plugin auto-generates a non-minified release-like
    // variant of :app on our behalf — the producer here just needs to point at the
    // module.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // Run the generator against whatever device is connected via adb (handheld or
    // running emulator). When no device is connected the task fails fast — that's
    // intentional, the alternative would be a silent no-op that ships an empty
    // profile.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
