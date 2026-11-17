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

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A three-column grid under a floating bar, as the search results lay out: its first row has to sit
 * fully below the bar whenever the grid is at the top, whatever lands ahead of the photo that was first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class GridTopAnchorTest {

    private var items by mutableStateOf(emptyList<String>())
    private var barHeight by mutableStateOf(170.dp)
    private val tops = HashMap<String, Float>()
    private var barBottom = 0f

    private fun photos(prefix: String, count: Int) = List(count) { "$prefix$it" }

    /** The bar over the grid, the grid padded clear of it, and the anchor under test. */
    @Composable
    private fun Screen(state: LazyGridState, bar: Dp) {
        Box(Modifier.size(400.dp, 800.dp)) {
            val current = items
            if (current.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = state,
                    contentPadding = PaddingValues(top = bar),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(current, key = { it }) { key ->
                        Box(
                            Modifier.fillMaxWidth().height(120.dp)
                                .onGloballyPositioned { tops[key] = it.positionInRoot().y },
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(bar)
                    .onGloballyPositioned { barBottom = it.positionInRoot().y + it.size.height },
            )
            KeepGridAtTopOnChange(state, current.firstOrNull())
        }
    }

    private lateinit var root: View

    private fun launch(state: LazyGridState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { Screen(state, barHeight) }
        root = activity.window.decorView
        settle()
    }

    /**
     * Runs the pending frames. Nothing draws under Robolectric and Compose lays out from its draw pass,
     * so each frame also forces a view traversal, which measures and places the composition.
     */
    private fun settle() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            forceLayout(root)
            root.requestLayout()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
    }

    private fun forceLayout(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (i in 0 until view.childCount) forceLayout(view.getChildAt(i))
    }

    private fun assertFirstRowBelowBar(first: String) {
        val top = tops[first] ?: error("$first was never laid out")
        assertTrue("first row top $top is above the bar bottom $barBottom", top >= barBottom)
    }

    @Test
    fun `three photos landing ahead of the top one stay below the bar`() {
        items = photos("old", 30)
        val state = LazyGridState()
        launch(state)
        assertFirstRowBelowBar("old0")

        items = photos("new", 3) + items
        settle()

        assertEquals(0, state.firstVisibleItemIndex)
        assertFirstRowBelowBar("new0")
    }

    @Test
    fun `a full-width row landing ahead of the photos stays below the bar`() {
        items = photos("p", 30)
        val state = LazyGridState()
        launch(state)

        items = listOf("match_albums", "m1", "m2") + items
        settle()

        assertEquals(0, state.firstVisibleItemIndex)
        assertFirstRowBelowBar("match_albums")
    }

    @Test
    fun `a taller bar pushes the first row down with it`() {
        items = photos("p", 30)
        val state = LazyGridState()
        launch(state)

        barHeight = 230.dp
        settle()

        assertFirstRowBelowBar("p0")
        assertEquals(0, state.firstVisibleItemIndex)
    }

    @Test
    fun `a new set opens at the top where an earlier one was scrolled`() {
        items = photos("old", 60)
        val state = LazyGridState()
        launch(state)
        runBlocking { state.scrollToItem(30) }
        settle()

        items = emptyList()
        settle()
        items = photos("new", 30)
        settle()

        assertEquals(0, state.firstVisibleItemIndex)
        assertFirstRowBelowBar("new0")
    }

    @Test
    fun `the same set coming back after a blank keeps its place`() {
        val set = photos("p", 60)
        items = set
        val state = LazyGridState()
        launch(state)
        runBlocking { state.scrollToItem(30) }
        settle()

        items = emptyList()
        settle()
        items = set
        settle()

        assertEquals(30, state.firstVisibleItemIndex)
    }

    @Test
    fun `a reader scrolled into the list is not moved by a change`() {
        items = photos("p", 60)
        val state = LazyGridState()
        launch(state)
        runBlocking { state.scrollToItem(30) }
        settle()

        items = photos("new", 3) + items
        settle()

        assertFalse(state.firstVisibleItemIndex == 0)
        assertEquals("p30", state.layoutInfo.visibleItemsInfo.first { it.offset.y >= 0 }.key)
    }

    @Test
    fun `a restored position survives the first composition`() {
        items = photos("p", 60)
        val state = LazyGridState(firstVisibleItemIndex = 30)
        launch(state)

        assertEquals(30, state.firstVisibleItemIndex)
    }

    @Test
    fun `the decision`() {
        assertTrue(shouldReturnToTop(null, null, "b", 30, 12))
        assertTrue(shouldReturnToTop(null, "a", "b", 30, 12))
        assertFalse(shouldReturnToTop(null, "a", "a", 30, 12))
        assertTrue(shouldReturnToTop("a", "a", "b", 0, 0))
        assertFalse(shouldReturnToTop("a", "a", "b", 0, 1))
        assertFalse(shouldReturnToTop("a", "a", "b", 3, 0))
    }
}
