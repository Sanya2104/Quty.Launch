// *** core/model/StoreRemoteItemModel.kt *** //
package by.quty.launch.core.model

import kotlinx.serialization.Serializable

/**
 * Модель элемента магазина, получаемого с сервера.
 *
 * Соответствует записи в store/shells.json на сервере.
 *
 * @property id уникальный идентификатор
 * @property type тип элемента (по умолчанию "shell" для обратной совместимости)
 * @property name имя файла / системное имя (QutyAuto)
 * @property displayName отображаемое имя
 * @property author автор
 * @property version версия
 * @property previewUrl URL превью
 * @property downloadUrl URL для скачивания
 * @property fileSize размер файла (человеко-читаемый)
 * @property minQutyLaunchVersion минимальная версия Quty.Launch
 * @property orientation ориентация из манифеста
 * @property repoUrl ссылка на репозиторий
 * @property telegram ссылка на Telegram
 * @property datePublished дата публикации (YYYY-MM-DD)
 */
@Serializable
data class StoreRemoteItemModel(
    val id: String,
    val type: String = StoreItemTypeModel.SHELL.id,
    val name: String,
    val displayName: String,
    val author: String,
    val version: String,
    val previewUrl: String,
    val downloadUrl: String,
    val fileSize: String,
    val minQutyLaunchVersion: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    val telegram: String? = null,
    val datePublished: String = ""
)