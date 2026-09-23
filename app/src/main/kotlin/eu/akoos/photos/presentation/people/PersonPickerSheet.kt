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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.AppSearchField
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.AppColors

/**
 * A tall bottom drawer for choosing a person by name, shared by every surface that picks or names a
 * person (a single person's "merge with", the review screen's bulk merge, the People search, and the
 * name-or-merge sheet) so they look and behave the same. It carries the app's search field, which narrows
 * the named people by name ignoring case and accents, and, when the typed text names nobody, offers to
 * create a new person with that name; the people fill a scrollable grid of face tiles. Text that already
 * names someone, case and accents aside, commits to that person, so no second person of the same name is
 * created from here. [onPick] returns the chosen person, [onCreateNew] a new name.
 *
 * [header], when given, is drawn in the title line's place above the search field, for a sheet that opens
 * with more than a title (the face it asks about); it then shows the title itself.
 */
@Composable
fun PersonPickerSheet(
    title: String,
    people: List<PersonUi>,
    onPick: (PersonUi) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: ((String) -> Unit)? = null,
    searchPlaceholder: String? = null,
    emptyText: String? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // Pure-search callers (no create) pass their own hint so the field does not read "or type a new
    // name" when there is nothing to create; merge callers keep the default.
    val placeholderText = searchPlaceholder ?: stringResource(R.string.person_picker_search)
    // The whole-person merge passes its own empty line ("no other people to merge"); the assign and
    // search callers keep the default.
    val emptyStateText = emptyText ?: stringResource(R.string.person_picker_empty)

    val q = query.trim()
    val filtered = remember(people, q) { PersonPickerSearch.filter(people, q) }
    val namedAlready = remember(people, q) { PersonPickerSearch.personNamed(people, q) }
    val commitTyped: () -> Unit = {
        if (q.isNotEmpty()) {
            if (namedAlready != null) onPick(namedAlready) else onCreateNew?.invoke(q)
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = colors.sheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            if (header != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { header() }
            } else {
                Text(
                    title,
                    color = colors.fgPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            AppSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = placeholderText,
                modifier = Modifier.fillMaxWidth(),
                // The keyboard's Done key commits the typed name the same way the Create row or the
                // matching person's tile does, then closes the keyboard as usual.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commitTyped()
                    defaultKeyboardAction(ImeAction.Done)
                }),
            )
            Spacer(Modifier.height(12.dp))

            // Offered only for a name nobody has yet; a name someone already has shows as their tile in
            // the grid below instead, so the row never reads "Create" for an existing person.
            if (onCreateNew != null && q.isNotEmpty() && namedAlready == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = commitTyped)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceWeak),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = colors.accent)
                    }
                    Text(
                        stringResource(R.string.person_picker_create, q),
                        color = colors.fgPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (filtered.isEmpty() && q.isEmpty()) {
                Text(
                    emptyStateText,
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(72.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(items = filtered, key = { it.personId }) { person ->
                        PersonTile(
                            person = person,
                            selected = false,
                            onClick = { onPick(person) },
                        )
                    }
                }
            }
        }
    }
}

/** The picker's name matching, kept free of Compose so it is tested on the JVM. */
internal object PersonPickerSearch {

    /** [people] whose name contains [query], ignoring case and accents, so "akos" finds "Ákos"; everyone
     *  for a blank query. The given order is kept. */
    fun filter(people: List<PersonUi>, query: String): List<PersonUi> {
        val needle = SearchFilter.fold(query.trim())
        if (needle.isEmpty()) return people
        return people.filter { SearchFilter.fold(it.displayName.orEmpty()).contains(needle) }
    }

    /**
     * The person [typed] is the name of, so committing it picks them rather than creating a second person
     * of that name: the exact spelling first, else the name that matches once case and accents are set
     * aside, the most photographed when several do. Null for blank text or a name nobody has. The whole
     * name has to match, and unnamed people and the Unsorted bucket never do.
     */
    fun personNamed(people: List<PersonUi>, typed: String): PersonUi? {
        val name = typed.trim()
        if (name.isEmpty()) return null
        val named = people.filter { !it.isOther && !it.displayName.isNullOrBlank() }
        named.filter { it.displayName?.trim() == name }.maxByOrNull { it.faceCount }?.let { return it }
        val folded = SearchFilter.fold(name)
        return named.filter { SearchFilter.fold(it.displayName.orEmpty().trim()) == folded }
            .maxByOrNull { it.faceCount }
    }
}
