// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.content.Context
import android.webkit.JavascriptInterface
import by.quty.launch.R
import by.quty.launch.core.Core
import by.quty.launch.core.logger.Logger
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

    // Тайм-аут выполнения метода (10 секунд)
    private val timeoutMs = 10000L

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
                val error = """{"success": false, "error": "${context.getString(R.string.js_bridge_timeout)}"}"""
                sendResultToJs(callbackId, error, e)

            } catch (e: CancellationException) {
                // Отмена выполнения
                val error = """{"success": false, "error": "${context.getString(R.string.js_bridge_cancelled)}"}"""
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
            // WebView уничтожен — логируем через Logger
            if (error != null) {
                Logger.e("JsBridge", context.getString(R.string.js_bridge_webview_null, error.message))
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
                Logger.e("JsBridge", context.getString(R.string.js_bridge_send_error, e.message))
            }
        }
    }

    /**
     * Принимает лог из JavaScript и отправляет в Logger
     * @param logData JSON строка с полями: level, message
     */
    @JavascriptInterface
    fun log(logData: String) {
        try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val data = json.decodeFromString<LogData>(logData)

            // Всегда используем "WebView" как источник
            when (data.level.lowercase()) {
                "debug", "log" -> Logger.d("WebView", data.message, "WebView")
                "info" -> Logger.i("WebView", data.message, "WebView")
                "warn" -> Logger.w("WebView", data.message, "WebView")
                "error" -> Logger.e("WebView", data.message, "WebView")
                else -> Logger.d("WebView", data.message, "WebView")
            }
        } catch (_: Exception) {
            // Игнорируем ошибки парсинга
        }
    }

    /**
     * Структура данных для лога
     */
    @kotlinx.serialization.Serializable
    data class LogData(
        val level: String,
        val message: String
    )
}