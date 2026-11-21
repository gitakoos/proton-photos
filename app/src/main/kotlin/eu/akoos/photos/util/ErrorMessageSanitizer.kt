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

package eu.akoos.photos.util

/**
 * Strips HTML, redacts PII / opaque IDs, and trims runaway whitespace so a
 * server-side error page can't dump paragraphs of `<html><body>…` (or worse, a
 * user's email + a Drive linkId) into a toast, snackbar, or [ErrorPopup]. Drive's
 * CDN occasionally returns 5xx HTML pages instead of JSON envelopes when a node is
 * unhealthy, and the bare `e.message` propagated through the upload pipeline used
 * to land in the editor's "Save failed" toast as raw markup with the filename
 * + linkId baked in. Now any consumer (PhotoEditor, VideoEditor, PhotoViewer,
 * ErrorPopup) can route untrusted exception messages through this.
 *
 * Behaviour:
 *  - null / blank → "unknown error"
 *  - drops everything between angle brackets (tags, comments, scripts)
 *  - redacts filenames, emails, opaque base64-ish IDs (link IDs, share IDs),
 *    filesystem paths, and full URLs to anonymous placeholders. Done BEFORE the
 *    length cap so the cap counts the redacted form (no surprise truncation that
 *    cuts a placeholder in half).
 *  - collapses whitespace to single spaces
 *  - caps length at 200 chars + ellipsis so multi-paragraph bodies don't push UI
 *
 * Output is plain text and safe to embed in user-facing strings.
 */
fun sanitizeErrorMessage(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown error"
    var s = raw.replace(Regex("<[^>]+>"), " ")

    // ── PII / opaque-token redaction. Order matters: ───────────────────────────
    // URLs first (they contain paths + slashes), then paths, then emails, then
    // filenames, then opaque IDs. Doing IDs LAST is critical — without that order
    // the 20+ char base64 regex would also eat the body of an email or URL we
    // wanted to recognise structurally.
    s = s.replace(Regex("https?://\\S+"), "<url>")
    // A path is read to its end, not to its first space: the folders of a library are named by people,
    // and a family name in one is exactly what must not outlive the redaction.
    s = s.replace(Regex("(?:/storage|/sdcard|/mnt|/data)/[^\\n:()'\"]*"), "<path>")
    // The same folders reached by their media-relative path, which names no storage root.
    s = s.replace(Regex("\\b(?:Pictures|DCIM|Movies|Download|Documents|Android)/[^\\n:()'\"]*"), "<path>")
    s = s.replace(Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"), "<email>")
    // Anything quoted is a name somebody chose (an album, a person, a folder).
    s = s.replace(Regex("'[^'\\n]*'|\"[^\"\\n]*\""), "<text>")
    // A name after a key that introduces one, unquoted: the rest of the message is that name as far as
    // anything here can tell, so it all goes. "album Summer at Anna's" or "name: holiday 2024".
    s = s.replace(
        Regex(
            "(?i)\\b(named|called|album|person|folder)\\b(?:\\s*[:=])?\\s+" +
                "(?!<|(?:not|does|is|was|has|had|already|cannot|could|missing|exists|full|of|for|to|in)\\b)\\S.*",
        ),
        "$1 <text>",
    )
    s = s.replace(Regex("(?i)\\b(file ?name|file|name|title|path)\\s*[:=]\\s*(?!<)\\S.*"), "$1: <text>")
    // A file name in any script, with up to three words before it that a name with spaces carries, in
    // any case; the plain words of an error message around a name stop it, so they stay readable.
    s = s.replace(
        Regex(
            "(?<![\\p{L}\\p{N}_()\\-.,])(?:(?!(?i:$FILE_NAME_STOP_WORDS)\\b)[\\p{L}\\p{N}_()\\-.,]+ ){0,3}[\\p{L}\\p{N}_()\\-.,]+" +
                "\\.(?i:$MEDIA_EXTENSIONS)\\b",
        ),
        "<file>",
    )
    // Opaque base64-ish tokens (Drive linkIds, shareIds). 20-char minimum on the
    // base64 charset is the safety margin against false positives on ordinary
    // words — English's longest common words top out around 15 chars, and the
    // mixed-case+digits requirement is satisfied effectively only by encoded IDs.
    s = s.replace(Regex("[A-Za-z0-9_-]{20,}"), "<id>")

    val stripped = s.replace(Regex("\\s+"), " ").trim()
    if (stripped.isBlank()) return "server error"
    return if (stripped.length > 200) stripped.substring(0, 200) + "…" else stripped
}

/**
 * Convenience alias for callers that just want the redacted form without thinking
 * about the historical "sanitize" name. Behaviourally identical to
 * [sanitizeErrorMessage] — kept as a separate symbol so call sites can read as
 * `redact(e.message)` where that intent is clearer than "sanitize".
 */
fun redact(raw: String?): String = sanitizeErrorMessage(raw)

/** Every photo, video and archive extension the app reads, writes or names, for the file name rule. */
private const val MEDIA_EXTENSIONS =
    "jpg|jpeg|jpe|jfif|png|webp|heic|heif|heics|hif|avif|gif|bmp|tif|tiff|dng|raw|arw|cr2|cr3|nef|nrw|orf|" +
        "rw2|raf|srw|pef|jxl|svg|mp4|m4v|mov|qt|avi|3gp|3g2|mkv|webm|mpg|mpeg|wmv|ts|mts|m2ts|zip"

/** The plain words of an error message, which never belong to the file name that follows them. */
private const val FILE_NAME_STOP_WORDS =
    "a|an|the|to|of|for|in|on|at|from|with|by|and|or|as|is|was|not|no|cannot|could|failed|error|" +
        "file|photo|video|image|named|called|while|when|open|read|write|save|load|copy|move|delete|create|" +
        "rename|encrypt|decrypt|encode|decode|upload|download|uploading|downloading|process|scan|stamp|find"
