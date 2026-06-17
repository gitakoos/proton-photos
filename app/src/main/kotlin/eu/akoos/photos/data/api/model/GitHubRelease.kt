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

/** Subset of the GitHub Releases response we consume (parser uses ignoreUnknownKeys). */
@Serializable
data class GitHubRelease(
    /** Includes the leading 'v', e.g. "v2.0.1". */
    @SerialName("tag_name") val tagName: String,
    val name: String,
    val body: String,
    /** GitHub's pre-release flag — treated as "skip" before even looking at the tag. */
    val prerelease: Boolean,
    val assets: List<Asset>,
) {
    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long,
    )
}
