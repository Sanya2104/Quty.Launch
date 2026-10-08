// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.content.Context
import android.webkit.JavascriptInterface
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.LoggerManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Мост между JavaScript и Kotlin.
 * Обрабатывает вызовы API из WebView.
 *
 * Методы applyColorScheme / applyLanguage удалены — теперь оболочка
 * сама запрашивает состояние ядра через API-метод GetCoreState
 * и подписывается на JS-событие window.onCoreStateChanged.
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
     * Единый scope для выполнения API-вызовов.
     *
     * SupervisorJob — чтобы падение одной корутины (например, ошибка
     * в конкретном методе) не убивало остальные.
     *
     * Dispatchers.IO — потому что методы API делают I/O (файлы, сеть).
     */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // Запускаем выполнение в общем scope
        bridgeScope.launch {
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
     * Отправляет результат выполнения в JavaScript.
     *
     * ВАЖНО: результат и сообщение об ошибке экранируются через
     * JSONObject.quote() — иначе апострофы, кавычки и спецсимволы
     * внутри JSON сломают JS-код и callback не сработает.
     *
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

        // Формируем JavaScript код для вызова callback.
        // JSONObject.quote() корректно экранирует все спецсимволы.
        val jsCode = if (error == null) {
            val safeResult = JSONObject.quote(result)
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId']($safeResult);"
        } else {
            val safeError = JSONObject.quote(error.message ?: "Unknown error")
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId'](null, $safeError);"
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
    // PUSH-МОДЕЛЬ: СООБЩЕНИЕ ОБОЛОЧКЕ ОБ ИЗМЕНЕНИИ СОСТОЯНИЯ ЯДРА
    // ============================================================

    /**
     * Уведомляет оболочку об изменении состояния ядра (тема, язык,
     * ориентация и т.д.). Вызывает JS-функцию window.onCoreStateChanged(),
     * если она определена.
     *
     * Оболочка в этой функции обычно перезапрашивает состояние через
     * Android.call('GetCoreState', null, callbackId).
     */
    fun notifyCoreStateChanged() {
        val webView = webViewRef?.get() ?: return

        val jsCode = """
            (function() {
                if (typeof window.onCoreStateChanged === 'function') {
                    window.onCoreStateChanged();
                }
            })();
        """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(jsCode, null)
                LoggerManager.d("JsBridge", context.getString(R.string.log_js_bridge_state_notified))
            } catch (e: Exception) {
                LoggerManager.e("JsBridge", context.getString(R.string.log_js_bridge_state_notify_error, e.message))
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