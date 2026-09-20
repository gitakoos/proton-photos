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

package eu.akoos.photos.presentation.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.util.formatBytes

/** Which copy a delete-confirm row removes, so each surface can label it in its own words. */
enum class DeleteRowKind { RemoveDevice, RemoveCloud, RemoveEverywhere, TrashLocal, TrashCloud }

/**
 * One row of a delete confirmation: what it removes, whether it is the red full delete, and the
 * `(freeUpSpace, deleteFromCloud)` pair its tap asks the delete action for.
 */
data class DeleteRow(
    val kind: DeleteRowKind,
    val destructive: Boolean,
    val freeUpSpace: Boolean,
    val deleteFromCloud: Boolean,
    /** Device bytes this row reclaims, appended to its description as a freed-space note. Set only on
     *  [freeUpSpace] rows, and only when [deleteConfirmRows] was given a positive figure. */
    val freedBytes: Long? = null,
)

/**
 * The rows a delete confirmation offers for a selection holding [hasLocal] device copies and/or
 * [hasCloud] Proton Drive copies.
 *
 * One source for the viewer's single-photo sheet and the timeline's multi-select sheet, so the two
 * can never disagree on which rows appear or which one is the red full delete. A selection with both
 * sides offers two neutral partial removals then the red everywhere; a one-sided selection offers
 * only its own red trash row. A partial removal is neutral because it leaves the other copy in place,
 * so only the row that takes the last copy of everything is destructive.
 *
 * [reclaimableBytes] is the device space the selection occupies, when a caller has resolved it. When
 * positive it is carried on every space-freeing row as [DeleteRow.freedBytes] so the sheet can show
 * how much a removal frees, matching the free-up screen.
 */
fun deleteConfirmRows(
    hasLocal: Boolean,
    hasCloud: Boolean,
    reclaimableBytes: Long? = null,
): List<DeleteRow> {
    val freed = reclaimableBytes?.takeIf { it > 0L }
    return when {
        hasLocal && hasCloud -> listOf(
            DeleteRow(DeleteRowKind.RemoveDevice, destructive = false, freeUpSpace = true, deleteFromCloud = false, freedBytes = freed),
            DeleteRow(DeleteRowKind.RemoveCloud, destructive = false, freeUpSpace = false, deleteFromCloud = true),
            DeleteRow(DeleteRowKind.RemoveEverywhere, destructive = true, freeUpSpace = true, deleteFromCloud = true, freedBytes = freed),
        )
        hasLocal -> listOf(
            DeleteRow(DeleteRowKind.TrashLocal, destructive = true, freeUpSpace = true, deleteFromCloud = false, freedBytes = freed),
        )
        hasCloud -> listOf(
            DeleteRow(DeleteRowKind.TrashCloud, destructive = true, freeUpSpace = false, deleteFromCloud = true),
        )
        else -> emptyList()
    }
}

/**
 * The title string for a [kind] of row, shared by both delete sheets so the same choice reads the
 * same everywhere. [vaulted] is the viewer's one special case: a hidden-vault photo has no device
 * trash behind it, so its device row says the deletion is permanent.
 */
@StringRes
fun deleteRowTitleRes(kind: DeleteRowKind, vaulted: Boolean = false): Int = when (kind) {
    DeleteRowKind.RemoveDevice -> R.string.delete_multi_remove_device
    DeleteRowKind.RemoveCloud -> R.string.delete_multi_remove_cloud
    DeleteRowKind.RemoveEverywhere -> R.string.delete_multi_move_trash_everywhere
    DeleteRowKind.TrashLocal -> if (vaulted) R.string.delete_button_permanently else R.string.delete_multi_move_trash
    DeleteRowKind.TrashCloud -> R.string.delete_multi_drive_trash
}

/** The description line under a [kind] of row; see [deleteRowTitleRes] for [vaulted]. */
@StringRes
fun deleteRowDescRes(kind: DeleteRowKind, vaulted: Boolean = false): Int = when (kind) {
    DeleteRowKind.RemoveDevice -> R.string.delete_multi_remove_device_desc
    DeleteRowKind.RemoveCloud -> R.string.delete_multi_remove_cloud_desc
    DeleteRowKind.RemoveEverywhere -> R.string.delete_multi_move_trash_everywhere_desc
    DeleteRowKind.TrashLocal -> if (vaulted) R.string.viewer_delete_vault_body else R.string.delete_multi_move_trash_desc
    DeleteRowKind.TrashCloud -> R.string.delete_multi_drive_trash_desc
}

/**
 * The description line for [row], with a freed-space note appended when the row reclaims device
 * bytes (see [DeleteRow.freedBytes]). One helper so both delete sheets word that note identically;
 * see [deleteRowTitleRes] for [vaulted].
 */
@Composable
fun deleteRowDescription(row: DeleteRow, vaulted: Boolean = false): String {
    val base = stringResource(deleteRowDescRes(row.kind, vaulted))
    val bytes = row.freedBytes ?: return base
    return base + " " + stringResource(R.string.delete_frees_note, formatBytes(bytes))
}
