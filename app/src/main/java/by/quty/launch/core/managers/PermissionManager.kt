// *** core/managers/PermissionManager.kt *** //
package by.quty.launch.core.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Менеджер для работы с разрешениями
 * Проверяет и управляет системными разрешениями приложения
 */
object PermissionManager {

    // Обязательные разрешения для работы приложения
    private val REQUIRED_PERMISSIONS = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * Возвращает список обязательных разрешений
     */
    fun getRequiredPermissions(): List<String> {
        return REQUIRED_PERMISSIONS
    }

    /** Проверяет, предоставлены ли все обязательные разрешения */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Проверяет, предоставлено ли конкретное разрешение */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Возвращает список отсутствующих обязательных разрешений */
    fun getMissingRequiredPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}