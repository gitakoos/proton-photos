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

package eu.akoos.photos.presentation.settings.appicon

import eu.akoos.photos.MainHostActivity
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The manifest and the resources are the other half of [AppIcon]; these keep the two from drifting. */
class AppIconManifestTest {

    private val manifest: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml")).documentElement

    private fun Element.attr(name: String): String = getAttribute("android:$name")

    private fun Element.all(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun fullName(name: String): String =
        if (name.startsWith(".")) "eu.akoos.photos$name" else name

    private val aliases = manifest.all("activity-alias")
    private val activities = manifest.all("activity")

    private fun hasLauncher(e: Element): Boolean = e.all("category")
        .any { it.attr("name") == "android.intent.category.LAUNCHER" }

    private fun launcherAliases() = aliases.filter { hasLauncher(it) }

    @Test
    fun every_icon_is_a_launcher_alias_and_nothing_else_is() {
        val manifestNames = launcherAliases().map { fullName(it.attr("name")) }.toSet()
        assertEquals(AppIcon.entries.map { it.aliasName }.toSet(), manifestNames)
        assertEquals(AppIcon.entries.size, launcherAliases().size)
    }

    @Test
    fun exactly_one_alias_ships_enabled_and_it_is_the_default() {
        val enabled = launcherAliases().filter { it.attr("enabled") != "false" }
        assertEquals(1, enabled.size)
        assertEquals(AppIcon.DEFAULT.aliasName, fullName(enabled.single().attr("name")))
    }

    @Test
    fun the_real_activity_is_not_a_launcher_entry_and_is_never_shipped_disabled() {
        assertEquals("eu.akoos.photos.MainHostActivity", MainHostActivity::class.java.name)
        val real = activities.single { fullName(it.attr("name")) == MainHostActivity::class.java.name }
        assertFalse(hasLauncher(real))
        assertTrue(real.attr("enabled") != "false")
    }

    @Test
    fun the_other_entries_are_not_launcher_entries() {
        val picker = aliases.single { fullName(it.attr("name")) == DisguiseEntryPolicy.PICKER_ENTRY }
        assertFalse(hasLauncher(picker))
        val share = activities.single { fullName(it.attr("name")) == DisguiseEntryPolicy.SHARE_ENTRY }
        assertFalse(hasLauncher(share))
        assertNotNull(Class.forName(DisguiseEntryPolicy.SHARE_ENTRY, false, javaClass.classLoader))
    }

    @Test
    fun every_alias_targets_the_real_activity() {
        for (alias in aliases) {
            assertEquals(".MainHostActivity", alias.attr("targetActivity"))
        }
    }

    @Test
    fun every_alias_icon_and_label_resolves() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val mipmapDirs = File("src/main/res").listFiles { f -> f.name.startsWith("mipmap") }.orEmpty()
        for (alias in launcherAliases()) {
            for (attr in listOf("icon", "roundIcon")) {
                val name = alias.attr(attr).removePrefix("@mipmap/")
                val exists = mipmapDirs.any { dir -> dir.listFiles().orEmpty().any { it.nameWithoutExtension == name } }
                assertTrue("${alias.attr("name")} $attr $name", exists)
            }
            val label = alias.attr("label").removePrefix("@string/")
            assertTrue("label $label", strings.contains("name=\"$label\""))
        }
    }

    @Test
    fun the_disguises_carry_their_own_label_and_the_rest_the_app_name() {
        for (icon in AppIcon.entries) {
            val alias = aliases.single { fullName(it.attr("name")) == icon.aliasName }
            val expected = if (icon.isDisguise) "@string/app_icon_label_${icon.key}" else "@string/app_name"
            assertEquals(icon.aliasName, expected, alias.attr("label"))
        }
    }

    @Test
    fun every_adaptive_icon_has_background_foreground_and_monochrome_layers() {
        for (icon in AppIcon.entries) {
            val suffix = if (icon == AppIcon.DEFAULT) "" else "_" + icon.key
            val file = File("src/main/res/mipmap-anydpi-v26/ic_launcher$suffix.xml")
            assertTrue(file.path, file.exists())
            val text = file.readText()
            for (layer in listOf("<background", "<foreground", "<monochrome")) {
                assertTrue("${file.name} $layer", text.contains(layer))
            }
        }
    }

    @Test
    fun every_locale_has_the_new_strings_with_matching_placeholders() {
        val keys = listOf(
            "settings_app_icon", "app_icon_section_preview", "app_icon_section_disguise", "app_icon_disguise_intro",
            "app_icon_cut_entries", "app_icon_cut_entries_desc", "app_icon_disguise_hint", "app_icon_variants_hint",
            "app_icon_black", "app_icon_white", "app_icon_silver", "app_icon_label_clock", "app_icon_label_calculator",
            "app_icon_label_notes", "app_icon_preview_default", "app_icon_preview_disguise", "app_icon_preview_variant",
            "app_icon_confirm_title", "app_icon_confirm_disguise", "app_icon_confirm_variant", "app_icon_confirm_delay",
            "app_icon_readd_hint", "app_icon_confirm_still_visible", "app_icon_confirm_action", "app_icon_changed",
            "app_icon_failed", "app_icon_a11y_disguise_selected", "app_icon_a11y_disguise", "app_icon_a11y_selected",
        )
        fun value(text: String, key: String): String? =
            Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        fun placeholders(s: String) = Regex("%\\d\\$[sd]").findAll(s).map { it.value }.sorted().toList()

        val folders = File("src/main/res").listFiles { f -> f.name == "values" || f.name.matches(Regex("values-[a-z]{2}")) }
            .orEmpty().filter { File(it, "strings.xml").exists() }
        assertEquals(12, folders.size)
        val english = File("src/main/res/values/strings.xml").readText()
        for (dir in folders) {
            val text = File(dir, "strings.xml").readText()
            for (key in keys) {
                val v = value(text, key)
                assertNotNull("${dir.name} $key", v)
                assertEquals("${dir.name} $key", placeholders(value(english, key)!!), placeholders(v!!))
            }
        }
    }
}
