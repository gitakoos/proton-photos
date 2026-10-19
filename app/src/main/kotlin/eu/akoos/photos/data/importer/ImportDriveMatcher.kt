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

package eu.akoos.photos.data.importer

import eu.akoos.photos.data.db.dao.ImportUploadedDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.UploadFormatNaming
import eu.akoos.photos.domain.usecase.uploadRewritesBytes
import kotlinx.coroutines.CancellationException
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** A Drive link that is live, not in the trash. */
private const val LINK_STATE_ACTIVE = 1

/** The Drive check for an import ledger link could not be made, so the entry is retried on a later run. */
class ImportLinkCheckException(cause: Throwable) : Exception("import link check failed", cause)

/**
 * Decides whether an import entry is already in this user's Drive, for the review badge and for the
 * upload alike. An entry is in Drive when any of these holds, checked in this order:
 *
 *  1. Drive holds the exact bytes of one of the entry's digests ([Entry.sha1s]).
 *  2. An earlier import of this user recorded the same archive bytes ([Entry.sourceSha1]) and the link it
 *     recorded still exists. A compressed upload cannot be reproduced byte for byte, so this is what
 *     recognises a re-run.
 *  3. While the backup rewrites this kind of file (compression, or strip for a photo), a photo of the same
 *     name, or of the name the rewrite gives it, captured in the same second. The backup's compressed or
 *     stripped copy of a photo can only be found this way.
 */
@Singleton
class ImportDriveMatcher @Inject constructor(
    private val drivePhotoRepository: DrivePhotoRepository,
    private val photoListingDao: PhotoListingDao,
    private val importUploadedDao: ImportUploadedDao,
    private val linkDetailHelpers: LinkDetailHelpers,
) {

    /**
     * One entry to look for: the SHA-1 hex digests its bytes have had, the digest of the bytes as the
     * archive holds them, and the name, type and capture time it is uploaded under.
     */
    data class Entry(
        val sha1s: List<String>,
        val sourceSha1: String,
        val name: String,
        val mimeType: String,
        val captureMs: Long,
    )

    /**
     * The Drive link [entry] already is for [userId], or null. With [confirmLedgerLink] a ledger link counts
     * only while the library holds it or Drive reports it live; without, any recorded link counts. A Drive
     * check that fails throws [ImportLinkCheckException].
     */
    suspend fun find(
        userId: UserId,
        entry: Entry,
        settings: CompressionSettings,
        stripOnUpload: Boolean,
        confirmLedgerLink: Boolean,
    ): String? =
        linkByContent(userId, entry.sha1s)
            ?: ledgerLink(userId, entry.sourceSha1, confirmLedgerLink)
            ?: twinLink(userId, entry, settings, stripOnUpload)

    /** The link of a photo of [userId]'s whose content is one of [sha1s], or null. */
    suspend fun linkByContent(userId: UserId, sha1s: List<String>): String? {
        for (sha1 in sha1s.distinct()) {
            val hash = drivePhotoRepository.cloudContentHash(sha1) ?: continue
            photoListingDao.linkIdByContentHash(userId.id, hash)?.let { return it }
        }
        return null
    }

    private suspend fun ledgerLink(userId: UserId, sourceSha1: String, confirm: Boolean): String? {
        val links = importUploadedDao.linksForSource(userId.id, sourceSha1)
        if (links.isEmpty()) return null
        if (!confirm) return links.first()
        links.firstOrNull { photoListingDao.hasLink(userId.id, it) }?.let { return it }
        val details = try {
            linkDetailHelpers.batchFetchLinkDetails(userId, drivePhotoRepository.getVolumeId(userId), links)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImportLinkCheckException(e)
        }
        return links.firstOrNull { details[it]?.link?.state == LINK_STATE_ACTIVE }
    }

    private suspend fun twinLink(
        userId: UserId,
        entry: Entry,
        settings: CompressionSettings,
        stripOnUpload: Boolean,
    ): String? {
        if (!uploadRewritesBytes(entry.mimeType, stripOnUpload, settings.photos, settings.videos)) return null
        val rewritesFormat =
            if (entry.mimeType.startsWith("video/")) settings.videos else settings.photos || stripOnUpload
        val names = listOfNotNull(
            entry.name,
            UploadFormatNaming.reencodedName(entry.name, entry.mimeType)?.takeIf { rewritesFormat },
        )
        return photoListingDao.linkIdByNameAndCapture(userId.id, names, entry.captureMs / 1000L)
    }
}
