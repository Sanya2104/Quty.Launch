// *** core/model/ColorSchemeModel.kt *** //
package by.quty.launch.core.model

import android.content.Context
import androidx.annotation.ColorRes
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig

/**
 * Модель цветовой схемы
 * Содержит идентификатор, отображаемое имя, цвета и ссылки на ресурсы
 */
data class ColorSchemeModel(
    val id: String,                     // Уникальный идентификатор (teal, orange, ...)
    val displayNameRes: Int,            // Ресурс отображаемого имени
    val primaryColor: String,           // Primary цвет в HEX (#009688)
    val accentColor: String,            // Accent цвет в HEX (#4CAF50)
    @ColorRes val primaryRes: Int,      // Ресурс primary цвета
    @ColorRes val accentRes: Int        // Ресурс accent цвета
) {
    /**
     * Возвращает отображаемое имя из ресурсов
     */
    fun getDisplayName(context: Context): String {
        return context.getString(displayNameRes)
    }

    companion object {
        /**
         * Возвращает список всех доступных цветовых схем
         */
        fun getAllSchemes(): List<ColorSchemeModel> {
            return listOf(
                ColorSchemeModel(
                    id = "red",
                    displayNameRes = R.string.color_scheme_red,
                    primaryColor = "#F44336",
                    accentColor = "#EF5350",
                    primaryRes = R.color.scheme_red_primary,
                    accentRes = R.color.scheme_red_accent
                ),
                ColorSchemeModel(
                    id = "orange",
                    displayNameRes = R.string.color_scheme_orange,
                    primaryColor = "#FF9800",
                    accentColor = "#FF5722",
                    primaryRes = R.color.scheme_orange_primary,
                    accentRes = R.color.scheme_orange_accent
                ),
                ColorSchemeModel(
                    id = "lime",
                    displayNameRes = R.string.color_scheme_lime,
                    primaryColor = "#CDDC39",
                    accentColor = "#D4E157",
                    primaryRes = R.color.scheme_lime_primary,
                    accentRes = R.color.scheme_lime_accent
                ),
                ColorSchemeModel(
                    id = "green",
                    displayNameRes = R.string.color_scheme_green,
                    primaryColor = "#4CAF50",
                    accentColor = "#8BC34A",
                    primaryRes = R.color.scheme_green_primary,
                    accentRes = R.color.scheme_green_accent
                ),
                ColorSchemeModel(
                    id = "teal",
                    displayNameRes = R.string.color_scheme_teal,
                    primaryColor = "#009688",
                    accentColor = "#4CAF50",
                    primaryRes = R.color.scheme_teal_primary,
                    accentRes = R.color.scheme_teal_accent
                ),
                ColorSchemeModel(
                    id = "cyan",
                    displayNameRes = R.string.color_scheme_cyan,
                    primaryColor = "#00BCD4",
                    accentColor = "#26C6DA",
                    primaryRes = R.color.scheme_cyan_primary,
                    accentRes = R.color.scheme_cyan_accent
                ),
                ColorSchemeModel(
                    id = "blue",
                    displayNameRes = R.string.color_scheme_blue,
                    primaryColor = "#2196F3",
                    accentColor = "#03A9F4",
                    primaryRes = R.color.scheme_blue_primary,
                    accentRes = R.color.scheme_blue_accent
                ),
                ColorSchemeModel(
                    id = "purple",
                    displayNameRes = R.string.color_scheme_purple,
                    primaryColor = "#9C27B0",
                    accentColor = "#E040FB",
                    primaryRes = R.color.scheme_purple_primary,
                    accentRes = R.color.scheme_purple_accent
                ),
                ColorSchemeModel(
                    id = "pink",
                    displayNameRes = R.string.color_scheme_pink,
                    primaryColor = "#E91E63",
                    accentColor = "#F06292",
                    primaryRes = R.color.scheme_pink_primary,
                    accentRes = R.color.scheme_pink_accent
                )
            )
        }

        /**
         * Возвращает схему по ID
         */
        fun getSchemeById(id: String): ColorSchemeModel {
            return getAllSchemes().find { it.id == id } ?: getDefaultScheme()
        }

        /**
         * Возвращает схему по умолчанию (teal)
         */
        fun getDefaultScheme(): ColorSchemeModel {
            return getSchemeById(CoreConfig.DEFAULT_COLOR_SCHEME)
        }
    }
}