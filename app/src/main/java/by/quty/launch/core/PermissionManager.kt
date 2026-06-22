// core/PermissionManager.kt

package by.quty.launch.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionManager {

    // Обязательные разрешения
    private val REQUIRED_PERMISSIONS = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
    )

    // Опциональные разрешения
    private val OPTIONAL_PERMISSIONS = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    private val ALL_PERMISSIONS = REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun getMissingRequiredPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRequiredProgress(context: Context): Int {
        if (REQUIRED_PERMISSIONS.isEmpty()) return 100
        val granted = REQUIRED_PERMISSIONS.count { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        return (granted * 100) / REQUIRED_PERMISSIONS.size
    }

    fun getPermissionInfo(permission: String): PermissionInfo {
        return when (permission) {
            Manifest.permission.READ_PHONE_STATE -> PermissionInfo(
                id = "phone",
                isRequired = true
            )
            Manifest.permission.ACCESS_NETWORK_STATE -> PermissionInfo(
                id = "network",
                isRequired = true
            )
            Manifest.permission.ACCESS_WIFI_STATE -> PermissionInfo(
                id = "wifi",
                isRequired = true
            )
            Manifest.permission.ACCESS_FINE_LOCATION -> PermissionInfo(
                id = "location",
                isRequired = false
            )
            Manifest.permission.READ_EXTERNAL_STORAGE -> PermissionInfo(
                id = "storage",
                isRequired = false
            )
            else -> PermissionInfo(
                id = "unknown",
                isRequired = false
            )
        }
    }

    data class PermissionInfo(
        val id: String,
        val isRequired: Boolean
    )
}