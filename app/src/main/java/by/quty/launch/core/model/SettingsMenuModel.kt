// *** core/model/SettingsMenuModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * Модель пункта меню для настроек
 * Используется в SettingsMenuAdapter для отображения списка
 */
data class SettingsMenuModel(
    val id: Int,                        // Уникальный идентификатор пункта
    @DrawableRes val icon: Int,         // Иконка (drawable resource)
    @StringRes val title: Int,          // Заголовок (string resource)
    @StringRes val description: Int,    // Описание (string resource)
    val fragment: Class<out Fragment>,  // Класс фрагмента для открытия
    @ColorRes val iconColor: Int = 0,   // Цвет заливки иконки
    val applyTint: Boolean = true       // true = иконка белая, false = оригинальные цвета
)