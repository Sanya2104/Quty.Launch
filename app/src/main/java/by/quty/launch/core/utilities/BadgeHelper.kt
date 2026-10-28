// *** core/utilities/BadgeHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import androidx.core.content.ContextCompat
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import java.util.Locale

/** Маркеры: семантика → HEX. */
object BadgeHelper {

    /** Семантика ("accent"/"warning"/"error") → HEX "#RRGGBB". */
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

    /** isCritical → "error"/"accent". */
    fun resolveUpdateSemantic(isCritical: Boolean): String {
        return if (isCritical) {
            CoreConfig.BADGE_COLOR_ERROR
        } else {
            CoreConfig.BADGE_COLOR_ACCENT
        }
    }

    /** Пара (semantic, hex) для обновления. */
    fun resolveUpdateBadge(isCritical: Boolean, context: Context): Pair<String, String?> {
        val semantic = resolveUpdateSemantic(isCritical)
        val hex = resolveHex(semantic, context)
        return Pair(semantic, hex)
    }

    private fun colorResToHex(colorRes: Int, context: Context): String {
        val color = ContextCompat.getColor(context, colorRes)
        return String.format(Locale.US, "#%06X", color and 0xFFFFFF)
    }
}