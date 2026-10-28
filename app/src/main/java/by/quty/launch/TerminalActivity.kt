// *** TerminalActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
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
 * Терминал ядра: лог + ввод, история стрелками, IME-insets.
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

    private var isExecuting = false

    // ===== Навигация по истории =====

    private var historyIndex = -1
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

        addOutput(getString(R.string.terminal_welcome))

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

        cardView.clipToOutline = true
    }

    /** Пересчитывает paddingBottom при появлении/скрытии IME. */
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

    /** ^ / v в EditText — навигация по истории. */
    private fun setupHistoryNavigation() {
        inputField.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

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

        if (historyIndex == -1) {
            historyDraft = editText.text.toString()
            historyIndex = 0
        } else if (historyIndex < history.size - 1) {
            historyIndex++
        } else {
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
            historyIndex = -1
            editText.setText(historyDraft)
            editText.setSelection(historyDraft.length)
            historyDraft = ""
        }
    }

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

        resetHistoryNavigation()

        TerminalHistoryManager.add(this, raw)

        inputField.setText("")
        addInput(raw)

        val parsed = TerminalManager.parse(raw) ?: return

        if (parsed.name == "clear") {
            clearLog()
            return
        }

        if (parsed.name == "exit") {
            if (getNeedsRestart()) {
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
                        markRestartRequired()
                    }
                }
            }
        }
    }

    // ============================================================
    // ЗАКРЫТИЕ
    // ============================================================

    private fun checkAndClose() {
        if (getNeedsRestart()) {
            showRestartDialog()
        } else {
            finish()
        }
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