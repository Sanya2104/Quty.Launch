// *** core/utilities/BadgeHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import androidx.core.content.ContextCompat
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import java.util.Locale

/**
 * Утилита для маркеров (badge).
 *
 * Единая точка «семантика + context → HEX» — чтобы не дублировать
 * логику маппинга в GetApps, GetCoreState, SettingsActivity
 * и не держать синхронизацию между colors.xml и константами.
 *
 * Семантика:
 * - "accent" → R.color.status_accent (#009688)
 * - "warning" → R.color.status_warning (#FF9800)
 * - "error" → R.color.status_error (#F44336)
 *
 * На вход подаётся семантика, на выходе — HEX-строка вида "#009688".
 */
object BadgeHelper {

    /**
     * Возвращает HEX-строку для семантического цвета маркера.
     *
     * @param semanticColor "accent" | "warning" | "error" | null
     * @param context контекст для чтения R.color.*
     * @return HEX-строка "#RRGGBB" или null, если семантика не задана
     */
    fun resolveHex(semanticColor: String?, context: Context): String? {
        if (semanticColor.isNullOrEmpty()) return null

        val colorRes = when (semanticColor) {
            CoreConfig.BADGE_COLOR_ACCENT -> R.color.status_accent
            CoreConfig.BADGE_COLOR_WARNING -> R.color.status_warning
            CoreConfig.BADGE_COLOR_ERROR -> R.color.status_error
            else -> return null
        }

        return colorResToHex(colorRes, context)
    }

    /**
     * Возвращает семантический цвет для обновления по флагу isCritical.
     *
     * - isCritical = true  → "error"
     * - isCritical = false → "accent"
     */
    fun resolveUpdateSemantic(isCritical: Boolean): String {
        return if (isCritical) {
            CoreConfig.BADGE_COLOR_ERROR
        } else {
            CoreConfig.BADGE_COLOR_ACCENT
        }
    }

    /**
     * Удобный хелпер: возвращает сразу пару (semantic, hex)
     * для обновления по флагу isCritical.
     */
    fun resolveUpdateBadge(isCritical: Boolean, context: Context): Pair<String, String?> {
        val semantic = resolveUpdateSemantic(isCritical)
        val hex = resolveHex(semantic, context)
        return Pair(semantic, hex)
    }

    /**
     * Конвертирует ресурс цвета в HEX-строку "#RRGGBB".
     *
     * Использует Locale.US — чтобы не было сюрпризов на устройствах
     * с арабской/персидской локалью (где форматирование чисел иначе).
     */
    private fun colorResToHex(colorRes: Int, context: Context): String {
        val color = ContextCompat.getColor(context, colorRes)
        // Берём только RGB, альфу игнорируем (она и так FF для status_*)
        return String.format(Locale.US, "#%06X", color and 0xFFFFFF)
    }
}