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

package eu.akoos.photos.data.api.model

import kotlinx.serialization.Serializable

/**
 * The news feed the app fetches from the website. Publishing a message is a one-file edit plus a
 * website deploy, so a note reaches everyone without an app update, and the link people write to
 * for feedback travels in the same file so it can move without a release either.
 *
 * The parser uses ignoreUnknownKeys, so the feed can gain fields the running app has never heard of
 * without breaking the ones it has.
 */
@Serializable
data class NewsFeed(
    /** Where a "Send feedback" tap goes: the feedback thread itself. Absent hides the button rather
     *  than sending nowhere. A Discord thread link opens straight to the post for anyone already in
     *  that server, so it is the direct route for a member. */
    val feedbackUrl: String? = null,
    /** Where to send someone who is not in that server yet: an invite that lets them join first, so
     *  the direct thread link has somewhere to land. Shown as a quieter second line under the
     *  button; absent hides that line. */
    val feedbackJoinUrl: String? = null,
    /** An email address for anyone who would rather not use Discord at all. Shown as a plain
     *  alternative under the button; absent hides it. */
    val feedbackEmail: String? = null,
    val items: List<NewsItem> = emptyList(),
)

/** One entry in the feed. [id] is the stable handle the read-state is kept by, so editing an
 *  entry's wording never marks it unread again, and only a new [id] counts as new. */
@Serializable
data class NewsItem(
    /** The stable handle the read-state is kept by AND the short code shown on the entry and used to
     *  open it on the website (photosforproton.eu/news/#id). Keep it short, e.g. "1024". */
    val id: String,
    /** Date the entry is shown under, e.g. "3 August 2026". Display-only, never parsed for logic. */
    val date: String = "",
    val title: String = "",
    val body: String = "",
    /** When true the title is drawn in orange, so an urgent message stands out at a glance. */
    val important: Boolean = false,
    /** Optional "Read more" target for an entry that points somewhere. Absent hides the link. */
    val link: String? = null,
)
