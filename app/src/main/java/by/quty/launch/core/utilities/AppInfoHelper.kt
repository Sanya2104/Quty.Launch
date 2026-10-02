// *** core/utilities/AppInfoHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Утилита для получения информации о приложении.
 * Используется в разных местах (Центр обновления, О системе и т.д.),
 * чтобы не дублировать логику работы с PackageManager.
 */
object AppInfoHelper {

    /**
     * Возвращает versionName приложения (полный, с суффиксом).
     * Например: "0.0.145-Alpha".
     */
    fun getVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Возвращает versionCode приложения.
     */
    fun getVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.longVersionCode
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Разбивает versionName на версию и суффикс (канал).
     * Например, "0.0.145-Alpha" → Pair("0.0.145", "Alpha").
     * Если суффикса нет — второй элемент пустой строкой.
     */
    fun splitVersionName(fullVersionName: String): Pair<String, String> {
        if (fullVersionName.isEmpty()) {
            return Pair("", "")
        }

        // Ищем разделитель
        val separators = listOf("-", "_", " ")
        for (separator in separators) {
            val index = fullVersionName.indexOf(separator)
            if (index > 0 && index < fullVersionName.length - 1) {
                val version = fullVersionName.substring(0, index)
                val suffix = fullVersionName.substring(index + 1)
                return Pair(version, suffix)
            }
        }

        // Fallback — регулярка по цифрам
        val digitRegex = Regex("^[\\d.]+")
        val match = digitRegex.find(fullVersionName)
        if (match != null) {
            val version = match.value
            val suffix = fullVersionName.substring(version.length)
            if (suffix.isNotEmpty()) {
                return Pair(version, suffix)
            }
        }

        return Pair(fullVersionName, "")
    }

    /**
     * Возвращает пару (версия без суффикса, суффикс/канал).
     * Удобно для отображения: отдельно версия, отдельно канал.
     */
    fun getSplitVersion(context: Context): Pair<String, String> {
        return splitVersionName(getVersionName(context))
    }
}