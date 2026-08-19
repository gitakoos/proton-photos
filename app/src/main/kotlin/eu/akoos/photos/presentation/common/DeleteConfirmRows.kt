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
import eu.akoos.photos.R

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
 */
fun deleteConfirmRows(hasLocal: Boolean, hasCloud: Boolean): List<DeleteRow> = when {
    hasLocal && hasCloud -> listOf(
        DeleteRow(DeleteRowKind.RemoveDevice, destructive = false, freeUpSpace = true, deleteFromCloud = false),
        DeleteRow(DeleteRowKind.RemoveCloud, destructive = false, freeUpSpace = false, deleteFromCloud = true),
        DeleteRow(DeleteRowKind.RemoveEverywhere, destructive = true, freeUpSpace = true, deleteFromCloud = true),
    )
    hasLocal -> listOf(
        DeleteRow(DeleteRowKind.TrashLocal, destructive = true, freeUpSpace = true, deleteFromCloud = false),
    )
    hasCloud -> listOf(
        DeleteRow(DeleteRowKind.TrashCloud, destructive = true, freeUpSpace = false, deleteFromCloud = true),
    )
    else -> emptyList()
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
