// *** LoggerActivity.kt *** //
package by.quty.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.core.adapters.LoggerAdapter
import by.quty.launch.core.logger.LogEntry
import by.quty.launch.core.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Активность для просмотра логов (Логгер)
 * Доступна только в режиме разработчика (DevMode)
 */
class LoggerActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LoggerAdapter
    private lateinit var tvStatus: TextView
    private lateinit var pausePanel: LinearLayout
    private lateinit var btnPause: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnClose: ImageButton

    // Фильтры
    private lateinit var spinnerLevel: Spinner
    private lateinit var spinnerSource: Spinner
    private var selectedLevel: String = ""
    private var selectedSource: String = ""

    // Ключи для сохранения состояния
    companion object {
        private const val KEY_SELECTED_LEVEL = "selected_level"
        private const val KEY_SELECTED_SOURCE = "selected_source"
        private const val KEY_SELECTED_LEVEL_POSITION = "selected_level_position"
        private const val KEY_SELECTED_SOURCE_POSITION = "selected_source_position"
    }

    // Слушатель для обновления UI при добавлении логов
    private val logListener = object : Logger.LogListener {
        override fun onLogAdded(entry: LogEntry) {
            runOnUiThread {
                applyFilters()
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }

        override fun onLogsCleared() {
            runOnUiThread {
                applyFilters()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, включён ли DevMode
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        val isDevMode = prefs.getBoolean("developer_mode", false)

        if (!isDevMode) {
            Toast.makeText(this, R.string.logger_devmode_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_logger)

        // Применяем ориентацию
        applyOrientation()

        // Инициализация UI
        initViews()

        // Настройка RecyclerView
        setupRecyclerView()

        // Настройка фильтров
        setupFilters()

        // Настройка кнопок
        setupButtons()

        // Восстанавливаем состояние, если есть
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        } else {
            // Инициализируем значения фильтров только при первом создании
            selectedLevel = getString(R.string.logger_filter_all)
            selectedSource = getString(R.string.logger_filter_all)
        }

        // Обновляем статус
        updateStatus()

        // Регистрируем слушатель
        Logger.addListener(logListener)

        // Включаем иммерсивный режим
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    /**
     * Сохраняет состояние активности при повороте экрана
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Сохраняем выбранные значения фильтров
        outState.putString(KEY_SELECTED_LEVEL, selectedLevel)
        outState.putString(KEY_SELECTED_SOURCE, selectedSource)

        // Сохраняем позиции в Spinner
        outState.putInt(KEY_SELECTED_LEVEL_POSITION, spinnerLevel.selectedItemPosition)
        outState.putInt(KEY_SELECTED_SOURCE_POSITION, spinnerSource.selectedItemPosition)
    }

    /**
     * Восстанавливает состояние активности после поворота экрана
     */
    private fun restoreState(savedInstanceState: Bundle) {
        selectedLevel = savedInstanceState.getString(KEY_SELECTED_LEVEL, getString(R.string.logger_filter_all))
        selectedSource = savedInstanceState.getString(KEY_SELECTED_SOURCE, getString(R.string.logger_filter_all))

        val levelPosition = savedInstanceState.getInt(KEY_SELECTED_LEVEL_POSITION, 0)
        val sourcePosition = savedInstanceState.getInt(KEY_SELECTED_SOURCE_POSITION, 0)

        // Восстанавливаем позиции в Spinner (если адаптер уже установлен)
        if (::spinnerLevel.isInitialized && spinnerLevel.adapter != null) {
            spinnerLevel.setSelection(levelPosition, false)
        }
        if (::spinnerSource.isInitialized && spinnerSource.adapter != null) {
            spinnerSource.setSelection(sourcePosition, false)
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recycler_logs)
        tvStatus = findViewById(R.id.tv_status)
        pausePanel = findViewById(R.id.pause_panel)
        btnPause = findViewById(R.id.btn_pause)
        btnCopy = findViewById(R.id.btn_copy)
        btnShare = findViewById(R.id.btn_share)
        btnSave = findViewById(R.id.btn_save)
        btnClear = findViewById(R.id.btn_clear)
        btnClose = findViewById(R.id.btn_close)
        spinnerLevel = findViewById(R.id.spinner_level)
        spinnerSource = findViewById(R.id.spinner_source)
    }

    private fun setupRecyclerView() {
        adapter = LoggerAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        applyFilters()
    }

    /**
     * Настройка фильтров (Spinner)
     */
    private fun setupFilters() {
        // Фильтр по уровню
        val levelAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.logger_levels,
            android.R.layout.simple_spinner_item
        )
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLevel.adapter = levelAdapter

        spinnerLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLevel = parent?.getItemAtPosition(position) as String
                applyFilters()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Ничего не делаем
            }
        }

        // Фильтр по источнику
        val sourceAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.logger_sources,
            android.R.layout.simple_spinner_item
        )
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSource.adapter = sourceAdapter

        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSource = parent?.getItemAtPosition(position) as String
                applyFilters()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Ничего не делаем
            }
        }
    }

    private fun setupButtons() {
        // Кнопка "Пауза/Старт"
        btnPause.setOnClickListener {
            if (Logger.isPaused()) {
                Logger.resume()
                btnPause.setImageResource(R.drawable.ic_pause)
                pausePanel.visibility = View.GONE
                Toast.makeText(this, R.string.logger_resumed, Toast.LENGTH_SHORT).show()
            } else {
                Logger.pause()
                btnPause.setImageResource(R.drawable.ic_play)
                pausePanel.visibility = View.VISIBLE
                Toast.makeText(this, R.string.logger_paused, Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка "Копировать"
        btnCopy.setOnClickListener {
            val logsText = Logger.formatLogsForCopy()
            if (logsText.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Logs", logsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.logger_copied, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.logger_empty, Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка "Поделиться"
        btnShare.setOnClickListener {
            val logsText = Logger.formatLogsForShare()
            if (logsText.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, logsText)
                    putExtra(Intent.EXTRA_SUBJECT, "Quty.Launch Logs")
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.logger_share_title)))
            } else {
                Toast.makeText(this, R.string.logger_empty, Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка "Сохранить"
        btnSave.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val filePath = Logger.saveLogsToFile()
                withContext(Dispatchers.Main) {
                    if (filePath != null) {
                        Toast.makeText(
                            this@LoggerActivity,
                            getString(R.string.logger_saved, filePath),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@LoggerActivity,
                            R.string.logger_save_error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // Кнопка "Очистить"
        btnClear.setOnClickListener {
            Logger.clear()
            Toast.makeText(this, R.string.logger_cleared, Toast.LENGTH_SHORT).show()
        }

        // Кнопка "Закрыть"
        btnClose.setOnClickListener {
            finish()
        }
    }

    /**
     * Применяет фильтры к списку логов
     */
    private fun applyFilters() {
        val allLogs = Logger.getLogs()
        val allText = getString(R.string.logger_filter_all)

        val filtered = allLogs.filter { entry ->
            // Фильтр по уровню
            (selectedLevel == allText || entry.level.name == selectedLevel) &&
                    // Фильтр по источнику
                    (selectedSource == allText || entry.source == selectedSource)
        }

        adapter.submitList(filtered)
        updateStatus(filtered.size, allLogs.size)
    }

    /**
     * Обновляет статус с количеством логов
     * @param filteredCount количество отфильтрованных логов
     * @param totalCount общее количество логов
     */
    private fun updateStatus(filteredCount: Int = 0, totalCount: Int = 0) {
        val count = if (totalCount > 0) totalCount else Logger.getLogCount()
        tvStatus.text = getString(R.string.logger_filter_status, filteredCount, count)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.removeListener(logListener)
    }

    override fun onResume() {
        super.onResume()
        applyFilters()

        // Восстанавливаем состояние паузы
        if (Logger.isPaused()) {
            btnPause.setImageResource(R.drawable.ic_play)
            pausePanel.visibility = View.VISIBLE
        } else {
            btnPause.setImageResource(R.drawable.ic_pause)
            pausePanel.visibility = View.GONE
        }
    }
}