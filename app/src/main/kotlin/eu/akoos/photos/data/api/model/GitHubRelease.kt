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

package eu.akoos.photos.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the GitHub Releases API response we actually consume — the full payload has
 * dozens of fields (author, reactions, etc.) we don't need; the JSON parser is
 * configured with `ignoreUnknownKeys = true` so adding fields server-side won't break us.
 *
 * Endpoint: GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 * Docs:     https://docs.github.com/en/rest/releases/releases#get-the-latest-release
 */
@Serializable
data class GitHubRelease(
    /** Tag the release points at, e.g. "v2.0.1". Includes the leading 'v' as the project tags it. */
    @SerialName("tag_name") val tagName: String,
    /** Human-readable release title, e.g. "v2.0.1 — bug fixes". */
    val name: String,
    /** Release notes body, markdown-formatted. */
    val body: String,
    /** GitHub's own pre-release flag. We treat true here as "skip" before even looking at the tag. */
    val prerelease: Boolean,
    /** Downloadable APKs (and any other files) attached to the release. */
    val assets: List<Asset>,
) {
    @Serializable
    data class Asset(
        /** Filename of the asset, e.g. "photosforproton-arm64-v8a-release.apk". */
        val name: String,
        /** Direct download URL; works without auth for public repos. */
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        /** Size in bytes — surfaced to the user before they commit to the download. */
        val size: Long,
    )
}
