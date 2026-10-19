// *** core/model/ShellStoreModel.kt *** //
package by.quty.launch.core.model

import kotlinx.serialization.Serializable

/**
 * Модель оболочки для магазина.
 *
 * Соответствует записи в shells.json на сервере.
 */
@Serializable
data class ShellStoreModel(
    val id: String,                                 // Уникальный идентификатор
    val name: String,                               // Имя файла (например, QutyAuto)
    val displayName: String,                        // Отображаемое имя
    val author: String,                             // Автор
    val version: String,                            // Версия
    val previewUrl: String,                         // URL превью
    val downloadUrl: String,                        // URL для скачивания .qutyshell
    val fileSize: String,                           // Размер файла (человеко-читаемый)
    val minQutyLaunchVersion: String,               // Минимальная версия Quty.Launch
    val orientation: String? = null,                // Ориентация из манифеста
    val repoUrl: String? = null,                    // Ссылка на репозиторий
    val telegram: String? = null,                   // Ссылка на Telegram
    val datePublished: String = ""                  // Дата публикации (YYYY-MM-DD)
)