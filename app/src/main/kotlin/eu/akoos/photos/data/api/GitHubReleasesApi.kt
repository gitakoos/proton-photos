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

package eu.akoos.photos.data.api

import eu.akoos.photos.data.api.model.GitHubRelease
import retrofit2.http.GET

/** Public, unauthenticated binding for the GitHub Releases API (anonymous calls capped at 60/hour/IP). */
interface GitHubReleasesApi {

    /** Returns the most recent NON-pre-release release. Pre-releases need a different endpoint. */
    @GET("repos/gitakoos/proton-photos/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}
