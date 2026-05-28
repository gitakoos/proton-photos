package eu.akoos.photos.util

/**
 * Strips HTML and trims runaway whitespace so a server-side error page can't dump
 * paragraphs of `<html><body>…` into a toast or snackbar. Drive's CDN occasionally
 * returns 5xx HTML pages instead of JSON envelopes when a node is unhealthy, and
 * the bare `e.message` propagated through the upload pipeline used to land in the
 * editor's "Save failed" toast as raw markup. Now any consumer (PhotoEditor,
 * VideoEditor, PhotoViewer) can route untrusted exception messages through this.
 *
 * Behaviour:
 *  - null / blank → "unknown error"
 *  - drops everything between angle brackets (tags, comments, scripts)
 *  - collapses whitespace to single spaces
 *  - caps length at 200 chars + ellipsis so multi-paragraph bodies don't push UI
 *
 * Output is plain text and safe to embed in user-facing strings.
 */
fun sanitizeErrorMessage(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown error"
    val stripped = raw.replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (stripped.isBlank()) return "server error"
    return if (stripped.length > 200) stripped.substring(0, 200) + "…" else stripped
}
