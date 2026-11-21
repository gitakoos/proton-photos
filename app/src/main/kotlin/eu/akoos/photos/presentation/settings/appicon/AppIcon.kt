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

import eu.akoos.photos.R

/**
 * The launcher icons the user can pick from. Each entry is an `activity-alias` of the real main screen
 * in the manifest, so [aliasName] must match the manifest name exactly (a test compares them). Exactly
 * one alias is enabled at a time; [DEFAULT] is the one that ships enabled and keeps the component name
 * an upgrading home screen already stores.
 *
 * The three [Group.Disguise] entries also rename the app on the launcher, so [labelRes] is their own
 * word; every other entry keeps the app's name.
 */
enum class AppIcon(
    val key: String,
    val aliasName: String,
    /** The name the launcher shows under the icon. */
    val labelRes: Int,
    /** The name under the tile in Settings. */
    val tileNameRes: Int,
    /** The adaptive icon the launcher draws for this alias. */
    val iconRes: Int,
    val group: Group,
) {
    CLOCK("clock", "eu.akoos.photos.IconClock", R.string.app_icon_label_clock, R.string.app_icon_label_clock, R.mipmap.ic_launcher_clock, Group.Disguise),
    CALCULATOR("calculator", "eu.akoos.photos.IconCalculator", R.string.app_icon_label_calculator, R.string.app_icon_label_calculator, R.mipmap.ic_launcher_calculator, Group.Disguise),
    NOTES("notes", "eu.akoos.photos.IconNotes", R.string.app_icon_label_notes, R.string.app_icon_label_notes, R.mipmap.ic_launcher_notes, Group.Disguise),

    DEFAULT("default", "eu.akoos.photos.MainActivity", R.string.app_name, R.string.app_icon_default, R.mipmap.ic_launcher, Group.Variant),
    BLACK("black", "eu.akoos.photos.IconBlack", R.string.app_name, R.string.app_icon_black, R.mipmap.ic_launcher_black, Group.Variant),
    WHITE("white", "eu.akoos.photos.IconWhite", R.string.app_name, R.string.app_icon_white, R.mipmap.ic_launcher_white, Group.Variant),
    SILVER("silver", "eu.akoos.photos.IconSilver", R.string.app_name, R.string.app_icon_silver, R.mipmap.ic_launcher_silver, Group.Variant),
    FOREST("forest", "eu.akoos.photos.IconForest", R.string.app_name, R.string.palette_forest, R.mipmap.ic_launcher_forest, Group.Variant),
    SUNSET("sunset", "eu.akoos.photos.IconSunset", R.string.app_name, R.string.palette_sunset, R.mipmap.ic_launcher_sunset, Group.Variant),
    SEA("sea", "eu.akoos.photos.IconSea", R.string.app_name, R.string.palette_sea, R.mipmap.ic_launcher_sea, Group.Variant),
    SEPIA("sepia", "eu.akoos.photos.IconSepia", R.string.app_name, R.string.palette_sepia, R.mipmap.ic_launcher_sepia, Group.Variant),
    LAVENDER("lavender", "eu.akoos.photos.IconLavender", R.string.app_name, R.string.palette_lavender, R.mipmap.ic_launcher_lavender, Group.Variant),
    ROSE("rose", "eu.akoos.photos.IconRose", R.string.app_name, R.string.palette_rose, R.mipmap.ic_launcher_rose, Group.Variant),
    MINT("mint", "eu.akoos.photos.IconMint", R.string.app_name, R.string.palette_mint, R.mipmap.ic_launcher_mint, Group.Variant),
    GOLD("gold", "eu.akoos.photos.IconGold", R.string.app_name, R.string.palette_gold, R.mipmap.ic_launcher_gold, Group.Variant),
    RUBY("ruby", "eu.akoos.photos.IconRuby", R.string.app_name, R.string.palette_ruby, R.mipmap.ic_launcher_ruby, Group.Variant);

    enum class Group { Disguise, Variant }

    val isDisguise: Boolean get() = group == Group.Disguise

    companion object {
        val disguises: List<AppIcon> = entries.filter { it.isDisguise }
        val variants: List<AppIcon> = entries.filter { !it.isDisguise }
    }
}
