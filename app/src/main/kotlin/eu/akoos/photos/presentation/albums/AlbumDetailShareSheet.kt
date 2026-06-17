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

package eu.akoos.photos.presentation.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ShareAlbumSheet(
    sheetState: androidx.compose.material3.SheetState,
    albumName: String,
    isSharing: Boolean,
    isInvitingBatch: Boolean,
    isTogglingPublicLink: Boolean,
    publicShareUrl: String?,
    ownerEmail: String,
    hasShareRecord: Boolean,
    invitations: List<ShareInvitation>,
    members: List<ShareMember>,
    isLoadingInvitations: Boolean,
    inviteBatchResult: InviteBatchResult?,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onInviteUsers: (emails: List<String>, message: String, permissions: Int) -> Unit,
    onStopSharing: () -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onCreatePublicLink: () -> Unit,
    onDisablePublicLink: () -> Unit,
    onChangeMemberPermission: (memberId: String, permissions: Int) -> Unit,
    onChangeInvitationPermission: (invitationId: String, permissions: Int) -> Unit,
    onDismissInviteResult: () -> Unit,
) {
    var inviteEmail by remember { mutableStateOf("") }
    val pendingEmails = remember { mutableStateListOf<String>() }
    // Typed for Drive-web parity but dropped at the data-layer boundary — repo signature has no message field yet.
    var inviteMessage by remember { mutableStateOf("") }
    // 6 (editor) matches the inviteToAlbum() default in the data layer.
    var newInvitePermissions by remember { mutableStateOf(6) }

    val trimmedEmail = inviteEmail.trim()
    // Reject chars that slip through paste and break the recipient public-key URL lookup downstream.
    val hasForbiddenChars = trimmedEmail.any { it in "<>\"' \t\n\r" }
    val isValidEmail = trimmedEmail.isNotEmpty() && !hasForbiddenChars &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()
    val isDuplicate = isValidEmail && trimmedEmail in pendingEmails
    val canAdd = isValidEmail && !isDuplicate && !isInvitingBatch

    val resetInputs = {
        inviteEmail = ""
        pendingEmails.clear()
        inviteMessage = ""
    }

    val appColors = AppColors.current
    val isShareActive = publicShareUrl != null
    // Cover the orphan-share case (members/URL empty but a share record lingers) so "Stop sharing" can clean it up.
    val hasActiveShares = hasShareRecord || isShareActive ||
        members.isNotEmpty() || invitations.isNotEmpty()
    val hasPendingInvites = pendingEmails.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = { resetInputs(); onDismiss() },
        sheetState = sheetState,
        containerColor = appColors.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ── Header — "Share <albumName>" + × close ────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.share_sheet_title, albumName),
                    color = FgPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            resetInputs()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.share_close),
                        tint = FgMute,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Inline banner — the completion snackbar sits behind the open sheet, so feedback must render in-sheet.
            if (isInvitingBatch) {
                val sheetSendingText = stringResource(R.string.share_invite_sending)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = sheetSendingText,
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Inline success banner — the screen's snackbar is hidden behind the open sheet.
            if (inviteBatchResult != null &&
                inviteBatchResult.failures.isEmpty() &&
                inviteBatchResult.successCount > 0
            ) {
                val sheetSuccessMsg = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.share_invite_sent,
                    inviteBatchResult.successCount,
                    inviteBatchResult.successCount,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sheetSuccessMsg,
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDismissInviteResult() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close, null,
                                tint = Accent, modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // Inline failure banner — the screen's snackbar is hidden behind the open sheet.
            if (inviteBatchResult != null && inviteBatchResult.failures.isNotEmpty()) {
                val sheetAllFailedFmt = stringResource(R.string.share_invite_summary_all_failed)
                val sheetPartialFmt   = stringResource(R.string.share_invite_summary_partial)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(ErrorColor.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, ErrorColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (inviteBatchResult.successCount == 0)
                                sheetAllFailedFmt.format(inviteBatchResult.failures.size)
                            else
                                sheetPartialFmt.format(
                                    inviteBatchResult.successCount,
                                    inviteBatchResult.failures.size,
                                ),
                            color = ErrorColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDismissInviteResult() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close, null,
                                tint = ErrorColor, modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    inviteBatchResult.failures.forEach { (email, reason) ->
                        Text(
                            "• $reason",
                            color = FgPrimary, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            // Invite flow: type email → Add → chip; then Share fires one inviteToAlbum() per chip.
            // "Will invite" chip strip — only while there are pending chips.
            if (hasPendingInvites) {
                Text(
                    stringResource(R.string.share_invite_will_invite),
                    color = FgDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingEmails.forEach { email ->
                        androidx.compose.material3.AssistChip(
                            onClick = { /* no-op; close icon handles removal */ },
                            enabled = !isInvitingBatch,
                            label = { Text(email, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PersonAdd, null,
                                    tint = Accent, modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.share_remove_member),
                                    tint = FgMute,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = !isInvitingBatch) {
                                            pendingEmails.remove(email)
                                        },
                                )
                            },
                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = PillBg,
                                labelColor = FgPrimary,
                            ),
                            border = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                                enabled = !isInvitingBatch,
                                borderColor = PillBorder,
                                borderWidth = 0.5.dp,
                            ),
                        )
                    }
                }
            }

            // Email input row — pill input + Add button + permission selector.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(PillBg, RoundedCornerShape(12.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PersonAdd, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                    // BasicTextField, not OutlinedTextField: the latter's fixed 56 dp container can't shrink to the pill height.
                    androidx.compose.foundation.text.BasicTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        enabled = !isInvitingBatch,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = if (isInvitingBatch) FgDim else FgPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            // Done, not Send — the IME action only adds a chip, it doesn't submit the whole invite.
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canAdd) {
                                pendingEmails.add(trimmedEmail)
                                inviteEmail = ""
                            }
                        }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (inviteEmail.isEmpty()) {
                                Text(
                                    stringResource(R.string.share_invite_email_hint),
                                    color = FgMute, fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                    if (inviteEmail.isNotBlank()) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (canAdd) {
                                    pendingEmails.add(trimmedEmail)
                                    inviteEmail = ""
                                }
                            },
                            enabled = canAdd,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.share_invite_add),
                                color = if (canAdd) Accent else FgMute,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                // Permission dropdown — applies to all chips queued in the current batch.
                PermissionDropdown(
                    currentPermissions = newInvitePermissions,
                    onSelect = { newInvitePermissions = it },
                )
            }

            if (inviteEmail.isNotBlank() && !isValidEmail) {
                Text(
                    stringResource(R.string.share_invite_email_invalid),
                    color = ErrorColor, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                )
            } else if (isDuplicate) {
                Text(
                    stringResource(R.string.share_invite_email_duplicate),
                    color = ErrorColor, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                )
            }

            // Message field — revealed only once a chip exists, keeping the common single-email case small.
            if (hasPendingInvites) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = inviteMessage,
                    onValueChange = { inviteMessage = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.share_invite_message_hint),
                            color = FgMute, fontSize = 13.sp,
                        )
                    },
                    enabled = !isInvitingBatch,
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PillBorder,
                        unfocusedBorderColor = PillBorder,
                        focusedContainerColor = PillBg,
                        unfocusedContainerColor = PillBg,
                        focusedTextColor = FgPrimary,
                        unfocusedTextColor = FgPrimary,
                        cursorColor = Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                // Share + Cancel footer for the invite batch — distinct from the sheet-level "Stop sharing" footer.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            inviteEmail = ""
                            pendingEmails.clear()
                            inviteMessage = ""
                        },
                        enabled = !isInvitingBatch,
                    ) {
                        Text(
                            stringResource(R.string.share_invite_cancel),
                            color = FgDim, fontSize = 14.sp,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (isInvitingBatch) Accent.copy(alpha = 0.6f) else Accent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = !isInvitingBatch && pendingEmails.isNotEmpty()) {
                                val snapshot = pendingEmails.toList()
                                val msg = inviteMessage.trim()
                                onInviteUsers(snapshot, msg, newInvitePermissions)
                                // Optimistically clear; the VM holds the result for the screen's snackbar.
                                pendingEmails.clear()
                                inviteEmail = ""
                                inviteMessage = ""
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isInvitingBatch) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = Color.White, strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    stringResource(R.string.share_invite_send),
                                    color = Color.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.share_invite_send),
                                color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ── "Who has access" subheader + member list ─────────────────────
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.share_who_has_access),
                color = FgDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (ownerEmail.isNotEmpty()) {
                OwnerRow(email = ownerEmail)
            }

            if (isLoadingInvitations) {
                // Placeholder rows mirror MemberRow's metrics so the list doesn't jump when members fade in.
                repeat(2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShimmerBox(modifier = Modifier.size(32.dp), cornerRadius = 16.dp)
                        ShimmerTextLine(widthFraction = 0.55f, height = 14.dp)
                    }
                }
            }

            members.forEach { member ->
                MemberRow(
                    email = member.email,
                    permissions = member.permissions,
                    onChangePermission = { perm -> onChangeMemberPermission(member.memberId, perm) },
                    onRemove = { onRemoveMember(member.memberId) },
                )
            }

            invitations.forEach { inv ->
                PendingInvitationRow(
                    email = inv.email,
                    permissions = inv.permissions,
                    onChangePermission = { perm -> onChangeInvitationPermission(inv.invitationId, perm) },
                    onRevoke = { onRevokeInvitation(inv.invitationId) },
                )
            }

            // No public-link card: Drive rejects public URLs on album shares (code 2511); album sharing is invite-only.

            // Footer — "Stop sharing" revokes all member access.
            if (hasActiveShares) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSharing, onClick = onStopSharing)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(color = ErrorColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            stringResource(R.string.share_stop_sharing),
                            color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Share-sheet sub-rows ───────────────────────────────────────────────────────

/** Owner row — non-removable, displays the "owner" chip. */
@Composable
internal fun OwnerRow(email: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(Line2, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.share_role_owner), color = FgDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Accepted member row — clickable permission chip + remove × icon. */
@Composable
internal fun MemberRow(
    email: String,
    permissions: Int,
    onChangePermission: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        PermissionDropdown(
            currentPermissions = permissions,
            onSelect = onChangePermission,
            compact = true,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.share_remove_member), tint = FgMute, modifier = Modifier.size(16.dp))
        }
    }
}

/** Pending invitation row — amber chip + revoke × icon. */
@Composable
internal fun PendingInvitationRow(
    email: String,
    permissions: Int,
    onChangePermission: (Int) -> Unit,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Darker amber in light mode — the dark-card amber washes out on white.
        val pendingAmber = if (AppColors.current.isLight) Color(0xFFB45309) else Color(0xFFFCD34D)
        AvatarCircle(letter = email.first().uppercase(), tint = pendingAmber)
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        // Role stays editable pre-acceptance — the backend updates a pending invitation's bitmap like a member's.
        PermissionDropdown(
            currentPermissions = permissions,
            onSelect = onChangePermission,
            compact = true,
        )
        Box(
            modifier = Modifier
                .background(Color(0x33FCD34D), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.share_role_pending), color = pendingAmber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onRevoke),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.share_remove_member), tint = FgMute, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Drive-permission dropdown — "can view" (4) / "can edit" (6). Used both at the top of
 * the sheet (gating new invites) and inline on each member row. Pass `compact = true`
 * for the inline form which uses smaller padding and font.
 */
@Composable
internal fun PermissionDropdown(
    currentPermissions: Int,
    onSelect: (Int) -> Unit,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val labelRes = if (currentPermissions >= 6) R.string.share_role_can_edit else R.string.share_role_can_view
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (compact) Color.Transparent else PillBg)
                .then(if (compact) Modifier else Modifier.border(0.5.dp, PillBorder, RoundedCornerShape(8.dp)))
                .clickable { expanded = true }
                .padding(
                    horizontal = if (compact) 6.dp else 12.dp,
                    vertical = if (compact) 4.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(labelRes),
                color = FgPrimary,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium,
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = FgMute,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        }
        val menuColors = AppColors.current
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = menuColors.cardBg,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, menuColors.pillBorder),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_view), color = menuColors.fgPrimary) },
                onClick = { expanded = false; onSelect(4) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_edit), color = menuColors.fgPrimary) },
                onClick = { expanded = false; onSelect(6) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedWithMeInfoSheet(
    sheetState: androidx.compose.material3.SheetState,
    sharedByEmail: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.current.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.share_shared_with), color = FgPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PillBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(20.dp))
                Column {
                    Text(stringResource(R.string.share_shared_by), color = FgMute, fontSize = 12.sp)
                    Text(sharedByEmail, color = FgPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
