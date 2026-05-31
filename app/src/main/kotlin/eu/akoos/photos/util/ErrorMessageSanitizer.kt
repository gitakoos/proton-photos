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
    s = s.replace(Regex("/storage/[^\\s]+"), "<path>")
    s = s.replace(Regex("/data/[^\\s]+"), "<path>")
    s = s.replace(Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"), "<email>")
    s = s.replace(
        Regex("[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|heic|gif|mp4|mov|m4v|avi|3gp)\\b", RegexOption.IGNORE_CASE),
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
