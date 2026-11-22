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

package eu.akoos.photos.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.util.SyncDiagnostics
import eu.akoos.photos.worker.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The choices an account made on this phone that a sign-out wipes ([AccountScopedPreferences]) and the same
 * account signing in again should find: which folders back up and how, which of them become Drive albums,
 * which folders the timeline leaves out, and how the timeline and the album list look. The wipe sets them
 * aside under the account's id; that account's next sign-in takes them back, and no other account reads
 * them. A folder that is not on the phone any more comes back as a name that matches no photo.
 */
internal object AccountChoiceStash {

    /** The group of the folders the backup takes and the albums they become. */
    const val BACKUP = "backup folders"
    const val TIMELINE_FOLDERS = "timeline folders"
    const val VIEWS = "views"

    private const val KEY_PREFIX = "account_choices_"

    private class Choice<T : Any>(
        val key: Preferences.Key<T>,
        private val toJson: (T) -> JsonElement,
        private val fromJson: (JsonElement) -> T?,
    ) {
        fun saved(prefs: Preferences): JsonElement? = prefs[key]?.let(toJson)

        /** Writes [json] back; false when it is not a value of this choice. */
        fun restore(prefs: MutablePreferences, json: JsonElement): Boolean {
            val value = fromJson(json) ?: return false
            prefs[key] = value
            return true
        }
    }

    private class Group(val name: String, val choices: List<Choice<*>>)

    private fun flag(key: Preferences.Key<Boolean>) = Choice(key, ::JsonPrimitive) { json ->
        (json as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }

    private fun number(key: Preferences.Key<Int>) = Choice(key, ::JsonPrimitive) { json ->
        (json as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }

    private fun names(key: Preferences.Key<Set<String>>) = Choice(
        key,
        toJson = { set -> JsonArray(set.sorted().map(::JsonPrimitive)) },
        fromJson = ::namesOf,
    )

    private fun namesOf(json: JsonElement): Set<String>? {
        val items = (json as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
        return if (null in items) null else items.filterNotNull().toSet()
    }

    /**
     * Each group comes back whole or not at all: the phone holding any of a group's settings means the
     * account chose again since it signed in, and a half-restored backup choice could widen what uploads.
     */
    private val groups = listOf(
        Group(
            BACKUP,
            listOf(
                names(SettingsKeys.SYNC_FOLDER_NAMES),
                flag(SettingsKeys.BACKUP_EVERYTHING),
                names(SettingsKeys.EXCLUDED_FOLDER_NAMES),
                names(SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES),
                names(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES),
                names(SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES),
                flag(SettingsKeys.ALBUM_ALL_BY_DEFAULT),
            ),
        ),
        Group(TIMELINE_FOLDERS, listOf(names(SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES))),
        Group(
            VIEWS,
            listOf(
                flag(SettingsKeys.SHOW_SCROLL_DATE),
                flag(SettingsKeys.REVERSE_TIMELINE_ORDER),
                flag(SettingsKeys.MOSAIC_GRID),
                flag(SettingsKeys.SEAMLESS_GRID),
                number(SettingsKeys.ALBUMS_DEFAULT_FILTER),
                flag(SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER),
                number(SettingsKeys.ALBUMS_LAST_FILTER),
            ),
        ),
    )

    /** Every setting set aside. */
    val keys: Set<Preferences.Key<*>> = groups.flatMap { group -> group.choices.map { it.key } }.toSet()

    fun keyFor(userId: UserId): Preferences.Key<String> = stringPreferencesKey(KEY_PREFIX + userId.id)

    /**
     * Sets [userId]'s choices in [prefs] aside under its id. A group the phone holds none of keeps what was set
     * aside before, so a second wipe of the same sign-out, or the replay of one cut short, keeps the choices.
     */
    fun setAsideIn(prefs: MutablePreferences, userId: UserId) {
        val before = prefs[keyFor(userId)]?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
        val saved = LinkedHashMap<String, JsonElement>()
        for (group in groups) {
            val heldNow = group.choices.any { it.key in prefs }
            for (choice in group.choices) {
                val value = if (heldNow) choice.saved(prefs) else before?.get(choice.key.name)
                if (value != null) saved[choice.key.name] = value
            }
        }
        if (saved.isEmpty()) prefs.remove(keyFor(userId)) else prefs[keyFor(userId)] = JsonObject(saved).toString()
    }

    /**
     * Writes back what [userId] set aside, each group only where [prefs] holds none of its settings, and drops
     * the set-aside copy either way. Returns the names of the groups that came back.
     */
    fun takeBackIn(prefs: MutablePreferences, userId: UserId): Set<String> {
        val raw = prefs[keyFor(userId)] ?: return emptySet()
        prefs.remove(keyFor(userId))
        val saved = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptySet()
        val back = LinkedHashSet<String>()
        for (group in groups) {
            if (group.choices.any { it.key in prefs }) continue
            var wrote = false
            for (choice in group.choices) {
                val json = saved[choice.key.name] ?: continue
                if (choice.restore(prefs, json)) wrote = true
            }
            if (wrote) back += group.name
        }
        return back
    }
}

/** Gives every account that signs in, or is signed in when the app starts, what its last sign-out set aside. */
@Singleton
class AccountChoiceRestore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    @AppScope private val appScope: CoroutineScope,
) {
    /** Called once, from the main process. */
    fun start() {
        appScope.launch {
            // Distinct before the nulls go, so the same account signing back in after a sign-out counts again.
            accountManager.getPrimaryUserId().distinctUntilChanged().collectLatest { userId ->
                if (userId == null) return@collectLatest
                try {
                    // A sign-in that gives up before it is ready must not consume the choices.
                    accountManager.getAccount(userId).first { it?.state == AccountState.Ready }
                    takeBack(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "choices not taken back: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun takeBack(userId: UserId) {
        var back = emptySet<String>()
        context.settingsDataStoreStrict.edit { back = AccountChoiceStash.takeBackIn(it, userId) }
        if (back.isEmpty()) return
        SyncDiagnostics.log("sign-in: choices set aside at sign-out taken back: ${back.joinToString()}")
        if (AccountChoiceStash.BACKUP in back) SyncWorker.reconcileBackgroundWork(context)
    }

    private companion object {
        const val TAG = "AccountChoiceRestore"
    }
}
