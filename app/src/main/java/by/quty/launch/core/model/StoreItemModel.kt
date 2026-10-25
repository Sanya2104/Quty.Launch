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
 * - remoteInfo == null > элемент есть только локально (импорт, встроенная, Default)
 * - localShell == null > элемент есть только на сервере (не установлен)
 * - оба != null > установлен и есть в каталоге
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
     * Активна ли оболочка с текущей локальной версией.
     *
     * ВАЖНО: бейдж «Активна» означает «эта оболочка сейчас активна»,
     * а НЕ «активна с последней версией с сервера». Поэтому сравнение
     * идёт с installedVersion (локальная версия файла), а не с
     * remoteInfo.version.
     *
     * Пример: оболочка активна, версия 0.0.5 локально, на сервере есть
     * 0.0.6. updateAvailable = true, activeVersion = "0.0.5",
     * installedVersion = "0.0.5" → isActiveSameVersion = true →
     * бейдж «Активна» показывается. Это правильно: оболочка активна,
     * просто есть обновление, которое юзер ещё не поставил.
     *
     * Крайние случаи:
     * - installedVersion == null (встроенная без version в manifest) —
     *   считаем «активна» (не знаем версию, но элемент активен);
     * - activeVersion == null (нет данных в prefs, например, до первого
     *   setActiveShell) — считаем «активна» (нечем сравнивать).
     */
    val isActiveSameVersion: Boolean
        get() {
            if (!isActive) return false

            // Версия локального файла неизвестна — считаем активной,
            // чтобы не получить пустой экран без единого пункта меню.
            val localVersion = installedVersion ?: return true

            // activeVersion ещё не записан — нет данных для сравнения.
            return activeVersion == null || activeVersion == localVersion
        }

    /**
     * Элемент присутствует только локально (нет записи в каталоге).
     */
    val isLocalOnly: Boolean
        get() = remoteInfo == null

    /**
     * Можно установить.
     *
     * true, если:
     * - есть запись в каталоге;
     * - И элемент либо не установлен, либо установлен, но версия
     *   локального файла неизвестна (например, встроенная оболочка без
     *   поля version в manifest). В этом случае «установка поверх» —
     *   единственный способ починить оболочку с неизвестной версией.
     */
    val canInstall: Boolean
        get() = remoteInfo != null && (!isInstalled || installedVersion.isNullOrEmpty())

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
     * Можно поделиться (есть локальный файл).
     *
     * ВАЖНО: реальную проверку «файл существует и на него можно получить
     * URI» делает StoreItemDetailFragment.canShareItem().
     */
    val canShare: Boolean
        get() = localShell != null
}