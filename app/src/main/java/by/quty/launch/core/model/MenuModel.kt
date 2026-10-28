// *** core/model/MenuModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/** Пункт меню настроек/логгера/магазина. id < 0 — спейсер. */
data class MenuModel(
    val id: Int,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val fragment: Class<out Fragment>,
    @ColorRes val iconColor: Int = 0,
    val applyTint: Boolean = true,
    val badge: Boolean = false,
    val badgeColor: String? = null,
    val badgeColorHex: String? = null
)