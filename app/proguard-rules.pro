# ── Open-source project — don't obfuscate ─────────────────────────────────
# The source is public on GitHub; renaming classes to a.b.c saves nothing
# the reader can't already see. Keeping readable names also makes crash
# logs from beta testers vastly more useful.
-dontobfuscate

# Keep all annotations — many ProtonCore / Hilt / Compose features
# rely on annotation reflection at runtime.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ── kotlinx.serialization ────────────────────────────────────────────────
# @Serializable classes reflect into their companions. Without these, every
# Drive DTO round-trip throws SerializationException at runtime.
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# All Drive DTOs are in this package — keep them whole.
-keep class eu.akoos.photos.data.api.dto.** { *; }

# ── Retrofit ──────────────────────────────────────────────────────────────
-keepattributes Signature,Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call

# ── OkHttp / Okio ─────────────────────────────────────────────────────────
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# ── ProtonCore — auth, crypto, network ────────────────────────────────────
# ProtonCore uses heavy reflection for DI module wiring and crypto key
# resolution. Keep all public surface; the obfuscator would otherwise rename
# methods that ProtonCore's own JNI / kotlin-reflect calls expect by name.
-keep class me.proton.core.** { *; }
-keep interface me.proton.core.** { *; }
-dontwarn me.proton.core.**

# ── GoOpenPGP / gopenpgp (the underlying crypto JNI) ──────────────────────
-keep class com.proton.gopenpgp.** { *; }
-dontwarn com.proton.gopenpgp.**

# ── Hilt — generated code expects original class names ────────────────────
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ViewWithFragmentComponentManagerHolder
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep @dagger.Module class *
-keep @dagger.hilt.InstallIn class *
-keepclassmembers class * {
    @dagger.* <methods>;
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# ── Room — entities + DAOs use generated subclasses ──────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers @androidx.room.TypeConverter class * { *; }

# ── Compose runtime ──────────────────────────────────────────────────────
# Compose itself ships with the rules it needs in -consumer-proguard-rules.
-dontwarn androidx.compose.**

# ── Coil image loading ───────────────────────────────────────────────────
-dontwarn coil.**

# ── App-side: Workers (HiltWorker reflection) + Application class ────────
-keep class eu.akoos.photos.App { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep @androidx.hilt.work.HiltWorker class * { *; }

# ── Kotlin metadata + coroutines ─────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.flow.**

# ── Strip ALL android.util.Log calls in release ──────────────────────────
# Privacy hardening: the upload + download + reconcile pipelines emit plaintext
# filenames, filesystem paths, Drive linkIds, user signer emails, and short
# prefixes of encrypted key material at Log.d/i/w/e level. Stripping every
# Log call in release means an attacker with `adb logcat` access on an unlocked
# device sees nothing about the user's photos, account, or Drive layout.
# Debug builds keep all logs for development.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
