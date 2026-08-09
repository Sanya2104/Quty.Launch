// *** StoreActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.widget.ImageButton
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageManager

/**
 * Активность магазина оболочек Quty.Launch
 * Позволяет просматривать и устанавливать оболочки из репозитория
 * Доступна всегда, независимо от DevMode
 */
class StoreActivity : BaseActivity() {

    // Менеджеры (пока не используются, но добавлены для будущего функционала)
    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // StoreActivity доступна всегда — проверка DevMode НЕ ТРЕБУЕТСЯ

        setContentView(R.layout.activity_store)

        // Применяем ориентацию
        applyOrientation()

        // Инициализация менеджеров (для будущего функционала)
        shellManager = ShellManager(this, configManager)
        storageManager = StorageManager(this)

        // Инициализация UI
        initViews()

        // Включаем иммерсивный режим
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    /**
     * Инициализация UI элементов
     */
    private fun initViews() {
        val btnClose = findViewById<ImageButton>(R.id.btn_close)

        // Кнопка закрытия
        btnClose.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очистка ресурсов (при необходимости)
    }
}