import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

// Load the gitignored keystore.properties so the password never lands in the
// repo. Falling back to null lets a fresh clone still build the DEBUG variant
// without the file — only the release variant requires real credentials.
val keystorePropsFile: java.io.File = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else null

// APK base name — picked up by the AGP packager so the produced files are
// named protonphotos-<ABI>-<buildType>.apk instead of the default app-*.apk.
// Keeps the GitHub release assets self-describing for end users who download
// them directly without context.
base {
    archivesName.set("protonphotos")
}

android {
    namespace = "eu.akoos.photos"
    compileSdk = 35

    defaultConfig {
        // applicationId is intentionally OUTSIDE the me.proton.* namespace — this is an
        // unofficial third-party client, not built or endorsed by Proton AG. Using a
        // me.proton.* applicationId would imply association the project doesn't have.
        // The Kotlin/Java package namespace below stays at me.proton.photos to avoid a
        // full source-tree rename; that's only visible to people decompiling the APK.
        applicationId = "eu.akoos.photos"
        minSdk = 26
        targetSdk = 35
        // versionCode bumped per release tag — keep monotonically increasing.
        // versionName mirrors the GitHub release tag (e.g. v0.9.1 → "0.9.1") so the About
        // screen and the published APK report the same version the user downloaded.
        versionCode = 132
        versionName = "1.3.2-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Extract libgojni.so (Proton's Go-based OpenPGP) onto the filesystem at install time
    // instead of keeping it inside the APK as a memory-mapped page. Android 16's new
    // userfaultfd-based CMC garbage collector races against Go's signal handlers when
    // the Go runtime is reading code pages straight out of the APK, producing SIGABRT
    // on the DefaultDispatch thread (verified on Samsung S22 / Pixel 9 with Android 16
    // BP2A — S23 on Android 15 doesn't crash). Extracted .so files have their own pages
    // managed by the linker, which sidesteps the userfaultfd contention.
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
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Image loading + video frame extraction
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.okhttp.logging)
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
}
