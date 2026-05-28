package eu.akoos.photos.util

import eu.akoos.photos.domain.entity.GalleryItem
import java.util.Calendar

/**
 * Groups items whose capture date falls on TODAY's month + day in a PREVIOUS year
 * (the current year is excluded so "On this day" never echoes back today's own photos).
 * Returns a list of (year, items) pairs sorted most-recent-first. An empty list signals
 * "no memories today" — callers hide the carousel/section entirely.
 *
 * Originally lived in [eu.akoos.photos.presentation.gallery.GalleryScreen]; moved here so
 * the same logic backs both the gallery's "On this day" carousel and the search page's
 * empty-state "On this day" row without two copies drifting apart.
 */
fun computeOnThisDay(items: List<GalleryItem>): List<Pair<Int, List<GalleryItem>>> {
    if (items.isEmpty()) return emptyList()
    val today = Calendar.getInstance()
    val todayMonth = today.get(Calendar.MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val todayYear = today.get(Calendar.YEAR)
    val cal = Calendar.getInstance()
    val matches = items.filter { item ->
        cal.timeInMillis = item.captureTimeMs
        cal.get(Calendar.MONTH) == todayMonth &&
            cal.get(Calendar.DAY_OF_MONTH) == todayDay &&
            cal.get(Calendar.YEAR) != todayYear
    }
    if (matches.isEmpty()) return emptyList()
    return matches
        .groupBy { item ->
            cal.timeInMillis = item.captureTimeMs
            cal.get(Calendar.YEAR)
        }
        .entries
        .sortedByDescending { it.key }
        .map { it.key to it.value }
}
