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

package eu.akoos.photos.widget

import android.appwidget.AppWidgetManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which widget ids the exported configuration activity and receiver act on: only an id the host
 * binds to this app's own receiver. Runs without a device: a lookup stands in for the host's answer.
 */
class WidgetIdGuardTest {

    private val ownPackage = "eu.akoos.photos"
    private val ownReceiver = "eu.akoos.photos.widget.PhotoWidgetReceiver"
    private val ours = WidgetProviderName(ownPackage, ownReceiver)

    // ── the provider comparison ──────────────────────────────────────────────────────────────────

    @Test
    fun `this app's own receiver is ours`() {
        assertTrue(isOwnWidget(ownPackage, ownReceiver, ownPackage, ownReceiver))
    }

    @Test
    fun `another package with the same receiver name is not ours`() {
        assertFalse(isOwnWidget("com.example.other", ownReceiver, ownPackage, ownReceiver))
    }

    @Test
    fun `another receiver class in this package is not ours`() {
        assertFalse(isOwnWidget(ownPackage, "eu.akoos.photos.widget.OtherReceiver", ownPackage, ownReceiver))
    }

    @Test
    fun `no provider info is not ours`() {
        assertFalse(isOwnWidget(null, null, ownPackage, ownReceiver))
        assertFalse(isOwnWidget(ownPackage, null, ownPackage, ownReceiver))
        assertFalse(isOwnWidget(null, ownReceiver, ownPackage, ownReceiver))
    }

    // ── the id check ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an id bound to this app's receiver is ours`() {
        assertTrue(isOwnWidgetId(42, ownPackage, ownReceiver) { ours })
    }

    @Test
    fun `the invalid id is refused without asking the host`() {
        var asked = false
        val own = isOwnWidgetId(AppWidgetManager.INVALID_APPWIDGET_ID, ownPackage, ownReceiver) {
            asked = true
            ours
        }
        assertFalse(own)
        assertFalse(asked)
    }

    @Test
    fun `an id the host holds no widget for is not ours`() {
        assertFalse(isOwnWidgetId(42, ownPackage, ownReceiver) { null })
    }

    @Test
    fun `an id bound to another app's widget is not ours`() {
        assertFalse(
            isOwnWidgetId(42, ownPackage, ownReceiver) {
                WidgetProviderName("com.example.other", "com.example.other.ClockWidget")
            },
        )
    }

    // ── which ids each entry acts on ─────────────────────────────────────────────────────────────

    @Test
    fun `an update keeps only this app's own widgets, in order`() {
        val own = setOf(3, 7)
        assertArrayEquals(intArrayOf(3, 7), ownWidgetIds(intArrayOf(3, 5, 7, 9)) { it in own })
    }

    @Test
    fun `a delete cleans up only ids the host no longer binds here`() {
        val stillBound = setOf(5)
        assertArrayEquals(
            intArrayOf(3, 7),
            deletedWidgetIds(intArrayOf(3, 5, 7, AppWidgetManager.INVALID_APPWIDGET_ID)) { it in stillBound },
        )
    }

    @Test
    fun `a delete naming only live widgets cleans up nothing`() {
        assertArrayEquals(intArrayOf(), deletedWidgetIds(intArrayOf(4, 8)) { true })
    }
}
