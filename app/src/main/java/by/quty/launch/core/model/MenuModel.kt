// *** core/model/MenuModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * Общая модель пункта меню.
 *
 * Используется адаптером SettingsMenuAdapter в двух активностях:
 * - SettingsActivity — меню разделов настроек
 * - LoggerActivity — меню разделов логгера
 *
 * Поля:
 * - id — уникальный идентификатор пункта;
 * - icon — иконка (drawable);
 * - title — заголовок (string resource);
 * - description — описание (string resource);
 * - fragment — фрагмент, который открывается по клику;
 * - iconColor — цвет фона иконки (color resource, 0 = без фона);
 * - applyTint — true = иконка белая, false = оригинальные цвета;
 * - badge — флаг наличия маркера на иконке;
 * - badgeColor — семантика маркера ("accent" | "warning" | "error");
 * - badgeColorHex — HEX-цвет маркера ("#RRGGBB") для прямой подстановки.
 */
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