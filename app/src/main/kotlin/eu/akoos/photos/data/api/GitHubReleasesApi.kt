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

/**
 * Read-only Retrofit binding for the GitHub Releases REST API. Public, unauthenticated —
 * GitHub rate-limits anonymous calls to 60/hour per IP, but the repository layer caches
 * the last check for 24h so a single device burns at most ~1 call/day.
 *
 * Owner + repo are baked into the path because the GitHub repo for this app is fixed;
 * if it ever moves the path here is the only thing that needs updating.
 */
interface GitHubReleasesApi {

    /** Returns the most recent NON-pre-release release. Pre-releases need a different endpoint. */
    @GET("repos/gitakoos/proton-photos/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}
