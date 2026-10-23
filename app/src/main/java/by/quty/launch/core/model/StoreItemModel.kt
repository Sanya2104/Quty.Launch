// *** core/model/StoreItemModel.kt *** //
package by.quty.launch.core.model

import by.quty.launch.core.managers.Shell
import by.quty.launch.core.utilities.UpdateHelper

/**
 * Объединённая модель элемента магазина для UI.
 *
 * Собирается StoreManager.buildItems() из двух источников:
 * - remoteInfo (с сервера, StoreRemoteItemModel)
 * - localShell (локальная оболочка, Shell)
 *
 * Оба источника опциональны:
 * - remoteInfo == null → элемент есть только локально (импорт, встроенная, Default)
 * - localShell == null → элемент есть только на сервере (не установлен)
 * - оба != null → установлен и есть в каталоге
 *
 * Все UI-состояния вычисляются через геттеры — единая точка правды.
 *
 * @property name системное имя (совпадает с Shell.name)
 * @property type тип элемента
 * @property displayName отображаемое имя
 * @property author автор
 * @property version версия магазина (если есть) или локальная
 * @property previewUrl URL превью (только из магазина)
 * @property previewBase64 Base64-превью (только локальное)
 * @property fileSize размер файла
 * @property datePublished дата публикации
 * @property minQutyLaunchVersion минимальная версия Quty.Launch
 * @property orientation ориентация
 * @property repoUrl ссылка на репозиторий
 * @property telegramUrl ссылка на Telegram
 * @property isInstalled установлен ли локально
 * @property installedVersion версия локального файла
 * @property isActive назначен ли активным
 * @property activeVersion версия, реально загруженная в WebView
 * @property remoteInfo данные с сервера (null = только локальный)
 * @property localShell локальная оболочка (null = только на сервере)
 */
data class StoreItemModel(
    val name: String,
    val type: StoreItemTypeModel,

    val displayName: String,
    val author: String?,
    val version: String?,
    val previewUrl: String?,
    val previewBase64: String?,

    val fileSize: String?,
    val datePublished: String?,
    val minQutyLaunchVersion: String?,
    val orientation: String?,
    val repoUrl: String?,
    val telegramUrl: String?,

    val isInstalled: Boolean,
    val installedVersion: String?,
    val isActive: Boolean,
    val activeVersion: String?,

    val remoteInfo: StoreRemoteItemModel?,
    val localShell: Shell?
) {

    /**
     * Есть ли обновление на сервере.
     * true, если установлено и версия магазина новее установленной.
     */
    val updateAvailable: Boolean
        get() {
            if (!isInstalled) return false
            val remoteVersion = remoteInfo?.version ?: return false
            val localVersion = installedVersion ?: return false
            return UpdateHelper.isNewerVersion(remoteVersion, localVersion)
        }

    /**
     * Установленная версия новее магазинной.
     * true, если установлено и локальная версия новее магазинной.
     */
    val installedIsNewer: Boolean
        get() {
            if (!isInstalled) return false
            val remoteVersion = remoteInfo?.version ?: return false
            val localVersion = installedVersion ?: return false
            return UpdateHelper.isNewerVersion(localVersion, remoteVersion)
        }

    /**
     * Активна и версия реально загруженной оболочки совпадает
     * с установленной.
     */
    val isActiveSameVersion: Boolean
        get() {
            if (!isActive) return false
            val effective = if (installedIsNewer) installedVersion else remoteInfo?.version
            return activeVersion != null && activeVersion == effective
        }

    /**
     * Элемент присутствует только локально (нет записи в каталоге).
     */
    val isLocalOnly: Boolean
        get() = remoteInfo == null

    /**
     * Можно установить (есть в магазине и не установлено).
     */
    val canInstall: Boolean
        get() = remoteInfo != null && !isInstalled

    /**
     * Можно обновить (есть обновление).
     */
    val canUpdate: Boolean
        get() = updateAvailable

    /**
     * Можно применить (установлено, но не активно).
     */
    val canApply: Boolean
        get() = isInstalled && !isActive

    /**
     * Можно удалить (кастомная, не активна).
     */
    val canDelete: Boolean
        get() = localShell?.isCustom == true && !isActive

    /**
     * Можно поделиться (есть локальный файл).
     */
    val canShare: Boolean
        get() = localShell != null
}