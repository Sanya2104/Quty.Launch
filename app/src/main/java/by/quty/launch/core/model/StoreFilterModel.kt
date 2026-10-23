// *** core/model/StoreFilterModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.StringRes
import by.quty.launch.R

/**
 * Фильтр списка магазина.
 *
 * Чипы над списком в StoreCatalogFragment. Одиночный выбор —
 * как табы в Google Play.
 *
 * @property labelRes ресурс локализованного названия
 * @property emptyRes ресурс текста пустого состояния для этого фильтра
 * @property predicate предикат для фильтрации списка
 */
enum class StoreFilterModel(
    @StringRes val labelRes: Int,
    @StringRes val emptyRes: Int,
    val predicate: (StoreItemModel) -> Boolean
) {
    ALL(
        R.string.store_filter_all,
        R.string.store_empty_all,
        { true }
    ),

    INSTALLED(
        R.string.store_filter_installed,
        R.string.store_empty_installed,
        { it.isInstalled }
    ),

    UPDATES(
        R.string.store_filter_updates,
        R.string.store_empty_updates,
        { it.updateAvailable }
    ),

    NOT_INSTALLED(
        R.string.store_filter_not_installed,
        R.string.store_empty_not_installed,
        { !it.isInstalled }
    )
}