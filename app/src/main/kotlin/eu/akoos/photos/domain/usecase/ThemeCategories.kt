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

package eu.akoos.photos.domain.usecase

import androidx.annotation.StringRes
import eu.akoos.photos.R

/**
 * Generation of the theme prompt table below. Raise it whenever a prompt, a category or its id changes:
 * the cached prompt embeddings and every cached classification are then dropped and rebuilt, so a stale
 * assignment never outlives the table that produced it.
 */
const val THEME_TABLE_VERSION = 1

/** A photo's best category needs at least this cosine against one of the category's prompts. The value is
 *  a starting point for this CLIP model and wants tuning against a real library. */
const val THEME_TOP_MIN_SCORE = 0.20f

/** A second category needs at least this cosine (higher than the top rule, since two themes on one photo
 *  is a stronger claim). */
const val THEME_SECOND_MIN_SCORE = 0.235f

/** A second category must also sit within this margin of the best one. */
const val THEME_SECOND_MARGIN = 0.015f

/**
 * One content theme: a stable [id] (the persisted key, never reused), the string resource of its display
 * name, and the text [prompts] whose embeddings stand for it. A photo scores against a category as the
 * best cosine over that category's prompts.
 */
class ThemeCategory(val id: Int, @StringRes val nameRes: Int, val prompts: List<String>)

/** The theme table: twenty everyday categories, a few prompts each. */
object ThemeCategories {

    val ALL: List<ThemeCategory> = listOf(
        ThemeCategory(0, R.string.content_theme_food, listOf("a photo of food", "a photo of a meal on a plate", "a photo of a dish at a restaurant")),
        ThemeCategory(1, R.string.content_theme_pets, listOf("a photo of a dog", "a photo of a cat", "a photo of a pet")),
        ThemeCategory(2, R.string.content_theme_animals, listOf("a photo of a wild animal", "a photo of a bird", "a photo of farm animals")),
        ThemeCategory(3, R.string.content_theme_beach, listOf("a photo of a beach", "a photo of the sea", "a photo of people swimming")),
        ThemeCategory(4, R.string.content_theme_mountains, listOf("a photo of mountains", "a photo of hiking in the mountains")),
        ThemeCategory(5, R.string.content_theme_nature, listOf("a photo of a forest", "a photo of a landscape", "a photo of a lake")),
        ThemeCategory(6, R.string.content_theme_flowers, listOf("a photo of flowers", "a photo of a garden")),
        ThemeCategory(7, R.string.content_theme_city, listOf("a photo of a city street", "a photo of buildings", "a photo of a town square")),
        ThemeCategory(8, R.string.content_theme_night, listOf("a photo taken at night", "a photo of city lights at night")),
        ThemeCategory(9, R.string.content_theme_sunsets, listOf("a photo of a sunset", "a photo of a sunrise")),
        ThemeCategory(10, R.string.content_theme_sky, listOf("a photo of clouds in the sky", "a photo of a blue sky with clouds", "a photo of a cloudy sky")),
        ThemeCategory(11, R.string.content_theme_snow, listOf("a photo of snow", "a photo of winter")),
        ThemeCategory(12, R.string.content_theme_portraits, listOf("a portrait photo of a person", "a close-up photo of a face")),
        ThemeCategory(13, R.string.content_theme_group_photos, listOf("a group photo of people", "a photo of a crowd")),
        ThemeCategory(14, R.string.content_theme_celebrations, listOf("a photo of a birthday party", "a photo of a wedding", "a photo of a celebration")),
        ThemeCategory(15, R.string.content_theme_sports, listOf("a photo of people playing sports", "a photo of a football match")),
        ThemeCategory(16, R.string.content_theme_cars, listOf("a photo of a car", "a photo of vehicles")),
        ThemeCategory(17, R.string.content_theme_documents, listOf("a photo of a document", "a photo of a receipt", "a photo of text on paper")),
        ThemeCategory(18, R.string.content_theme_screenshots, listOf("a screenshot of a phone screen", "a screenshot of a website")),
        ThemeCategory(19, R.string.content_theme_drawings, listOf("a cartoon drawing", "an illustration", "a drawing")),
    )

    /** Every prompt of every category, flattened in table order: the order the cached prompt embeddings
     *  are stored in. */
    val FLAT_PROMPTS: List<String> = ALL.flatMap { it.prompts }

    fun byId(id: Int): ThemeCategory? = ALL.firstOrNull { it.id == id }
}

/**
 * The 0 to 2 categories one photo carries: [first] with [firstScore], then an optional [second] (-1 for
 * none). A photo that clears no threshold is [NONE]; it is still recorded so it is not re-scored.
 */
data class ThemeAssignment(
    val first: Int,
    val firstScore: Float,
    val second: Int = -1,
    val secondScore: Float = 0f,
) {
    companion object {
        val NONE = ThemeAssignment(-1, 0f)
    }
}

/** The embedded prompts, one list of vectors per category, in [ThemeCategories.ALL] order. */
class ThemePromptVectors(val perCategory: List<List<FloatArray>>)

/**
 * Assigns a photo its categories from its per-category [scores] (index = position in [ids], the category
 * ids; a category with no usable prompt scores -1). The best category
 * counts when its score reaches [THEME_TOP_MIN_SCORE]; one second category counts when it reaches
 * [THEME_SECOND_MIN_SCORE] and sits within [THEME_SECOND_MARGIN] of the best. Ties go to the lowest
 * index, so the result is deterministic. Pure.
 */
internal fun classifyThemeScores(scores: FloatArray, ids: IntArray = IntArray(scores.size) { it }): ThemeAssignment {
    var bestIdx = -1
    for (i in scores.indices) if (bestIdx < 0 || scores[i] > scores[bestIdx]) bestIdx = i
    if (bestIdx < 0 || scores[bestIdx] < THEME_TOP_MIN_SCORE) return ThemeAssignment.NONE
    val best = scores[bestIdx]
    var secondIdx = -1
    for (i in scores.indices) {
        if (i == bestIdx) continue
        val s = scores[i]
        if (s < THEME_SECOND_MIN_SCORE || s < best - THEME_SECOND_MARGIN) continue
        if (secondIdx < 0 || s > scores[secondIdx]) secondIdx = i
    }
    return if (secondIdx < 0) ThemeAssignment(ids[bestIdx], best)
    else ThemeAssignment(ids[bestIdx], best, ids[secondIdx], scores[secondIdx])
}

/** [classifyThemeScores] for one photo [vector] against the embedded [prompts]: each category scores as
 *  its best prompt cosine. A vector of the wrong width, or a zero tombstone, lands in [ThemeAssignment.NONE]. */
internal fun classifyTheme(vector: FloatArray, prompts: ThemePromptVectors, ids: IntArray): ThemeAssignment {
    val scores = FloatArray(prompts.perCategory.size) { c ->
        var best = -1f
        for (p in prompts.perCategory[c]) {
            if (p.size != vector.size) continue
            val s = cosineSimilarity(p, vector)
            if (s > best) best = s
        }
        best
    }
    return classifyThemeScores(scores, ids)
}

/** Lowercased, diacritic-folded form a typed query and a category name are compared in. */
internal fun foldThemeText(s: String): String =
    java.text.Normalizer.normalize(s.trim().lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

/**
 * The category a typed [query] names, or null. [namesById] holds each category's display names in every
 * locale, already folded by [foldThemeText]. A query matches a name exactly or as a simple plural of it
 * ("beach", "beaches"; "flower", "flowers"); anything under three characters never matches, so a stray
 * letter does not jump to a theme. Pure.
 */
internal fun matchThemeCategory(query: String, namesById: Map<Int, Set<String>>): Int? {
    val q = foldThemeText(query)
    if (q.length < 3) return null
    for ((id, names) in namesById) {
        for (n in names) {
            if (q == n || q + "s" == n || q == n + "s" || q == n + "es") return id
            if (n.length > 1 && n.endsWith("s") && q == n.dropLast(1)) return id
        }
    }
    return null
}
