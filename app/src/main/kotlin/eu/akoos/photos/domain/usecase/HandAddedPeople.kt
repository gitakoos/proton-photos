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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.PersonEntity

/**
 * Photos added to a person by hand are kept under the person's NAME, and a named person stands while it holds
 * faces or such photos. So every name holding hand-added photos has a person: one with none here (a regroup
 * left out a person whose last face had moved away, or the photos came from another device under a name no
 * face here carries) gets a person with no face, counted by those photos, under [reuseIdFor]'s id when it
 * gives one. A name differing from a held one only by [personNameKey] counts as held, and a hidden group's
 * reserved name is left alone.
 *
 * It only inserts people and notes no curation time, so it takes nothing from and hands nothing to another
 * device. Runs inside the caller's transaction when there is one; returns how many people it made.
 */
internal suspend fun keepHandAddedPeople(
    db: AppDatabase,
    account: String,
    reuseIdFor: (String) -> Long? = { null },
): Int {
    val manual = db.personManualPhotoDao().allForUser(account)
    if (manual.isEmpty()) return 0
    val personDao = db.personDao()
    val held = personDao.namedPeopleForUser(account)
        .mapNotNullTo(HashSet()) { person -> person.displayName?.takeIf { it.isNotBlank() }?.let(::personNameKey) }
    var made = 0
    for ((name, photoKeys) in manual.groupBy({ it.personName }, { it.photoKey })) {
        if (name.isBlank() || HiddenPersonToken.isToken(name)) continue
        if (!held.add(personNameKey(name))) continue
        personDao.insert(
            PersonEntity(
                id = reuseIdFor(name) ?: 0L,
                userId = account,
                displayName = name,
                faceCount = photoKeys.toHashSet().size,
            ),
        )
        made++
    }
    return made
}
