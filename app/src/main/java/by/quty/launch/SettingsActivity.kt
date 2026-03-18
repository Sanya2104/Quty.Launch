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
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.adapters.SettingsPagerAdapter
import by.quty.launch.core.fragments.DisplaySettingsFragment
import by.quty.launch.core.fragments.SystemSettingsFragment
import by.quty.launch.core.fragments.ThemeSettingsFragment
import by.quty.launch.core.interfaces.SettingsEventListener
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Активность настроек лаунчера с вкладками
 * Позволяет выбирать тему оформления, ориентацию экрана и полноэкранный режим
 */
class SettingsActivity : BaseActivity(), SettingsEventListener {

    // Менеджеры - используем configManager из BaseActivity через геттер
    lateinit var themeManager: ThemeManager

    // UI компоненты
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var closeButton: Button
    private lateinit var pagerAdapter: SettingsPagerAdapter

    // Ссылки на фрагменты для обновления
    var themeFragment: ThemeSettingsFragment? = null
        private set
    var displayFragment: DisplaySettingsFragment? = null
        private set
    private var systemFragment: SystemSettingsFragment? = null

    // Переменные для отслеживания изменений
    private var originalTheme: String? = null
    private var originalOrientation: String? = null
    private var originalFullscreen: Boolean? = null

    // Текущие значения (могут меняться)
    private var currentTheme: String? = null
    private var currentOrientation: String? = null
    private var currentFullscreen: Boolean? = null

    // Флаг для предотвращения множественных перезапусков
    private var isRestarting = false

    companion object {
        const val RESULT_THEME_CHANGED = 1
        const val EXTRA_SELECTED_THEME = "selected_theme"
        private const val DELAY_BEFORE_RESTART = 500L // Задержка перед перезапуском (мс)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        applyOrientation()
        themeManager = ThemeManager(this, configManager)

        // Сохраняем исходные настройки
        originalTheme = configManager.getActiveTheme()
        originalOrientation = configManager.getOrientation()
        originalFullscreen = configManager.isFullscreenEnabled()

        // Копируем в текущие
        currentTheme = originalTheme
        currentOrientation = originalOrientation
        currentFullscreen = originalFullscreen

        // УСТАНАВЛИВАЕМ LAYOUT ПЕРЕД ВСЕМИ ОПЕРАЦИЯМИ С UI
        setContentView(R.layout.activity_settings)

        // Инициализация UI
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
        closeButton = findViewById(R.id.close_button)

        setupViewPager()
        setupCloseButton()
        setupBackPressedDispatcher()

        // Включаем иммерсивный режим ПОСЛЕ того, как View создан
        window.decorView.post {
            enableImmersiveMode()
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
                SettingsPagerAdapter.TAB_THEME -> tab.text = getString(R.string.tab_theme)
                SettingsPagerAdapter.TAB_DISPLAY -> tab.text = getString(R.string.tab_display)
                SettingsPagerAdapter.TAB_SYSTEM -> tab.text = getString(R.string.tab_system)
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
     * Обновление ссылок на фрагменты
     */
    private fun updateFragmentReferences() {
        themeFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_THEME}") as? ThemeSettingsFragment
        displayFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_DISPLAY}") as? DisplaySettingsFragment
        systemFragment = supportFragmentManager.findFragmentByTag("f${SettingsPagerAdapter.TAB_SYSTEM}") as? SystemSettingsFragment
    }

    /**
     * Настройка диспетчера для обработки нажатия системной кнопки "Назад"
     */
    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasSettingsChanged()) {
                    showRestartDialog()
                } else {
                    finish()
                }
            }
        })
    }

    /**
     * Настройка кнопки закрытия с проверкой изменений
     */
    private fun setupCloseButton() {
        closeButton.setOnClickListener {
            if (hasSettingsChanged()) {
                showRestartDialog()
            } else {
                finish()
            }
        }
    }

    /**
     * Проверка, были ли изменены настройки
     */
    private fun hasSettingsChanged(): Boolean {
        return currentTheme != originalTheme ||
                currentOrientation != originalOrientation ||
                currentFullscreen != originalFullscreen
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
                finish()
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

        // Добавляем экстра с выбранной темой, если она изменилась
        if (currentTheme != originalTheme) {
            intent.putExtra(EXTRA_SELECTED_THEME, currentTheme)
        }

        // Запускаем с задержкой, чтобы избежать мерцания
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(intent)
            finish()
        }, DELAY_BEFORE_RESTART)
    }

    // ===== Методы интерфейса SettingsEventListener =====

    override fun onThemeChanged(themeName: String) {
        currentTheme = themeName
    }

    override fun onOrientationChanged(orientation: String) {
        currentOrientation = orientation
    }

    override fun onFullscreenChanged(enabled: Boolean) {
        currentFullscreen = enabled
    }

    override fun onSettingChanged() {
        // Можем добавить дополнительную логику при любом изменении
    }

    /**
     * Обновление всех фрагментов (вызывается при необходимости)
     */
    fun refreshAllFragments() {
        updateFragmentReferences()
        themeFragment?.refreshThemes()
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