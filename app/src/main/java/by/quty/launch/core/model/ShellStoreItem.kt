// *** core/model/ShellStoreItem.kt *** //
package by.quty.launch.core.model

import kotlinx.serialization.Serializable

/**
 * Модель оболочки для магазина
 * Получается из репозитория (shells.json)
 */
@Serializable
data class ShellStoreItem(
    val id: String,                         // Уникальный идентификатор
    val name: String,                       // Имя файла оболочки (QutyLauncher)
    val displayName: String,                // Отображаемое имя
    val author: String,                     // Автор
    val version: String,                    // Версия
    val description: String,                // Описание
    val previewUrl: String,                 // URL превью
    val screenshots: List<String> = emptyList(), // Скриншоты
    val downloadUrl: String,                // URL для скачивания .qutyshell
    val fileSize: String,                   // Размер файла
    val downloads: Int = 0,                 // Количество скачиваний
    val isPremium: Boolean = false,         // Платная или бесплатная
    val tags: List<String> = emptyList(),   // Теги
    val minQutyLaunchVersion: String,       // Минимальная версия Quty.Launch
    val datePublished: String,              // Дата публикации
    val isInstalled: Boolean = false        // Установлена ли
)