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

package eu.akoos.photos.presentation.settings.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.FreeUpInterval
import eu.akoos.photos.presentation.theme.AppColors

private val cardShape = RoundedCornerShape(12.dp)

@Composable
internal fun SectionLabel(text: String) {
    val colors = AppColors.current
    Text(text.uppercase(), color = colors.fgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/**
 * Debounce wrapper for navigation actions like Settings X / sub page back arrow. The
 * raw `Modifier.clickable` re-fires on every up event, so a spam tap on a back button
 * pops the back stack N times. Three rapid taps from inside Settings popped past the
 * Loading start destination and landed on an empty NavHost — the user saw a black
 * screen and had to force restart the app. Wrapping the action so it fires at most
 * once per [intervalMs] kills that crash without changing the perceived feel of a
 * single tap.
 */
@Composable
internal fun rememberDebouncedAction(
    intervalMs: Long = 600L,
    action: () -> Unit,
): () -> Unit {
    val lastFireMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    return remember(action, intervalMs) {
        {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFireMs.longValue >= intervalMs) {
                lastFireMs.longValue = now
                action()
            }
        }
    }
}

/**
 * Collapsible Settings section. Renders a clickable header (bigger / mixed-case label
 * plus an animated chevron) followed by [content] when expanded. Defaults to open so
 * a fresh visit shows every option; the user can collapse sections they don't tweak
 * often. The whole header row consumes the tap so we don't have to wire each child.
 *
 * The chevron rotates 180° between expanded and collapsed states via animateFloatAsState
 * — same motion convention as the per-cell ExpandableHeaderRow inside Privacy's strip
 * customise area, so the disclosure pattern reads identically everywhere.
 */
@Composable
internal fun CollapsibleSection(
    label: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    var expanded by rememberSaveable(label) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "section_chevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer(rotationZ = rotation),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.cardBg, cardShape).border(0.5.dp, colors.cardBorder, cardShape),
    ) { content() }
}

/**
 * Disclosure row used to gate optional sub-toggles behind a collapsed default state.
 * Renders a label + a chevron that flips between ExpandMore (collapsed) and ExpandLess
 * (expanded). No Switch — the only thing this row does is toggle the local visibility
 * of the rows below it.
 */
@Composable
internal fun ExpandableHeaderRow(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = colors.fgPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = colors.fgMute,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun NavRow(label: String, description: String? = null, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute, fontSize = 12.5.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.fgMute, modifier = Modifier.size(14.dp))
    }
}

/**
 * Read-only row: a label + optional description with a static trailing value chip.
 * No switch and no chevron, so it reads as informational rather than an interactive
 * control. Used for settings whose state lives elsewhere (e.g. a server-side account
 * preference) and is only mirrored here.
 */
@Composable
internal fun InfoRow(label: String, description: String? = null, value: String) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = colors.fgDim,
            fontSize = 12.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceWeak)
                .border(0.5.dp, colors.line2, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Drilldown row that sits underneath a parent toggle. Visually the same as [NavRow]
 * but indented (28.dp start) so the user reads it as a child of the toggle above.
 * Greys out when the parent is disabled — same alpha treatment as [ToggleRow] for
 * consistency.
 */
@Composable
internal fun IndentedNavRow(
    label: String,
    description: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 28.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 12.5.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.fgMute.copy(alpha = alpha), modifier = Modifier.size(14.dp))
    }
}

@Composable
internal fun RowDivider() {
    val colors = AppColors.current
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = colors.cardBorder)
}

@Composable
internal fun ToggleRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    val alpha = if (enabled) 1f else 0.4f
    // Indented rows get extra left padding only; the previous version drew a 6dp dash
    // marker that misaligned the title and read as a stray "- " bullet, especially on
    // long labels that wrapped. Cleaner: a pure indent gives the visual hierarchy
    // without the marker artifact.
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 32.dp else 16.dp,
            end = 16.dp, top = 13.dp, bottom = 13.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor   = colors.accent,
                checkedThumbColor   = Color.White,
                uncheckedTrackColor = colors.line2,
                uncheckedThumbColor = colors.fgMute,
                disabledCheckedTrackColor   = colors.accent.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = colors.line2.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
internal fun SelectRow(
    label: String,
    description: String? = null,
    selected: FreeUpInterval,
    onSelected: (FreeUpInterval) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    var expanded by remember { mutableStateOf(false) }
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 28.dp else 16.dp,
            end = 16.dp, top = 11.dp, bottom = 11.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 11.5.sp)
        }
        Box {
            Text(
                stringResource(selected.labelRes), color = if (enabled) colors.fgDim else colors.fgMute, fontSize = 12.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceWeak)
                    .border(0.5.dp, colors.line2, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = colors.cardBg,
                border = BorderStroke(0.5.dp, colors.pillBorder),
            ) {
                FreeUpInterval.entries.forEach { interval ->
                    DropdownMenuItem(
                        text = { Text(stringResource(interval.labelRes), color = colors.fgPrimary) },
                        onClick = { onSelected(interval); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * App-lock timeout picker. 0 = lock immediately on background; non-zero values mean the
 * lock only kicks in after that many minutes in the background, so a quick app-switch
 * doesn't re-prompt for biometrics every time.
 */
@Composable
internal fun AppLockTimeoutRow(
    label: String,
    description: String? = null,
    selectedMinutes: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = AppColors.current
    var expanded by remember { mutableStateOf(false) }
    val options: List<Pair<Int, Int>> = listOf(
        0   to R.string.settings_app_lock_timeout_immediate,
        1   to R.string.settings_app_lock_timeout_1min,
        5   to R.string.settings_app_lock_timeout_5min,
        10  to R.string.settings_app_lock_timeout_10min,
        15  to R.string.settings_app_lock_timeout_15min,
        60  to R.string.settings_app_lock_timeout_1h,
    )
    val selectedLabel = options.firstOrNull { it.first == selectedMinutes }?.second
        ?: R.string.settings_app_lock_timeout_immediate
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute, fontSize = 11.5.sp)
        }
        Box {
            Text(
                stringResource(selectedLabel), color = colors.fgDim, fontSize = 12.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceWeak)
                    .border(0.5.dp, colors.line2, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = colors.cardBg,
                border = BorderStroke(0.5.dp, colors.pillBorder),
            ) {
                options.forEach { (minutes, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes), color = colors.fgPrimary) },
                        onClick = { onSelected(minutes); expanded = false },
                    )
                }
            }
        }
    }
}
