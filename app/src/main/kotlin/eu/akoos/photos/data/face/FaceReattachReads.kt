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

package eu.akoos.photos.data.face

import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.domain.usecase.ReattachFace
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.compareCodePoints
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Rows per page of the name capture's read: lean rows with no vector. */
private const val LABEL_PAGE_SIZE = 500

/**
 * The named faces of [account] as a reattach snapshot carries them: each face grouped under a person
 * [nameById] names, or else confirmed under a non-blank name, removed ones included, with its photo and
 * box. They come in the whole-table read's order, clearest detection first and then by id, the score
 * compared as a number and the id by code point, as SQLite orders them. Read a page of lean rows at a
 * time, and only the named faces are kept.
 */
internal suspend fun readReattachMembers(
    faceDao: FaceDao,
    account: String,
    nameById: Map<Long, String>,
): List<ReattachLabel> {
    val named = ArrayList<ScoredLabel>()
    var afterId = ""
    while (true) {
        val page = faceDao.labelRowsAfter(account, afterId, LABEL_PAGE_SIZE)
        for (f in page) {
            val name = f.personId?.let { nameById[it] } ?: f.manualName?.takeIf { it.isNotBlank() } ?: continue
            named += ScoredLabel(
                f.score,
                ReattachLabel(f.id, f.photoKey, f.boxLeft, f.boxTop, f.boxRight, f.boxBottom, name),
            )
        }
        if (page.size < LABEL_PAGE_SIZE) break
        afterId = page.last().id
        coroutineContext.ensureActive()
    }
    named.sortWith(SCORE_DESC_THEN_ID)
    return named.map { it.label }
}

/**
 * The faces of [account] a reattach of [data] can bind to, as photo and box: every face on a photo one of
 * its names, marks or removed boxes sits on, and on the photo of any face holding a label's id, since an
 * id match weighs the other faces of that face's own photo. The matchers look no further than these
 * photos, so they bind exactly as against the whole table, and no other photo's faces are read.
 */
internal suspend fun readReattachFaces(
    faceDao: FaceDao,
    account: String,
    data: FaceReattachData,
): List<ReattachFace> {
    val photoKeys = HashSet<String>()
    val labelIds = HashSet<String>()
    for (label in data.members) {
        photoKeys += label.photoKey
        labelIds += label.faceId
    }
    for (label in data.nots) {
        photoKeys += label.photoKey
        labelIds += label.faceId
    }
    for (box in data.rejected) photoKeys += box.photoKey
    labelIds.forEachSqlChunk { photoKeys += faceDao.photoKeysOfFacesChunk(account, it) }
    val faces = ArrayList<ReattachFace>()
    photoKeys.forEachSqlChunk { slice ->
        for (f in faceDao.faceBoxesOnPhotosChunk(account, slice)) {
            faces += ReattachFace(f.id, f.photoKey, f.boxLeft, f.boxTop, f.boxRight, f.boxBottom)
        }
    }
    return faces
}

private class ScoredLabel(val score: Float, val label: ReattachLabel)

private val SCORE_DESC_THEN_ID = Comparator<ScoredLabel> { a, b ->
    when {
        a.score > b.score -> -1
        a.score < b.score -> 1
        else -> compareCodePoints(a.label.faceId, b.label.faceId)
    }
}
