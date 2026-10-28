// *** core/utilities/AppInfoHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** versionName / versionCode / split version + suffix. */
object AppInfoHelper {

    /** Возвращает versionName приложения ("" при ошибке). */
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

    /** Возвращает versionCode приложения (0 при ошибке). */
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

    /** "0.0.145-Alpha" > Pair("0.0.145", "Alpha"). */
    fun splitVersionName(fullVersionName: String): Pair<String, String> {
        if (fullVersionName.isEmpty()) {
            return Pair("", "")
        }

        val separators = listOf("-", "_", " ")
        for (separator in separators) {
            val index = fullVersionName.indexOf(separator)
            if (index > 0 && index < fullVersionName.length - 1) {
                val version = fullVersionName.substring(0, index)
                val suffix = fullVersionName.substring(index + 1)
                return Pair(version, suffix)
            }
        }

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

    /** Возвращает (versionName без суффикса, суффикс) приложения. */
    fun getSplitVersion(context: Context): Pair<String, String> {
        return splitVersionName(getVersionName(context))
    }
}