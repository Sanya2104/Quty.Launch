// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.content.Context
import android.webkit.JavascriptInterface
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.LoggerManager
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Мост между JavaScript и Kotlin
 * Обрабатывает вызовы API из WebView
 */
class JsBridge(
    private val core: Core,
    private val context: Context
) {

    // WeakReference для предотвращения утечки памяти
    private var webViewRef: WeakReference<LauncherWebView>? = null

    // Тайм-аут выполнения метода (из конфига)
    private val timeoutMs = CoreConfig.JS_BRIDGE_TIMEOUT_MS

    /**
     * Устанавливает ссылку на WebView для отправки результатов обратно в JS
     * @param webView экземпляр LauncherWebView
     */
    fun setWebView(webView: LauncherWebView) {
        webViewRef = WeakReference(webView)
    }

    /**
     * Вызов API метода из JavaScript (АСИНХРОННЫЙ)
     * @param method имя метода (например, "GetApps")
     * @param params JSON строка с параметрами
     * @param callbackId уникальный идентификатор для ответа в JS
     *
     * Результат отправляется в JavaScript через callback
     */
    @JavascriptInterface
    fun call(method: String, params: String?, callbackId: String) {
        // Запускаем выполнение в фоновом потоке
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Выполняем метод с таймаутом
                val result = withTimeout(timeoutMs.milliseconds) {
                    core.execute(method, params)
                }

                // Отправляем результат обратно в JavaScript
                sendResultToJs(callbackId, result, null)

            } catch (e: TimeoutCancellationException) {
                // Тайм-аут выполнения
                val error = """{"success": false, "error": "${context.getString(R.string.js_bridge_timeout_error)}"}"""
                sendResultToJs(callbackId, error, e)

            } catch (e: CancellationException) {
                // Отмена выполнения
                val error = """{"success": false, "error": "${context.getString(R.string.js_bridge_cancelled_error)}"}"""
                sendResultToJs(callbackId, error, e)

            } catch (e: Exception) {
                // Любая другая ошибка
                val error = """{"success": false, "error": "${e.message}"}"""
                sendResultToJs(callbackId, error, e)
            }
        }
    }

    /**
     * Отправляет результат выполнения в JavaScript
     * @param callbackId идентификатор callback в JS
     * @param result JSON строка с результатом
     * @param error исключение (если было)
     */
    private fun sendResultToJs(callbackId: String, result: String, error: Throwable?) {
        // Получаем WebView из WeakReference
        val webView = webViewRef?.get()
        if (webView == null) {
            // WebView уничтожен — логируем через LoggerManager
            if (error != null) {
                LoggerManager.e("JsBridge", context.getString(R.string.js_bridge_webview_null, error.message))
            }
            return
        }

        // Формируем JavaScript код для вызова callback
        val jsCode = if (error == null) {
            // Успешное выполнение
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId']($result);"
        } else {
            // Ошибка выполнения
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId'](null, '${error.message}');"
        }

        // Выполняем JavaScript в UI потоке
        webView.post {
            try {
                webView.evaluateJavascript(jsCode, null)
            } catch (e: Exception) {
                LoggerManager.e("JsBridge", context.getString(R.string.js_bridge_send_error, e.message))
            }
        }
    }

    /**
     * Принимает лог из JavaScript и отправляет в LoggerManager
     * @param logData JSON строка с полями: level, message
     */

    @JavascriptInterface
    fun log(logData: String) {
        try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val data = json.decodeFromString<LogData>(logData)

            val sourceTag = if (data.tag != null) {
                "WebView/${data.tag}"
            } else {
                "WebView"
            }

            // Убираем маркер из сообщения
            val cleanMessage = data.message.replace("[JS_BRIDGE_LOG] ", "")

            when (data.level.lowercase()) {
                "debug", "log" -> LoggerManager.d(sourceTag, cleanMessage, "WebView")
                "info" -> LoggerManager.i(sourceTag, cleanMessage, "WebView")
                "warn" -> LoggerManager.w(sourceTag, cleanMessage, "WebView")
                "error" -> LoggerManager.e(sourceTag, cleanMessage, "WebView")
                else -> LoggerManager.d(sourceTag, cleanMessage, "WebView")
            }
        } catch (_: Exception) {
            // Игнорируем ошибки парсинга
        }
    }

    // ============================================================
    // ПРИМЕНЕНИЕ ЦВЕТОВОЙ СХЕМЫ И ТЕМЫ В WEBVIEW
    // ============================================================

    /**
     * Применяет цветовую схему и тему в WebView
     * Вызывает JavaScript функцию applyColorScheme(primary, accent, theme)
     * @param primary primary цвет в HEX (#009688)
     * @param accent accent цвет в HEX (#4CAF50)
     */
    @JavascriptInterface
    fun applyColorScheme(primary: String, accent: String) {
        val webView = webViewRef?.get()
        if (webView == null) {
            LoggerManager.e(
                "JsBridge",
                context.getString(R.string.log_js_bridge_webview_destroyed_color_scheme)
            )
            return
        }

        // Получаем текущую тему (Dark/Light)
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("theme_dark", true)
        val theme = if (isDark) "dark" else "light"

        // Формируем JavaScript код для применения цветов и темы
        val jsCode = """
            (function() {
                // Проверяем, определена ли функция applyColorScheme
                if (typeof window.applyColorScheme === 'function') {
                    window.applyColorScheme('$primary', '$accent', '$theme');
                } else {
                    // Fallback: применяем через CSS переменные
                    document.documentElement.style.setProperty('--primary-color', '$primary');
                    document.documentElement.style.setProperty('--accent-color', '$accent');
                    document.documentElement.style.setProperty('--theme', '$theme');
                    
                    // Добавляем классы для темы
                    document.body.classList.toggle('dark-theme', '$theme' === 'dark');
                    document.body.classList.toggle('light-theme', '$theme' === 'light');
                }
            })();
        """.trimIndent()

        // Выполняем JavaScript в UI потоке
        webView.post {
            try {
                webView.evaluateJavascript(jsCode, null)
                LoggerManager.d(
                    "JsBridge",
                    context.getString(R.string.log_js_bridge_color_scheme_applied, primary, accent) + ", theme: $theme"
                )
            } catch (e: Exception) {
                LoggerManager.e(
                    "JsBridge",
                    context.getString(R.string.log_js_bridge_color_scheme_error, e.message)
                )
            }
        }
    }

    /**
     * Структура данных для лога
     */
    @kotlinx.serialization.Serializable
    data class LogData(
        val level: String,
        val tag: String? = null,
        val message: String
    )
}