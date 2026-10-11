// *** core/fragments/logger/LoggerFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.adapters.LoggerAdapter
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerEntryModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный фрагмент логгера — вывод записей.
 *
 * Подписывается на LoggerManager в onStart, отписывается в onStop.
 * При возврате в onStart — синхронизируется с буфером, чтобы показать
 * записи, накопившиеся пока фрагмент был невидим.
 *
 * UI:
 * - Панель действий сверху: счётчик, «Очистить», «Копировать всё», «Скролл вниз».
 * - Карточка bg_card_rounded — список логов (как в терминале).
 *
 * Записи приходят из LoggerManager в том потоке, в котором вызван log().
 * Переключение в Main — здесь, через lifecycleScope.
 */
class LoggerFragment : Fragment(), LoggerManager.LoggerListener {

    private lateinit var cardView: View
    private lateinit var recyclerLog: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var counterView: TextView
    private lateinit var btnClear: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnScrollBottom: ImageButton

    private lateinit var adapter: LoggerAdapter

    // Локальная копия записей — источник для адаптера
    private val entries = mutableListOf<LoggerEntryModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logger, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardView = view.findViewById(R.id.logger_card)
        recyclerLog = view.findViewById(R.id.logger_list)
        emptyText = view.findViewById(R.id.logger_empty)
        counterView = view.findViewById(R.id.logger_counter)
        btnClear = view.findViewById(R.id.logger_action_clear)
        btnCopy = view.findViewById(R.id.logger_action_copy)
        btnScrollBottom = view.findViewById(R.id.logger_action_scroll_bottom)

        // clipToOutline в XML недоступен для API 29-30,
        // включаем в коде — работает с API 21.
        cardView.clipToOutline = true

        setupRecycler()
        setupActions()
        syncWithBuffer()
    }

    override fun onStart() {
        super.onStart()
        syncWithBuffer()
        LoggerManager.subscribe(this)
    }

    override fun onStop() {
        super.onStop()
        LoggerManager.unsubscribe(this)
    }

    private fun setupRecycler() {
        adapter = LoggerAdapter(entries)

        recyclerLog.layoutManager = LinearLayoutManager(requireContext()).apply {
            // Новые записи — снизу (как в терминале)
            stackFromEnd = true
        }
        recyclerLog.adapter = adapter
    }

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================

    private fun setupActions() {
        btnClear.setOnClickListener { clearLog() }
        btnCopy.setOnClickListener { copyAllToClipboard() }
        btnScrollBottom.setOnClickListener { scrollToBottom() }
    }

    private fun clearLog() {
        if (entries.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.logger_settings_clear_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        LoggerManager.clear()
        // adapter.clearAll() и updateEmptyState() вызовет onCleared()
    }

    private fun copyAllToClipboard() {
        if (entries.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.logger_copy_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val text = formatAllEntries()
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("logger", text)
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            requireContext(),
            getString(R.string.logger_copy_success, entries.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Форматирует все записи в текст для буфера обмена.
     */
    private fun formatAllEntries(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            entries.forEach { entry ->
                append("[")
                append(formatter.format(Date(entry.timestamp)))
                append("] ")
                append(entry.level.letter)
                append(" [")
                append(entry.tag.displayName)
                append("] ")
                appendLine(entry.message)
            }
        }
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ С БУФЕРОМ
    // ============================================================

    /**
     * Синхронизирует локальный список с буфером LoggerManager.
     *
     * Вызывается:
     * - при первом показе фрагмента (onViewCreated);
     * - при каждом возврате в onStart (записи могли накопиться,
     *   пока фрагмент был невидим).
     *
     * Если размеры совпадают — считаем, что синхронизировано.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun syncWithBuffer() {
        if (!isAdded) return

        val snapshot = LoggerManager.getAll()
        if (entries.size == snapshot.size) {
            updateCounter()
            updateEmptyState()
            return
        }

        val oldSize = entries.size
        entries.clear()
        entries.addAll(snapshot)

        if (oldSize == 0) {
            // Первичная загрузка — точечное уведомление
            adapter.notifyItemRangeInserted(0, entries.size)
        } else {
            // Догрузка — простой путь
            adapter.notifyDataSetChanged()
        }

        updateCounter()
        updateEmptyState()
        scrollToBottom()
    }

    private fun updateCounter() {
        counterView.text = getString(R.string.logger_counter_format, entries.size, 500)
    }

    private fun updateEmptyState() {
        if (entries.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerLog.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerLog.visibility = View.VISIBLE
        }
    }

    private fun scrollToBottom() {
        recyclerLog.post {
            if (entries.isNotEmpty()) {
                recyclerLog.scrollToPosition(entries.size - 1)
            }
        }
    }

    // ============================================================
    // LoggerManager.LoggerListener
    // ============================================================

    override fun onEntryAdded(entry: LoggerEntryModel) {
        // Запись может прийти из IO-потока — переключаемся в Main
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded || view == null) return@launch

            adapter.addEntry(entry)
            updateCounter()
            updateEmptyState()
            scrollToBottom()
        }
    }

    override fun onCleared() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded || view == null) return@launch

            adapter.clearAll()
            updateCounter()
            updateEmptyState()
        }
    }
}