@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.calendar.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Bottom sheet for editing a single text field (location or description) on Day Detail.
 *
 * Lives alongside [MonthGrid]/[MonthPager] in the calendar `components` package so the
 * DayDetailScreen stays focused on layout. Mirrors the create-album bottom sheet recipe
 * from AlbumsScreen — same containerColor, scrim alpha, padding shape — so the surface
 * feels native to the app.
 *
 * The caller owns the initial text (`initialValue`); we keep a local mirror so each
 * keystroke doesn't round-trip through Room. On save we publish the trimmed-or-blank
 * string via [onSave] and animate the sheet closed before invoking [onDismiss].
 *
 * @param title       sheet title (e.g. "Edit location")
 * @param hint        placeholder shown when the field is empty
 * @param initialValue current persisted value, copied into local state on first compose
 * @param singleLine  true for the location field (one line), false for description (multi)
 * @param onDismiss   called when the user dismisses without saving (scrim tap, back press)
 *                    OR after the save animation finishes
 * @param onSave      called with the new value; receiver should persist it
 */
@Composable
fun EditFieldSheet(
    title: String,
    hint: String,
    initialValue: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Local input state — the user edits this freely; we only push to the caller when
    // they tap Save (so dismissing without saving leaves the persisted value untouched).
    var text by remember(initialValue) { mutableStateOf(initialValue) }

    /** Helper — animate the sheet closed then notify the parent we dismissed. */
    fun closeWith(finalAction: () -> Unit) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            finalAction()
            onDismiss()
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = colors.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                title,
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(hint, color = colors.fgMute)
                },
                singleLine = singleLine,
                // Description field gets more vertical room so multi-line notes don't
                // feel cramped; location stays single-line.
                minLines = if (singleLine) 1 else 3,
                maxLines = if (singleLine) 1 else 8,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = colors.accent,
                    unfocusedBorderColor = colors.line2,
                    focusedTextColor     = colors.fgPrimary,
                    unfocusedTextColor   = colors.fgPrimary,
                    cursorColor          = colors.accent,
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (singleLine) {
                            closeWith { onSave(text) }
                        }
                    },
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { closeWith { } }) {
                    Text(stringResource(R.string.cancel), color = colors.fgDim)
                }
                TextButton(onClick = { closeWith { onSave(text) } }) {
                    Text(
                        stringResource(R.string.day_detail_save),
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
