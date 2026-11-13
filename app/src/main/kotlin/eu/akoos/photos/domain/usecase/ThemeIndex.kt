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

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import eu.akoos.photos.data.semantic.ThemeCacheFiles
import eu.akoos.photos.data.semantic.ThemeCacheKey
import eu.akoos.photos.data.semantic.readThemeAssignments
import eu.akoos.photos.data.semantic.readThemePrompts
import eu.akoos.photos.data.semantic.writeThemeAssignments
import eu.akoos.photos.data.semantic.writeThemePrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Photos classified per read of the embedding table, under the 999 host-variable limit. */
private const val CLASSIFY_BATCH = 900

/** A bulk first classification is saved to disk every this many batches, so an interrupted run keeps
 *  most of its work. */
private const val SAVE_EVERY_BATCHES = 10

/** The embedding count must hold still this long before the index re-syncs, so the indexer writing a
 *  burst of rows triggers one pass rather than one per row. */
private const val RESYNC_SETTLE_MS = 2_000L

/** The locales the app ships names in, so a theme is found by its name in any of them. */
private val THEME_NAME_LOCALES = listOf(
    "en", "cs", "de", "es", "fr", "hu", "it", "nl", "pl", "sk", "sl", "tr",
)

/** The classified library: per category id, the photo keys that carry it, best score first. */
class ThemeSnapshot(val keysByCategory: Map<Int, List<String>>) {
    companion object {
        val EMPTY = ThemeSnapshot(emptyMap())
    }
}

/**
 * The on-device content themes of the library, computed from the CLIP image embeddings the semantic
 * index already stores and the text embeddings of the fixed prompt table in [ThemeCategories].
 *
 * Nothing is re-derived on a screen open. The prompt embeddings are computed once per model and table
 * generation and cached on disk; each photo's classification is cached per account on disk and in memory.
 * A pass compares the stored embedding keys against the classified keys (keys only, no blobs), reads and
 * scores just the new photos in batches of [CLASSIFY_BATCH], and forgets the removed ones, so an unchanged
 * library costs one keys query. A model or table generation bump drops the caches and rebuilds.
 *
 * Guest-safe: the account key is [PhotoLocationEntity.LOCAL_USER] with no account, as the indexer keys it.
 */
@Singleton
class ThemeIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val semanticSearch: SemanticSearchUseCase,
) {
    private val lock = Mutex()
    private val cacheKey = ThemeCacheKey(SEMANTIC_MODEL_VERSION, THEME_TABLE_VERSION)
    private val ids = IntArray(ThemeCategories.ALL.size) { ThemeCategories.ALL[it].id }

    private var promptVectors: ThemePromptVectors? = null
    private var memAccount: String? = null
    private var memEntries: HashMap<String, ThemeAssignment>? = null

    /**
     * The themes of [userId]'s library, emitted once from the cache at once and again after each settled
     * change of the embedding count. Runs off the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(userId: UserId?): Flow<ThemeSnapshot> {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        var first = true
        return imageEmbeddingDao.observeCountForUser(account)
            .distinctUntilChanged()
            .mapLatest {
                if (first) first = false else delay(RESYNC_SETTLE_MS)
                sync(account)
            }
            .flowOn(Dispatchers.Default)
    }

    /** Brings the classification of [account] up to date with the stored embeddings and returns it. */
    internal suspend fun sync(account: String): ThemeSnapshot = lock.withLock {
        withContext(Dispatchers.Default) { snapshotOf(syncLocked(account)) }
    }

    /** The pass itself; the caller holds [lock]. */
    private suspend fun syncLocked(account: String): HashMap<String, ThemeAssignment> {
        val entries = loadEntries(account)
        val prompts = promptVectorsOrNull() ?: return entries
        val stored = imageEmbeddingDao.keysForModel(account, SEMANTIC_MODEL_VERSION)
        val storedSet = HashSet<String>(stored.size * 2).apply { addAll(stored) }
        var changed = entries.keys.removeAll { it !in storedSet }
        val pending = stored.filter { it !in entries }
        var batches = 0
        for (batch in pending.chunked(CLASSIFY_BATCH)) {
            currentCoroutineContext().ensureActive()
            val rows = imageEmbeddingDao.embeddingsForKeys(account, SEMANTIC_MODEL_VERSION, batch)
            for (row in rows) {
                entries[row.photoKey] = classifyTheme(unpackSemanticEmbedding(row.embedding), prompts, ids)
            }
            changed = true
            if (++batches % SAVE_EVERY_BATCHES == 0) saveEntries(account, entries)
        }
        if (changed) saveEntries(account, entries)
        return entries
    }

    /** The in-memory classification for [account], read from the disk cache when this account is not the
     *  one held, or empty when there is none or it is of another generation. */
    private fun loadEntries(account: String): HashMap<String, ThemeAssignment> {
        val held = memEntries
        if (held != null && memAccount == account) return held
        val file = ThemeCacheFiles.assignmentsFile(context, account)
        val read = if (file.exists()) runCatching { file.inputStream().use { readThemeAssignments(it, cacheKey) } }.getOrNull() else null
        return (read ?: HashMap()).also {
            memAccount = account
            memEntries = it
        }
    }

    private fun saveEntries(account: String, entries: Map<String, ThemeAssignment>) {
        runCatching {
            ThemeCacheFiles.writeAtomically(ThemeCacheFiles.assignmentsFile(context, account)) {
                writeThemeAssignments(it, cacheKey, entries)
            }
        }
    }

    /** The embedded prompt table: from memory, else the disk cache, else computed once with the text
     *  encoder and written. Null while the text model is not on the device. */
    private suspend fun promptVectorsOrNull(): ThemePromptVectors? {
        promptVectors?.let { return it }
        val file = ThemeCacheFiles.promptsFile(context)
        val total = ThemeCategories.FLAT_PROMPTS.size
        val flat = (if (file.exists()) runCatching { file.inputStream().use { readThemePrompts(it, cacheKey, total) } }.getOrNull() else null)
            ?: semanticSearch.embedTexts(ThemeCategories.FLAT_PROMPTS)?.also { vectors ->
                runCatching {
                    ThemeCacheFiles.writeAtomically(file) { writeThemePrompts(it, cacheKey, vectors) }
                }
            }
            ?: return null
        var at = 0
        val perCategory = ThemeCategories.ALL.map { c -> List(c.prompts.size) { flat[at++] } }
        return ThemePromptVectors(perCategory).also { promptVectors = it }
    }

    private fun snapshotOf(entries: Map<String, ThemeAssignment>): ThemeSnapshot {
        val scored = HashMap<Int, MutableList<Pair<String, Float>>>()
        for ((key, a) in entries) {
            if (a.first >= 0) scored.getOrPut(a.first) { ArrayList() }.add(key to a.firstScore)
            if (a.second >= 0) scored.getOrPut(a.second) { ArrayList() }.add(key to a.secondScore)
        }
        return ThemeSnapshot(
            scored.mapValues { (_, list) ->
                list.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
                    .map { it.first }
            },
        )
    }

    /** Every category's display name in every shipped locale, folded, for the search match. Built once. */
    val namesById: Map<Int, Set<String>> by lazy { themeNamesInAllLocales(context) }

    /** The photo keys of the category a typed [query] names, best first, or empty when it names none or
     *  nothing is classified. Catches the classification up first (a keys query when nothing changed), so
     *  a search before the Collection was ever opened still finds the theme. */
    suspend fun keysForQuery(query: String, userId: UserId?): List<String> {
        val id = matchThemeCategory(query, namesById) ?: return emptyList()
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        return try {
            lock.withLock {
                withContext(Dispatchers.Default) { categoryKeys(syncLocked(account), id) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }
}

/** The keys carrying category [id] in [entries], best score first. */
internal fun categoryKeys(entries: Map<String, ThemeAssignment>, id: Int): List<String> {
    val hits = ArrayList<Pair<String, Float>>()
    for ((key, a) in entries) {
        if (a.first == id) hits.add(key to a.firstScore) else if (a.second == id) hits.add(key to a.secondScore)
    }
    return hits.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first }).map { it.first }
}

/** Display names of every theme in the shipped locales, folded by [foldThemeText], keyed by category id. */
internal fun themeNamesInAllLocales(context: Context): Map<Int, Set<String>> {
    val perLocale = THEME_NAME_LOCALES.map { tag ->
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        context.createConfigurationContext(config)
    }
    return ThemeCategories.ALL.associate { c ->
        c.id to perLocale.map { foldThemeText(it.getString(c.nameRes)) }.toSet()
    }
}
