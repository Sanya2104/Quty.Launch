// *** core/model/StoreItemTypeModel.kt *** //
package by.quty.launch.core.model

/**
 * Тип элемента магазина.
 *
 * Сейчас поддерживается только оболочка.
 * Позже сюда добавятся другие типы ресурсов (темы, шрифты, виджеты и т.д.).
 *
 * @property id строковый идентификатор для сериализации (совпадает с полем type в store/shells.json)
 */
enum class StoreItemTypeModel(val id: String) {
    SHELL("shell")
}