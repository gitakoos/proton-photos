import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

// Load the gitignored keystore.properties so the password never lands in the
// repo. Falling back to null lets a fresh clone still build the DEBUG variant
// without the file — only the release variant requires real credentials.
val keystorePropsFile: java.io.File = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else null

// APK base name — picked up by the AGP packager so the produced files are
// named photosforproton-<ABI>-<buildType>.apk instead of the default app-*.apk.
// Keeps the GitHub release assets self-describing for end users who download
// them directly without context.
base {
    archivesName.set("photosforproton")
}

android {
    namespace = "eu.akoos.photos"
    compileSdk = 35

    defaultConfig {
        // applicationId is intentionally OUTSIDE the me.proton.* namespace — this is an
        // unofficial third-party client, not built or endorsed by Proton AG. Using a
        // me.proton.* applicationId would imply association the project doesn't have.
        // The Kotlin/Java package namespace is eu.akoos.photos to match the applicationId.
        applicationId = "eu.akoos.photos"
        minSdk = 26
        targetSdk = 35
        // versionCode bumped per release tag — keep monotonically increasing.
        // versionName mirrors the GitHub release tag (e.g. v2.0.0 → "2.0.0") so the About
        // screen and the published APK report the same version the user downloaded.
        versionCode = 231
        versionName = "2.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Extract libgojni.so (Proton's Go-based OpenPGP) onto the filesystem at install
    // time instead of keeping it inside the APK as a memory-mapped page. Android 16's
    // new userfaultfd-based CMC garbage collector races against Go's runtime signal
    // handlers when the Go runtime is reading code pages straight out of the APK,
    // producing SIGABRT on the DefaultDispatch thread (verified on multiple Android 16
    // builds, both OEM and custom ROM — Android 15 devices don't crash).
    // Extracted .so files have their own pages managed by the linker, which sidesteps
    // the userfaultfd contention. Removing it re-introduces the SIGABRT within
    // ~45-90 s of first sign-in on Android 16 userfaultfd kernels — the cryptoLock
    // wrap only serialises this app's own calls, not Go's internal scheduler.
    // Dropping it to shrink 16 KB-page installs stays unjustified: that page-size
    // concern was never confirmed by a real report.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            keystoreProps?.let { p ->
                storeFile = rootProject.file(p["RELEASE_STORE_FILE"] as String)
                storePassword = p["RELEASE_STORE_PASSWORD"] as String
                keyAlias = p["RELEASE_KEY_ALIAS"] as String
                keyPassword = p["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            // Size optimisation (tree-shake unused classes + resources). Class/method
            // names stay readable because -dontobfuscate is set in proguard-rules.pro —
            // this project is open-source so renaming buys nothing.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Apply the release-signing config only when keystore.properties is present.
            // CI / fresh clones without the keystore still build (unsigned) so contributors
            // can iterate.
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Per-ABI APK splits — each device downloads only the .so libs for its own arch
    // instead of every architecture's bundle. ~30 MB savings on the 100 MB monolithic
    // debug APK. universalApk = true keeps a single "fallback" APK around for users
    // who can't (or won't) pick the right arch.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Apply Hilt @ApplicationContext / @Inject etc. to BOTH the value parameter and the
        // backing field. Without this Kotlin 2.x emits a deprecation warning per inject site
        // (KT-73255). Behaviour matches what Hilt's KSP processor expects.
        freeCompilerArgs += "-Xannotation-default-target=param-property"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // AIDL stub for ICryptoService — the cross-process binder that lets the
        // :crypto process expose its thumbnail-decrypt methods to the main process.
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Export Room schema JSONs into the source tree so they can be committed
    // and used by MigrationTestHelper / kept under code review.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Proton Core
    implementation(libs.proton.auth)
    implementation(libs.proton.auth.presentation)
    implementation(libs.proton.account)
    implementation(libs.proton.account.manager)
    implementation(libs.proton.account.manager.presentation.compose)
    implementation(libs.proton.crypto)
    implementation(libs.proton.crypto.android)
    implementation(libs.proton.key)
    implementation(libs.proton.key.domain)
    implementation(libs.proton.network)
    implementation(libs.proton.network.data)
    implementation(libs.proton.user)
    implementation(libs.proton.user.domain)
    implementation(libs.proton.user.data)
    implementation(libs.proton.human.verification)
    implementation(libs.proton.human.verification.presentation)
    implementation(libs.proton.plan)
    implementation(libs.proton.presentation)
    implementation(libs.proton.presentation.compose)
    implementation(libs.proton.feature.flag)
    implementation(libs.proton.account.data)
    implementation(libs.proton.auth.data)
    implementation(libs.proton.key.data)
    implementation(libs.proton.challenge.data)
    implementation(libs.proton.notification.data)
    implementation(libs.proton.payment.presentation)
    implementation(libs.proton.util.android.datetime)
    implementation(libs.proton.util.kotlin)
    implementation(libs.proton.data.room)
    implementation(libs.proton.user.settings.data)
    implementation(libs.proton.observability.data)
    implementation(libs.proton.telemetry.data)
    implementation(libs.proton.event.manager.data)
    implementation(libs.proton.account.recovery.data)
    implementation(libs.proton.country.data)
    implementation(libs.proton.push.data)

    // Override the transitively-pulled gopenpgp JNI artifact to the latest stable
    // build on Maven Central. ProtonCore 36.6.0 pins `me.proton.crypto:android-golib`
    // at the 2.9.0-2 build, whose gopenpgp Go runtime defaults to SHA-256 for the
    // self-cert and subkey-binding signatures emitted by `generateNewPrivateKey`.
    // Drive web's recipient-side `openpgp.js` rejects SHA-256 binding signatures,
    // which makes every NodeKey we generate fail subkey verification for share
    // members ("Failed to decrypt node ..."). `2.10.0-2` defaults to SHA-512
    // (matching the hashAlgo=10 on Drive-web-generated album NodeKeys), so this
    // strict version constraint is load-bearing for cross-client album sharing.
    implementation("me.proton.crypto:android-golib") {
        version { strictly("2.10.0-2") }
    }

    // Proton Core dagger/hilt bindings
    implementation(libs.proton.network.dagger)
    implementation(libs.proton.auth.dagger)
    implementation(libs.proton.account.manager.dagger)
    implementation(libs.proton.user.settings.dagger)
    implementation(libs.proton.crypto.validator.dagger)
    implementation(libs.proton.feature.flag.dagger)
    implementation(libs.proton.human.verification.dagger)
    implementation(libs.proton.key.dagger)
    implementation(libs.proton.plan.dagger)
    implementation(libs.proton.observability.dagger)
    implementation(libs.proton.telemetry.dagger)
    implementation(libs.proton.event.manager.dagger)
    implementation(libs.proton.account.recovery.dagger)
    implementation(libs.proton.challenge.dagger)
    implementation(libs.proton.pass.validator.dagger)
    implementation(libs.proton.device.migration.dagger)
    implementation(libs.proton.notification.dagger)
    implementation(libs.proton.util.android.dagger)

    // Hilt — KSP
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler.androidx)

    // Room — KSP required for Room 2.7+ with Kotlin 2.x
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Image loading + video frame extraction
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    // Video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // AppCompat (for language/locale switching)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // EXIF reading/writing
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Biometric authentication (hidden album)
    implementation("androidx.biometric:biometric:1.1.0")

    // Glance — home screen widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext)

    // Loads the AOT-compilable Baseline Profile shipped inside the APK on every
    // process start. Without this dependency the generated baseline-prof.txt sits
    // in assets unused; ProfileInstaller is what hands it off to ART.
    implementation(libs.profileinstaller)

    // Wires :baseline-profile as the producer of the profile this APK consumes.
    // The AGP Baseline Profile plugin reads from this configuration during release
    // builds and bakes the captured trace into the packaged APK.
    "baselineProfile"(project(":baseline-profile"))
}

baselineProfile {
    // Don't fire the generator on every release build — the profile is regenerated
    // explicitly via `./gradlew :app:generateBaselineProfile` when we actually
    // want a refreshed trace. Routine release builds reuse the last-committed
    // baseline-prof.txt instead of standing up an emulator on every CI run.
    automaticGenerationDuringBuild = false
}
