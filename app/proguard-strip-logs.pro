# ── Strip ALL android.util.Log calls in release ──────────────────────────
# Privacy hardening: the upload + download + reconcile pipelines emit plaintext
# filenames, filesystem paths, Drive linkIds, user signer emails, and short
# prefixes of encrypted key material at Log.d/i/w/e level. Stripping every
# Log call in release means an attacker with `adb logcat` access on an unlocked
# device sees nothing about the user's photos, account, or Drive layout.
# Debug builds keep all logs for development.
# Kept in its own file so a local measurement build can leave it out
# (`-PpfpKeepLogs`); a published release never passes that property.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
