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

import io.mockk.coEvery
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStatePage
import eu.akoos.photos.domain.repository.SyncStateRepository

/**
 * Answers the paged and per-uri reads of a mocked [SyncStateRepository] from whatever its `observeAll`
 * is stubbed to serve, as the real table would: a page walk in list order, a lookup of an account's
 * own rows, and the SYNCED cloud ids among a set. A relaxed mock would answer these with nothing, and a
 * test would then pass without its rows ever reaching the pass.
 */
internal fun SyncStateRepository.answerReadsFromObserveAll() {
    val repo = this
    coEvery { repo.pageByStatus(any(), any(), any(), any()) } coAnswers {
        val rows = repo.observeAll(firstArg<UserId>()).first().filter { it.status == secondArg<SyncStatus>() }
        val from = thirdArg<Long>().coerceIn(0L, rows.size.toLong())
        val page = rows.drop(from.toInt()).take(arg<Int>(3))
        SyncStatePage(page, from + page.size)
    }
    coEvery { repo.getOwnByUris(any(), any()) } coAnswers {
        val wanted = secondArg<List<String>>().toHashSet()
        repo.observeAll(firstArg<UserId>()).first().filter { it.localUri in wanted }
    }
    coEvery { repo.syncedCloudIdsAmong(any(), any()) } coAnswers {
        val wanted = secondArg<Collection<String>>()
        repo.observeAll(firstArg<UserId>()).first()
            .filter { it.status == SyncStatus.SYNCED }
            .mapNotNull { it.cloudFileId }
            .filterTo(HashSet()) { it in wanted }
    }
}
