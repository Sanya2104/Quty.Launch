// *** core/model/LoggerTagModel.kt *** //
package by.quty.launch.core.model

/**
 * Тег (категория) записи лога.
 *
 * Используется для фильтрации и для быстрого понимания, из какой
 * подсистемы ядра пришла запись.
 *
 * @property displayName отображаемое имя тега в UI
 */
enum class LoggerTagModel(val displayName: String) {
    CORE("CORE"),
    SHELL("SHELL"),
    WEBVIEW("WEBVIEW"),
    API("API"),
    CONFIG("CONFIG"),
    STORE("STORE"),
    UPDATE("UPDATE"),
    RECOVERY("RECOVERY"),
    JSBRIDGE("JSBRIDGE"),
    TERMINAL("TERMINAL"),
    LOGGER("LOGGER")
}