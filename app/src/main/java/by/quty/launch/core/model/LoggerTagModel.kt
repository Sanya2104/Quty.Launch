// *** core/model/LoggerTagModel.kt *** //
package by.quty.launch.core.model

/** Тег (категория) записи лога. */
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