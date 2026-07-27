// *** core/interfaces/SettingsEventListener.kt *** //
package by.quty.launch.core.interfaces

/**
 * Интерфейс для связи фрагментов настроек с SettingsActivity
 * Позволяет фрагментам сообщать об изменениях настроек
 */
interface SettingsEventListener {
    fun onShellChanged(shellName: String)
    fun onOrientationChanged(orientation: String)
    fun onFullscreenChanged(enabled: Boolean)
    fun onSettingChanged() // Общий метод для любого изменения
}