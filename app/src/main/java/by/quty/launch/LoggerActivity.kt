// *** LoggerActivity.kt *** //
package by.quty.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.core.adapters.LoggerAdapter
import by.quty.launch.core.logger.LogEntry
import by.quty.launch.core.logger.Logger

/**
 * Активность для просмотра логов (Логгер)
 * Доступна только в режиме разработчика (DevMode)
 */
class LoggerActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LoggerAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvPauseIndicator: TextView
    private lateinit var btnPause: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnClose: ImageButton

    // Слушатель для обновления UI при добавлении логов
    private val logListener = object : Logger.LogListener {
        override fun onLogAdded(entry: LogEntry) {
            runOnUiThread {
                adapter.addLog(entry)
                updateStatus()
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }

        override fun onLogsCleared() {
            runOnUiThread {
                adapter.clearLogs()
                updateStatus()
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

        // Настройка кнопок
        setupButtons()

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

    private fun initViews() {
        recyclerView = findViewById(R.id.recycler_logs)
        tvStatus = findViewById(R.id.tv_status)
        tvPauseIndicator = findViewById(R.id.tv_pause_indicator)
        btnPause = findViewById(R.id.btn_pause)
        btnCopy = findViewById(R.id.btn_copy)
        btnShare = findViewById(R.id.btn_share)
        btnSave = findViewById(R.id.btn_save)
        btnClear = findViewById(R.id.btn_clear)
        btnClose = findViewById(R.id.btn_close)
    }

    private fun setupRecyclerView() {
        adapter = LoggerAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.submitList(Logger.getLogs())

        Handler(Looper.getMainLooper()).postDelayed({
            if (adapter.itemCount > 0) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }, 100)
    }

    private fun setupButtons() {
        // Кнопка "Пауза/Старт"
        btnPause.setOnClickListener {
            if (Logger.isPaused()) {
                Logger.resume()
                btnPause.setImageResource(R.drawable.ic_pause)
                tvPauseIndicator.visibility = View.GONE
                Toast.makeText(this, R.string.logger_resumed, Toast.LENGTH_SHORT).show()
            } else {
                Logger.pause()
                btnPause.setImageResource(R.drawable.ic_play)
                tvPauseIndicator.visibility = View.VISIBLE
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
            val filePath = Logger.saveLogsToFile()
            if (filePath != null) {
                Toast.makeText(this, getString(R.string.logger_saved, filePath), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.logger_save_error, Toast.LENGTH_SHORT).show()
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

    private fun updateStatus() {
        val count = Logger.getLogCount()
        tvStatus.text = getString(R.string.logger_status, count)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.removeListener(logListener)
    }

    override fun onResume() {
        super.onResume()
        adapter.submitList(Logger.getLogs())
        updateStatus()

        if (Logger.isPaused()) {
            btnPause.setImageResource(R.drawable.ic_play)
            tvPauseIndicator.visibility = View.VISIBLE
        } else {
            btnPause.setImageResource(R.drawable.ic_pause)
            tvPauseIndicator.visibility = View.GONE
        }
    }
}