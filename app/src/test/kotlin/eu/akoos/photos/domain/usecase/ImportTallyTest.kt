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

import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** A run's summary counts a Live Photo once and never reports more skipped photos than are left to send. */
class ImportTallyTest {

    private fun staged(
        name: String,
        liveOf: String? = null,
        uploaded: Boolean = false,
        linkId: String? = null,
        excluded: Boolean = false,
    ) = ImportStagedEntity(
        zipId = "zip-1", entryName = name, stagedAt = 1L, liveOf = liveOf, uploaded = uploaded, linkId = linkId,
        excluded = excluded,
    )

    private fun ledger(linkId: String, alreadyInDrive: Boolean = false) =
        ImportUploadedEntity(runId = "run-1", linkId = linkId, sha1 = "sha-$linkId", alreadyInDrive = alreadyInDrive)

    private fun ImportSummary.counts() = listOf(total, imported, alreadyInDrive, skipped, failed)

    @Test
    fun `a Live Photo and a photo are two`() {
        val summary = importTally(
            ledger = listOf(ledger("L1"), ledger("L2"), ledger("L3")),
            staged = listOf(
                staged("a.jpg", uploaded = true, linkId = "L1"),
                staged("a.mp4", liveOf = "a.jpg", uploaded = true, linkId = "L2"),
                staged("b.jpg", uploaded = true, linkId = "L3"),
            ),
            notFound = 0,
        )

        assertEquals(listOf(2, 2, 0, 0, 0), summary.counts())
    }

    @Test
    fun `finishing a Live Photo an earlier run started counts one`() {
        val summary = importTally(
            ledger = listOf(ledger("L2")),
            staged = listOf(
                staged("a.jpg", uploaded = true, linkId = "L1"),
                staged("a.mp4", liveOf = "a.jpg", uploaded = true, linkId = "L2"),
            ),
            notFound = 0,
        )

        assertEquals(listOf(1, 1, 0, 0, 0), summary.counts())
    }

    @Test
    fun `a second copy of a Live Photo in the same run is already in Drive`() {
        val summary = importTally(
            ledger = listOf(ledger("L1"), ledger("L2"), ledger("L1", alreadyInDrive = true), ledger("L2", alreadyInDrive = true)),
            staged = listOf(
                staged("A/a.jpg", uploaded = true, linkId = "L1"),
                staged("A/a.mp4", liveOf = "A/a.jpg", uploaded = true, linkId = "L2"),
                staged("B/a.jpg", uploaded = true, linkId = "L1"),
                staged("B/a.mp4", liveOf = "B/a.jpg", uploaded = true, linkId = "L2"),
            ),
            notFound = 0,
        )

        assertEquals(listOf(2, 1, 1, 0, 0), summary.counts())
    }

    @Test
    fun `a still that failed with its video unsent is one failure, not a skip`() {
        val summary = importTally(
            ledger = emptyList(),
            staged = listOf(staged("a.jpg"), staged("a.mp4", liveOf = "a.jpg")),
            notFound = 0,
        )

        assertEquals(listOf(1, 0, 0, 0, 1), summary.counts())
    }

    @Test
    fun `a video that failed behind a still sent this run leaves the Live Photo failed, not imported`() {
        val summary = importTally(
            ledger = listOf(ledger("L1")),
            staged = listOf(staged("a.jpg", uploaded = true, linkId = "L1"), staged("a.mp4", liveOf = "a.jpg")),
            notFound = 0,
        )

        assertEquals(listOf(1, 0, 0, 0, 1), summary.counts())
    }

    @Test
    fun `a video that failed behind a still an earlier run sent is a failure of its own`() {
        val summary = importTally(
            ledger = emptyList(),
            staged = listOf(staged("a.jpg", uploaded = true, linkId = "L1"), staged("a.mp4", liveOf = "a.jpg")),
            notFound = 0,
        )

        assertEquals(listOf(1, 0, 0, 0, 1), summary.counts())
    }

    @Test
    fun `photos not found never make skipped exceed what is left, and excluded rows do not count`() {
        val summary = importTally(
            ledger = listOf(ledger("L1")),
            staged = listOf(
                staged("a.jpg", uploaded = true, linkId = "L1"),
                staged("b.jpg"),
                staged("c.jpg", excluded = true),
            ),
            notFound = 5,
        )

        assertEquals(listOf(2, 1, 0, 1, 0), summary.counts())
    }
}
