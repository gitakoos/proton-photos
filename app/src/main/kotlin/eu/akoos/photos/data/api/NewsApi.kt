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

package eu.akoos.photos.data.api

import eu.akoos.photos.data.api.model.NewsFeed
import retrofit2.http.GET

/** Public, unauthenticated binding for the news feed hosted on the website. */
interface NewsApi {

    /** The current feed. Served as a static file, so a message is published by editing it and
     *  deploying the site, with no app update in the loop. */
    @GET("news/feed.json")
    suspend fun getFeed(): NewsFeed
}
