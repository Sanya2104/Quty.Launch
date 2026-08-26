// *** core/interfaces/ParametersEventListener.kt *** //
package by.quty.launch.core.interfaces

/**
 * Интерфейс для связи фрагментов параметров с ParametersActivity
 * Позволяет фрагментам сообщать об изменениях настроек
 */
interface ParametersEventListener {
    fun onShellChanged(shellName: String)
    fun onOrientationChanged(orientation: String)
    fun onFullscreenChanged(enabled: Boolean)
    fun onSettingChanged() // Общий метод для любого изменения
}