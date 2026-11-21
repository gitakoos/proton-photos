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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Reads and writes the enabled state of manifest components by class name. A seam over
 * [PackageManager] so the switching order and the repair rules can be tested without a device.
 */
interface ComponentSwitch {
    /** One of the `PackageManager.COMPONENT_ENABLED_STATE_*` values. */
    fun state(className: String): Int
    fun set(className: String, state: Int)
}

/** The real thing: the system holds the state, `DONT_KILL_APP` keeps the running app alive. */
class PackageManagerSwitch(private val context: Context) : ComponentSwitch {
    override fun state(className: String): Int =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, className))

    override fun set(className: String, state: Int) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, className),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * Switches the launcher icon. The system is the single source of truth, as with the external picker
 * entry: nothing is copied anywhere, so a failed call or a cleared data folder cannot make a copy
 * disagree with what the launcher shows.
 *
 * A component in the `DEFAULT` state follows the manifest, where only [AppIcon.DEFAULT] ships enabled,
 * so [isEnabled] reads that rule rather than asking the launcher. The real main screen is never touched
 * here: the widget, the EDIT and VIEW filters and the gallery filter live on it.
 */
class AppIconManager(private val switch: ComponentSwitch) {

    constructor(context: Context) : this(PackageManagerSwitch(context))

    private fun isEnabled(icon: AppIcon): Boolean = when (switch.state(icon.aliasName)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
        else -> false
    }

    private fun enabledIcons(): List<AppIcon> = AppIcon.entries.filter { isEnabled(it) }

    /** The icon the launcher shows now. [AppIcon.DEFAULT] when none or several are enabled. */
    fun current(): AppIcon {
        val enabled = enabledIcons()
        if (enabled.size != 1) {
            Log.w(TAG, "expected one enabled launcher alias, found ${enabled.size}")
            return AppIcon.DEFAULT
        }
        return enabled.first()
    }

    /**
     * Makes [icon] the one launcher entry. The chosen alias is enabled first and the others disabled
     * after, so a process that dies half-way leaves two icons for a moment and never none. Choosing
     * [AppIcon.DEFAULT] puts every alias back on its manifest state, the state of a fresh install.
     * Returns false when the system refused a call.
     */
    fun apply(icon: AppIcon): Boolean = try {
        val target = if (icon == AppIcon.DEFAULT) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        setIfChanged(icon, target)
        val rest = if (icon == AppIcon.DEFAULT) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for (other in AppIcon.entries) if (other != icon) setIfChanged(other, rest)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "icon switch failed: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Repairs a state that is not exactly one enabled alias, which a crash between two calls or a
     * data clear could leave: none enables [AppIcon.DEFAULT], several keep the first and disable the
     * rest. Does nothing when the state is already right. Returns false when the system refused a call.
     */
    fun reconcile(): Boolean = try {
        val enabled = enabledIcons()
        when {
            enabled.size == 1 -> Unit
            enabled.isEmpty() -> switch.set(AppIcon.DEFAULT.aliasName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            else -> for (extra in enabled.drop(1)) {
                switch.set(extra.aliasName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            }
        }
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "icon reconcile failed: ${e.javaClass.simpleName}")
        false
    }

    /** Each call is a package-changed broadcast, so a state that is already right is left alone. */
    private fun setIfChanged(icon: AppIcon, state: Int) {
        if (switch.state(icon.aliasName) != state) switch.set(icon.aliasName, state)
    }

    private companion object {
        const val TAG = "AppIconManager"
    }
}
