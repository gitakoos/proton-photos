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

package eu.akoos.photos.data.autosave

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The two Drive copies that can be kept up to date on their own. */
enum class AutoSaveKind { ML, LABELS }

/** When an automatic save runs. [AFTER_CHANGES] waits for a quiet spell after the last change; the other two
 *  run at a fixed time of day, on every day or on Sunday only. */
enum class AutoSaveFrequency { AFTER_CHANGES, DAILY, WEEKLY }

/** How long nothing may change before a save after changes starts. */
internal const val AUTO_SAVE_QUIET_MS = 10L * 60_000L

/** The shortest gap between the starts of two saves after changes. */
internal const val AUTO_SAVE_MIN_GAP_MS = 60L * 60_000L

/** How long a failed save waits before the next try. */
internal const val AUTO_SAVE_FAILURE_RETRY_MS = 30L * 60_000L

/** How long to wait when another save is already running. */
internal const val AUTO_SAVE_BUSY_RETRY_MS = 15L * 60_000L

/** The shortest delay a check is scheduled with, so a check never fires in a tight loop. */
internal const val AUTO_SAVE_MIN_WAIT_MS = 60_000L

private const val SLOT_HOUR = 23
private const val SLOT_MINUTE = 59

/**
 * What an automatic save knows about the data and its own history, all in wall-clock milliseconds, 0 meaning
 * never. [dirtySinceMs] is when the first change that no save has covered yet happened (0 = nothing waits),
 * [lastChangeMs] when the latest one did, [lastSaveStartMs] when the last successful save began, and
 * [lastFailureMs] when the last save ended without finishing.
 */
internal data class AutoSaveClock(
    val dirtySinceMs: Long,
    val lastChangeMs: Long,
    val lastSaveStartMs: Long,
    val lastFailureMs: Long,
)

/** What to do about an automatic save right now. */
internal sealed interface AutoSaveDecision {
    /** Nothing changed since the last save. */
    data object Idle : AutoSaveDecision

    /** Start a save now. */
    data object Run : AutoSaveDecision

    /** Check again after [delayMs]. */
    data class Wait(val delayMs: Long) : AutoSaveDecision
}

/**
 * The automatic save's whole timing rule. A save only runs when something changed since the last one
 * ([AutoSaveClock.dirtySinceMs]), so an unchanged copy is never uploaded again as a new revision.
 *
 * [AutoSaveFrequency.AFTER_CHANGES] runs [AUTO_SAVE_QUIET_MS] after the latest change and at least
 * [AUTO_SAVE_MIN_GAP_MS] after the last save began. The daily and weekly kinds run at 23:59 local time (every
 * day, or on Sunday); a change that came before the latest such moment and was never saved, because the phone
 * was off or the check was held back, is saved at once instead of waiting for the next one. After a failed save
 * every kind waits [AUTO_SAVE_FAILURE_RETRY_MS] before it tries again. Pure so it is unit-tested without Android.
 */
internal fun decideAutoSave(
    frequency: AutoSaveFrequency,
    clock: AutoSaveClock,
    nowMs: Long,
    zone: ZoneId,
): AutoSaveDecision {
    if (clock.dirtySinceMs == 0L) return AutoSaveDecision.Idle
    val retryAt = if (clock.lastFailureMs > clock.lastSaveStartMs) clock.lastFailureMs + AUTO_SAVE_FAILURE_RETRY_MS else 0L
    val dueAt = when (frequency) {
        AutoSaveFrequency.AFTER_CHANGES -> maxOf(
            clock.lastChangeMs + AUTO_SAVE_QUIET_MS,
            if (clock.lastSaveStartMs > 0L) clock.lastSaveStartMs + AUTO_SAVE_MIN_GAP_MS else 0L,
        )
        AutoSaveFrequency.DAILY, AutoSaveFrequency.WEEKLY -> {
            val last = lastSlotAtOrBefore(frequency, nowMs, zone)
            if (last > 0L && clock.dirtySinceMs <= last) nowMs else nextSlotAfter(frequency, nowMs, zone)
        }
    }
    val wait = maxOf(dueAt, retryAt) - nowMs
    return if (wait <= 0L) AutoSaveDecision.Run else AutoSaveDecision.Wait(wait)
}

/**
 * When the first unsaved change happened after a save that began at [saveStartMs], given the latest change
 * [lastChangeMs]: 0 when nothing changed since the save began, otherwise just after it began, as the save
 * may or may not hold a change that came while it ran. Pure so it is unit-tested without Android.
 */
internal fun dirtySinceAfterSave(lastChangeMs: Long, saveStartMs: Long): Long =
    if (lastChangeMs > saveStartMs) saveStartMs + 1L else 0L

/** The first moment after [nowMs] a daily or weekly save is due: 23:59 local time. */
internal fun nextSlotAfter(frequency: AutoSaveFrequency, nowMs: Long, zone: ZoneId): Long {
    var day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    repeat(SLOT_SEARCH_DAYS) {
        if (isSlotDay(frequency, day)) {
            val slot = slotMs(day, zone)
            if (slot > nowMs) return slot
        }
        day = day.plusDays(1)
    }
    return nowMs + 24L * 3_600_000L
}

/** The latest moment at or before [nowMs] a daily or weekly save was due, or 0 when there is none in reach. */
internal fun lastSlotAtOrBefore(frequency: AutoSaveFrequency, nowMs: Long, zone: ZoneId): Long {
    var day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    repeat(SLOT_SEARCH_DAYS) {
        if (isSlotDay(frequency, day)) {
            val slot = slotMs(day, zone)
            if (slot <= nowMs) return slot
        }
        day = day.minusDays(1)
    }
    return 0L
}

private const val SLOT_SEARCH_DAYS = 9

private fun isSlotDay(frequency: AutoSaveFrequency, day: LocalDate): Boolean =
    frequency == AutoSaveFrequency.DAILY || (frequency == AutoSaveFrequency.WEEKLY && day.dayOfWeek == DayOfWeek.SUNDAY)

private fun slotMs(day: LocalDate, zone: ZoneId): Long =
    day.atTime(SLOT_HOUR, SLOT_MINUTE).atZone(zone).toInstant().toEpochMilli()
