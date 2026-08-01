// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.viewpager2.widget.ViewPager2
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.adapters.SettingsPagerAdapter
import by.quty.launch.core.fragments.DisplaySettingsFragment
import by.quty.launch.core.fragments.SystemSettingsFragment
import by.quty.launch.core.fragments.ShellSettingsFragment
import by.quty.launch.core.interfaces.SettingsEventListener
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Активность настроек Quty.Launch с вкладками
 * Позволяет выбирать оболочку оформления, ориентацию экрана и полноэкранный режим
 */
class SettingsActivity : BaseActivity(), SettingsEventListener {

    // Менеджеры - используем configManager из BaseActivity через геттер
    lateinit var shellManager: ShellManager

    // UI компоненты
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var closeButton: Button
    private lateinit var pagerAdapter: SettingsPagerAdapter

    // Ссылки на фрагменты для обновления
    var shellFragment: ShellSettingsFragment? = null
        private set
    var displayFragment: DisplaySettingsFragment? = null
        private set
    private var systemFragment: SystemSettingsFragment? = null

    // Переменные для отслеживания изменений
    private var originalShell: String? = null
    private var originalOrientation: String? = null
    private var originalFullscreen: Boolean? = null
    private var originalStrictMode: Boolean? = null
    private var originalDevMode: Boolean? = null

    // Текущие значения (могут меняться)
    private var currentShell: String? = null
    private var currentOrientation: String? = null
    private var currentFullscreen: Boolean? = null
    private var currentStrictMode: Boolean? = null

    // Флаг для предотвращения множественных перезапусков
    private var isRestarting = false

    companion object {
        const val RESULT_SHELL_CHANGED = 1
        const val EXTRA_SELECTED_SHELL = "selected_shell"
        private const val DELAY_BEFORE_RESTART = 500L // Задержка перед перезапуском (мс)

        // Индексы вкладок
        private const val TAB_SHELL = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        applyOrientation()
        shellManager = ShellManager(this, configManager)

        // Сохраняем исходные настройки
        originalShell = configManager.getActiveShell()
        originalOrientation = configManager.getOrientation()
        originalFullscreen = configManager.isFullscreenEnabled()
        originalStrictMode = configManager.isStrictModeEnabled()

        // Сохраняем исходное состояние DevMode (фиксируется при первом открытии настроек)
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        originalDevMode = prefs.getBoolean("developer_mode", false)

        // Копируем в текущие
        currentShell = originalShell
        currentOrientation = originalOrientation
        currentFullscreen = originalFullscreen
        currentStrictMode = originalStrictMode

        // УСТАНАВЛИВАЕМ LAYOUT ПЕРЕД ВСЕМИ ОПЕРАЦИЯМИ С UI
        setContentView(R.layout.activity_settings)

        // Инициализация UI
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
        closeButton = findViewById(R.id.close_button)

        setupViewPager()
        setupCloseButton()
        setupBackPressedDispatcher()
        restoreTabPosition()

        // Включаем иммерсивный режим ПОСЛЕ того, как View создан
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    /**
     * Настройка ViewPager2 с TabLayout
     */
    private fun setupViewPager() {
        pagerAdapter = SettingsPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Привязываем TabLayout к ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                SettingsPagerAdapter.TAB_SHELL -> tab.text = getString(R.string.tab_shell)
                SettingsPagerAdapter.TAB_DISPLAY -> tab.text = getString(R.string.tab_display)
                SettingsPagerAdapter.TAB_SYSTEM -> tab.text = getString(R.string.tab_system)
                SettingsPagerAdapter.TAB_DEVELOPER -> tab.text = getString(R.string.tab_developer)
            }
        }.attach()

        // Сохраняем ссылки на фрагменты при их создании
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Обновляем ссылки на фрагменты
                updateFragmentReferences()
            }
        })
    }

    /**
     * Получить текущую позицию вкладки
     */
    fun getCurrentTabPosition(): Int = viewPager.currentItem

    /**
     * Восстанавливает позицию вкладки из Intent
     * При обычном запуске — открывает "Оформление" (индекс 0)
     * При переключении DevMode — открывает "Система" (индекс 2) через Intent
     */
    fun restoreTabPosition() {
        // Проверяем Intent (для переключения DevMode)
        val intentPosition = intent.getIntExtra("restore_tab_position", -1)

        if (intentPosition >= 0 && intentPosition < pagerAdapter.itemCount) {
            // Переключение DevMode — открываем "Система"
            viewPager.setCurrentItem(intentPosition, false)
            // Очищаем, чтобы не мешать при следующем запуске
            intent.removeExtra("restore_tab_position")
            return
        }

        // Обычный запуск — всегда открываем "Оформление" (индекс 0)
        viewPager.setCurrentItem(TAB_SHELL, false)
    }

    /**
     * Обновляет адаптер ViewPager2 (пересоздаёт фрагменты)
     * Используется при переключении DevMode без перезапуска активности
     */
    fun refreshPagerAdapter() {
        // Сохраняем текущую позицию
        val currentPosition = viewPager.currentItem

        // Создаём новый адаптер
        pagerAdapter = SettingsPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Перепривязываем TabLayout
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                SettingsPagerAdapter.TAB_SHELL -> tab.text = getString(R.string.tab_shell)
                SettingsPagerAdapter.TAB_DISPLAY -> tab.text = getString(R.string.tab_display)
                SettingsPagerAdapter.TAB_SYSTEM -> tab.text = getString(R.string.tab_system)
                SettingsPagerAdapter.TAB_DEVELOPER -> tab.text = getString(R.string.tab_developer)
            }
        }.attach()

        // Восстанавливаем позицию
        viewPager.setCurrentItem(currentPosition, false)

        // Обновляем ссылки на фрагменты
        updateFragmentReferences()
    }

    /**
     * Обновление ссылок на фрагменты
     */
    private fun updateFragmentReferences() {
        shellFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_SHELL}") as? ShellSettingsFragment
        displayFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_DISPLAY}") as? DisplaySettingsFragment
        systemFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_SYSTEM}") as? SystemSettingsFragment
    }

    /**
     * Настройка диспетчера для обработки нажатия системной кнопки "Назад"
     */
    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                checkAndShowRestartDialog()
            }
        })
    }

    /**
     * Настройка кнопки закрытия с проверкой изменений
     */
    private fun setupCloseButton() {
        closeButton.setOnClickListener {
            checkAndShowRestartDialog()
        }
    }

    /**
     * Проверяет флаг перезагрузки из ShellSettingsFragment и показывает диалог
     */
    private fun checkAndShowRestartDialog() {
        // Получаем флаг из ShellSettingsFragment
        val shellFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_SHELL}") as? ShellSettingsFragment
        val needsRestart = shellFragment?.getNeedsRestart() ?: false

        // Проверяем также изменения в настройках
        val settingsChanged = hasSettingsChanged()

        if (needsRestart || settingsChanged) {
            showRestartDialog()
        } else {
            finish()
        }
    }

    /**
     * Проверка, были ли изменены настройки
     * DevMode читается напрямую из SharedPreferences при каждом вызове
     */
    private fun hasSettingsChanged(): Boolean {
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        val currentDevMode = prefs.getBoolean("developer_mode", false)

        val result = currentShell != originalShell ||
                currentOrientation != originalOrientation ||
                currentFullscreen != originalFullscreen ||
                currentStrictMode != originalStrictMode ||
                currentDevMode != originalDevMode

        return result
    }

    /**
     * Показ диалога с предложением перезапустить приложение
     */
    private fun showRestartDialog() {
        // Предотвращаем множественные диалоги
        if (isRestarting) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_apply_settings_title))
            .setMessage(getString(R.string.dialog_apply_settings_message))
            .setPositiveButton(getString(R.string.dialog_restart)) { _, _ ->
                isRestarting = true
                restartApp()
            }
            .setNegativeButton(getString(R.string.dialog_later)) { _, _ ->
                // Сбрасываем флаг, чтобы диалог не появлялся снова при следующем закрытии
                val shellFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_SHELL}") as? ShellSettingsFragment
                shellFragment?.setNeedsRestart(false)
                // Просто закрываем диалог, остаёмся в настройках
                // Ничего не делаем

                // finish закомментирован чтобы невозможно было выйти без обязательного перезапуска
                // finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Перезапуск приложения с задержкой
     */
    private fun restartApp() {
        // Создаем Intent для MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // Добавляем экстра с выбранной оболочкой, если она изменилась
        if (currentShell != originalShell) {
            intent.putExtra(EXTRA_SELECTED_SHELL, currentShell)
        }

        // Запускаем с задержкой, чтобы избежать мерцания
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(intent)
            finish()
        }, DELAY_BEFORE_RESTART)
    }

    // ===== Методы интерфейса SettingsEventListener =====

    override fun onShellChanged(shellName: String) {
        currentShell = shellName
    }

    override fun onOrientationChanged(orientation: String) {
        currentOrientation = orientation
    }

    override fun onFullscreenChanged(enabled: Boolean) {
        currentFullscreen = enabled
        // Если полноэкранный режим изменился, обновляем строгий режим
        currentStrictMode = configManager.isStrictModeEnabled()
    }

    override fun onSettingChanged() {
        // При изменении настроек ничего не делаем
        // DevMode будет прочитан при выходе из настроек
    }

    /**
     * Обновление всех фрагментов (вызывается при необходимости)
     */
    fun refreshAllFragments() {
        updateFragmentReferences()
        shellFragment?.refreshShells()
        displayFragment?.refreshSettings()
        systemFragment?.refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        // Обновляем фрагменты при возврате в активность
        refreshAllFragments()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRestarting = false
    }
}