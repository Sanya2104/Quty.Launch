// *** core/model/ColorScheme.kt *** //
package by.quty.launch.core.model

import androidx.annotation.ColorRes
import by.quty.launch.R

/**
 * Модель цветовой схемы
 * Содержит идентификатор, отображаемое имя, цвета и ссылки на ресурсы
 */
data class ColorScheme(
    val id: String,                     // Уникальный идентификатор (teal, orange, ...)
    val displayName: String,            // Отображаемое имя
    val primaryColor: String,           // Primary цвет в HEX (#009688)
    val accentColor: String,            // Accent цвет в HEX (#4CAF50)
    @ColorRes val primaryRes: Int,      // Ресурс primary цвета
    @ColorRes val accentRes: Int        // Ресурс accent цвета
) {
    companion object {
        /**
         * Возвращает список всех доступных цветовых схем
         */
        fun getAllSchemes(): List<ColorScheme> {
            return listOf(
                ColorScheme(
                    id = "teal",
                    displayName = "Бирюзовый",
                    primaryColor = "#009688",
                    accentColor = "#4CAF50",
                    primaryRes = R.color.scheme_teal_primary,
                    accentRes = R.color.scheme_teal_accent
                ),
                ColorScheme(
                    id = "orange",
                    displayName = "Оранжевый",
                    primaryColor = "#FF9800",
                    accentColor = "#FF5722",
                    primaryRes = R.color.scheme_orange_primary,
                    accentRes = R.color.scheme_orange_accent
                ),
                ColorScheme(
                    id = "purple",
                    displayName = "Фиолетовый",
                    primaryColor = "#9C27B0",
                    accentColor = "#E040FB",
                    primaryRes = R.color.scheme_purple_primary,
                    accentRes = R.color.scheme_purple_accent
                ),
                ColorScheme(
                    id = "pink",
                    displayName = "Розовый",
                    primaryColor = "#E91E63",
                    accentColor = "#F06292",
                    primaryRes = R.color.scheme_pink_primary,
                    accentRes = R.color.scheme_pink_accent
                ),
                ColorScheme(
                    id = "blue",
                    displayName = "Синий",
                    primaryColor = "#2196F3",
                    accentColor = "#03A9F4",
                    primaryRes = R.color.scheme_blue_primary,
                    accentRes = R.color.scheme_blue_accent
                ),
                ColorScheme(
                    id = "green",
                    displayName = "Зелёный",
                    primaryColor = "#4CAF50",
                    accentColor = "#8BC34A",
                    primaryRes = R.color.scheme_green_primary,
                    accentRes = R.color.scheme_green_accent
                ),
                ColorScheme(
                    id = "red",
                    displayName = "Красный",
                    primaryColor = "#F44336",
                    accentColor = "#EF5350",
                    primaryRes = R.color.scheme_red_primary,
                    accentRes = R.color.scheme_red_accent
                )
            )
        }

        /**
         * Возвращает схему по ID или схему по умолчанию
         */
        fun getSchemeById(id: String): ColorScheme {
            return getAllSchemes().find { it.id == id } ?: getAllSchemes().first()
        }

        /**
         * Возвращает схему по умолчанию (teal)
         */
        fun getDefaultScheme(): ColorScheme {
            return getAllSchemes().first()
        }
    }
}