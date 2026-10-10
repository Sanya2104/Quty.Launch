// *** TerminalActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.core.adapters.TerminalAdapter
import by.quty.launch.core.managers.TerminalHistoryManager
import by.quty.launch.core.managers.TerminalManager
import by.quty.launch.core.model.TerminalLineModel
import kotlinx.coroutines.launch

/**
 * Активность терминала Quty.Launch.
 *
 * Внутренняя консоль ядра. Выполняет команды через [TerminalManager],
 * выводит результат в лог (RecyclerView) и обрабатывает команды,
 * требующие участия UI (clear — очистка лога, exit — закрытие / перезапуск).
 *
 * История команд сохраняется в [TerminalHistoryManager] (SharedPreferences
 * "terminal_prefs"). Навигация по истории — стрелками ↑ / ↓ в поле ввода.
 *
 * Особенность: в immersive-режиме Android игнорирует adjustResize для IME,
 * поэтому нижний отступ корневого view пересчитывается вручную через
 * WindowInsetsCompat: paddingBottom = max(высота IME, высота навбара) + базовый отступ.
 *
 * Если пользователь применил write-команду (см. TerminalManager.isWriteCommand),
 * выставляется флаг needsRestart. При закрытии (крестик / системная «Назад»)
 * показывается диалог перезапуска — как в SettingsActivity. При вводе
 * команды exit с активным needsRestart приложение перезапускается сразу.
 */
class TerminalActivity : BaseActivity() {

    private lateinit var rootView: View
    private lateinit var cardView: View
    private lateinit var recyclerLog: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnClose: View

    private lateinit var adapter: TerminalAdapter
    private val lines = mutableListOf<TerminalLineModel>()

    // Флаг, что команда сейчас выполняется (блокирует повторную отправку)
    private var isExecuting = false

    // Флаг, что есть применённые изменения, требующие перезапуска
    private var needsRestart = false

    // Флаг, что перезапуск уже запущен (защита от двойного вызова)
    private var isRestarting = false

    // ===== Навигация по истории (стрелки ↑ / ↓) =====

    // Индекс текущей позиции в истории (0 — самая свежая).
    // -1 означает «пользователь не в режиме навигации».
    private var historyIndex = -1

    // Снимок того, что было введено до начала навигации по истории.
    // Позволяет вернуть пользователю «черновик», когда он дойдёт стрелкой ↓ до конца.
    private var historyDraft: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        applyOrientation()

        initViews()
        setupImeInsets()
        setupLog()
        setupInput()
        setupHistoryNavigation()
        setupBackHandler()

        // Приветственная строка
        addOutput(getString(R.string.terminal_welcome))

        // Иммерсивный режим
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    private fun initViews() {
        rootView = findViewById(R.id.terminal_root)
        cardView = findViewById(R.id.terminal_card)
        recyclerLog = findViewById(R.id.terminal_log)
        inputField = findViewById(R.id.terminal_input)
        btnSend = findViewById(R.id.terminal_send)
        btnClose = findViewById(R.id.terminal_close)

        // clipToOutline в XML доступен только с API 31,
        // поэтому включаем в коде — работает с API 21.
        cardView.clipToOutline = true
    }

    /**
     * Пересчитывает нижний padding корневого view при появлении/скрытии клавиатуры.
     */
    private fun setupImeInsets() {
        val baseBottomPx = resources.getDimensionPixelSize(R.dimen.spacing_s)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val bottomPadding = maxOf(imeBottom, navBottom) + baseBottomPx

            view.updatePadding(bottom = bottomPadding)
            insets
        }
    }

    private fun setupLog() {
        adapter = TerminalAdapter(lines)
        recyclerLog.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerLog.adapter = adapter
    }

    private fun setupInput() {
        btnSend.setOnClickListener { submitInput() }
        btnClose.setOnClickListener { checkAndClose() }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                submitInput()
                true
            } else {
                false
            }
        }
    }

    /**
     * Навигация по истории команд стрелками ↑ / ↓.
     *
     * Логика как в bash:
     * - ↑ идём назад по истории (от свежих к старым);
     * - ↓ идём вперёд (от старых к свежим);
     * - когда ↓ доходит до конца истории — восстанавливаем введённый черновик.
     *
     * Навигация начинается с сохранения текущего текста в historyDraft.
     */
    private fun setupHistoryNavigation() {
        inputField.setOnKeyListener { view, keyCode, event ->
            // Обрабатываем только ACTION_DOWN, иначе событие сработает дважды
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            // Стрелка ↑ / ↓ — только если нет модификаторов (не Shift, не Alt)
            if (event.isAltPressed || event.isShiftPressed || event.isCtrlPressed) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    navigateHistoryUp(view as EditText)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    navigateHistoryDown(view as EditText)
                    true
                }
                else -> false
            }
        }
    }

    private fun navigateHistoryUp(editText: EditText) {
        val history = TerminalHistoryManager.getReversed(this)
        if (history.isEmpty()) return

        // Первый вход в навигацию — сохраняем черновик
        if (historyIndex == -1) {
            historyDraft = editText.text.toString()
            historyIndex = 0
        } else if (historyIndex < history.size - 1) {
            historyIndex++
        } else {
            // Дошли до самого старого — дальше не идём
            return
        }

        val command = history[historyIndex]
        editText.setText(command)
        editText.setSelection(command.length)
    }

    private fun navigateHistoryDown(editText: EditText) {
        val history = TerminalHistoryManager.getReversed(this)
        if (history.isEmpty() || historyIndex == -1) return

        if (historyIndex > 0) {
            historyIndex--
            val command = history[historyIndex]
            editText.setText(command)
            editText.setSelection(command.length)
        } else {
            // Вышли за пределы истории — восстанавливаем черновик
            historyIndex = -1
            editText.setText(historyDraft)
            editText.setSelection(historyDraft.length)
            historyDraft = ""
        }
    }

    /**
     * Сбрасывает режим навигации по истории.
     * Вызывается при отправке команды и при очистке поля ввода.
     */
    private fun resetHistoryNavigation() {
        historyIndex = -1
        historyDraft = ""
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    checkAndClose()
                }
            }
        )
    }

    // ============================================================
    // ВВОД И ВЫПОЛНЕНИЕ
    // ============================================================

    private fun submitInput() {
        if (isExecuting) return

        val raw = inputField.text.toString()
        if (raw.isBlank()) return

        // Сбрасываем навигацию по истории — новая команда
        resetHistoryNavigation()

        // Сохраняем в историю (только для непустых вводов)
        TerminalHistoryManager.add(this, raw)

        inputField.setText("")
        addInput(raw)

        val parsed = TerminalManager.parse(raw) ?: return

        // clear обрабатывается на уровне UI
        if (parsed.name == "clear") {
            clearLog()
            return
        }

        // exit — если есть изменения, сразу перезапуск, иначе просто закрыть
        if (parsed.name == "exit") {
            if (needsRestart) {
                isRestarting = true
                restartApp()
            } else {
                finish()
            }
            return
        }

        isExecuting = true
        lifecycleScope.launch {
            val result = TerminalManager.execute(this@TerminalActivity, parsed)
            isExecuting = false

            if (result.isNotEmpty()) {
                val isError = result.startsWith("!")
                if (isError) {
                    addError(result.removePrefix("!").trim())
                } else {
                    addOutput(result)

                    if (TerminalManager.isWriteCommand(this@TerminalActivity, parsed.name)) {
                        needsRestart = true
                    }
                }
            }
        }
    }

    // ============================================================
    // ЗАКРЫТИЕ / ПЕРЕЗАПУСК
    // ============================================================

    private fun checkAndClose() {
        if (needsRestart) {
            showRestartDialog()
        } else {
            finish()
        }
    }

    private fun showRestartDialog() {
        if (isRestarting) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_restart, null)

        val dialog = AlertDialog.Builder(
            this,
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window?.decorView?.rootView
            decorView?.let { view ->
                val renderEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                view.setRenderEffect(renderEffect)
                dialog.setOnDismissListener {
                    view.setRenderEffect(null)
                }
            }
        }

        dialogView.findViewById<Button>(R.id.btn_restart).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }
            isRestarting = true
            restartApp()
        }

        dialogView.findViewById<Button>(R.id.btn_later).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }
            // needsRestart НЕ сбрасываем
        }

        dialog.show()
    }

    private fun restartApp() {
        configManager.setRestartForOrientationFlag()

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(intent)
            finish()
        }, 500)
    }

    // ============================================================
    // ЛОГ
    // ============================================================

    private fun addInput(text: String) {
        lines.add(TerminalLineModel(TerminalLineModel.LineType.INPUT, text))
        notifyAndScroll()
    }

    private fun addOutput(text: String) {
        lines.add(TerminalLineModel(TerminalLineModel.LineType.OUTPUT, text))
        notifyAndScroll()
    }

    private fun addError(text: String) {
        lines.add(TerminalLineModel(TerminalLineModel.LineType.ERROR, text))
        notifyAndScroll()
    }

    private fun clearLog() {
        val sizeBefore = lines.size
        if (sizeBefore == 0) return

        lines.clear()
        adapter.notifyItemRangeRemoved(0, sizeBefore)
    }

    private fun notifyAndScroll() {
        adapter.notifyItemInserted(lines.size - 1)
        recyclerLog.post {
            recyclerLog.scrollToPosition(lines.size - 1)
        }
    }
}