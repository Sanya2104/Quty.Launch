// *** ParametersActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.viewpager2.widget.ViewPager2
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.adapters.ParametersPagerAdapter
import by.quty.launch.core.fragments.parameters.DisplayFragment
import by.quty.launch.core.fragments.parameters.ShellFragment
import by.quty.launch.core.interfaces.ParametersEventListener
import by.quty.launch.core.managers.ShellManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Активность параметров Quty.Launch с вкладками
 * Позволяет выбирать оболочку оформления, ориентацию экрана и полноэкранный режим
 */
class ParametersActivity : BaseActivity(), ParametersEventListener {

    // Менеджеры - используем configManager из BaseActivity через геттер
    lateinit var shellManager: ShellManager

    // UI компоненты
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var closeButton: Button
    private lateinit var pagerAdapter: ParametersPagerAdapter

    // Ссылки на фрагменты для обновления
    var shellFragment: ShellFragment? = null
        private set
    var displayFragment: DisplayFragment? = null
        private set

    // Переменные для отслеживания изменений
    private var originalShell: String? = null
    private var originalOrientation: String? = null
    private var originalFullscreen: Boolean? = null
    private var originalStrictMode: Boolean? = null
    private var originalDevMode: Boolean? = null
    private var originalColorScheme: String? = null

    // Текущие значения (могут меняться)
    private var currentShell: String? = null
    private var currentOrientation: String? = null
    private var currentFullscreen: Boolean? = null
    private var currentStrictMode: Boolean? = null
    private var currentColorScheme: String? = null

    // Флаг для предотвращения множественных перезапусков
    private var isRestarting = false

    companion object {
        // Результат изменения оболочки (из конфига)
        const val RESULT_SHELL_CHANGED = CoreConfig.RESULT_SHELL_CHANGED

        // Ключ для передачи выбранной оболочки в Intent (из конфига)
        const val EXTRA_SELECTED_SHELL = CoreConfig.EXTRA_SELECTED_SHELL

        // Задержка перед перезапуском (из конфига)
        private const val DELAY_BEFORE_RESTART = CoreConfig.DELAY_BEFORE_RESTART_MS

        // Индексы вкладок (оставляем локально)
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
        originalColorScheme = configManager.getColorScheme()

        // Сохраняем исходное состояние DevMode (фиксируется при первом открытии параметров)
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        originalDevMode = prefs.getBoolean("developer_mode", false)

        // Копируем в текущие
        currentShell = originalShell
        currentOrientation = originalOrientation
        currentFullscreen = originalFullscreen
        currentStrictMode = originalStrictMode
        currentColorScheme = originalColorScheme

        // УСТАНАВЛИВАЕМ LAYOUT ПЕРЕД ВСЕМИ ОПЕРАЦИЯМИ С UI
        setContentView(R.layout.activity_parameters)

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
        pagerAdapter = ParametersPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Привязываем TabLayout к ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                ParametersPagerAdapter.TAB_SHELL -> tab.text = getString(R.string.tab_parameters_shell)
                ParametersPagerAdapter.TAB_DISPLAY -> tab.text = getString(R.string.tab_parameters_display)
                ParametersPagerAdapter.TAB_SYSTEM -> tab.text = getString(R.string.tab_parameters_system)
                ParametersPagerAdapter.TAB_DEVELOPER -> tab.text = getString(R.string.tab_parameters_developer)
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
     * Восстанавливает позицию вкладки из Intent
     * При обычном запуске — открывает "Оформление" (индекс 0)
     * При переключении DevMode — открывает "Система" (индекс 2) через Intent
     */
    private fun restoreTabPosition() {
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
        pagerAdapter = ParametersPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Перепривязываем TabLayout
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                ParametersPagerAdapter.TAB_SHELL -> tab.text = getString(R.string.tab_parameters_shell)
                ParametersPagerAdapter.TAB_DISPLAY -> tab.text = getString(R.string.tab_parameters_display)
                ParametersPagerAdapter.TAB_SYSTEM -> tab.text = getString(R.string.tab_parameters_system)
                ParametersPagerAdapter.TAB_DEVELOPER -> tab.text = getString(R.string.tab_parameters_developer)
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
        shellFragment = supportFragmentManager.findFragmentByTag("f${ParametersPagerAdapter.TAB_SHELL}") as? ShellFragment
        displayFragment = supportFragmentManager.findFragmentByTag("f${ParametersPagerAdapter.TAB_DISPLAY}") as? DisplayFragment
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
     * Проверяет флаг перезагрузки из ShellFragment и показывает диалог
     * Диалог показывается в ЛЮБОМ случае, если были изменения
     */
    private fun checkAndShowRestartDialog() {
        // Получаем флаг из ShellFragment
        val shellFragment = supportFragmentManager.findFragmentByTag("f${ParametersPagerAdapter.TAB_SHELL}") as? ShellFragment

        // Проверяем, есть ли изменения, требующие перезапуска (оболочка, ориентация и т.д.)
        val needsRestart = shellFragment?.getNeedsRestart() ?: false

        // Проверяем также изменения в настройках
        val parametersChanged = hasParametersChanged()

        // Если есть ЛЮБЫЕ изменения — показываем диалог
        if (needsRestart || parametersChanged) {
            showRestartDialog()
        } else {
            // Сбрасываем флаг изменения цветовой схемы, если он был установлен
            shellFragment?.resetColorSchemeChangedFlag()
            finish()
        }
    }

    /**
     * Проверка, были ли изменены настройки
     * DevMode читается напрямую из SharedPreferences при каждом вызове
     */
    private fun hasParametersChanged(): Boolean {
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        val currentDevMode = prefs.getBoolean("developer_mode", false)

        return currentShell != originalShell ||
                currentOrientation != originalOrientation ||
                currentFullscreen != originalFullscreen ||
                currentStrictMode != originalStrictMode ||
                currentDevMode != originalDevMode ||
                currentColorScheme != originalColorScheme
    }

    /**
     * Показ диалога с предложением перезапустить приложение
     */
    private fun showRestartDialog() {
        // Предотвращаем множественные диалоги
        if (isRestarting) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_apply_parameters_title))
            .setMessage(getString(R.string.dialog_apply_parameters_message))
            .setPositiveButton(getString(R.string.dialog_restart)) { _, _ ->
                isRestarting = true
                // Сбрасываем флаг изменения цветовой схемы
                val shellFragment = supportFragmentManager.findFragmentByTag("f${ParametersPagerAdapter.TAB_SHELL}") as? ShellFragment
                shellFragment?.resetColorSchemeChangedFlag()
                restartApp()
            }
            .setNegativeButton(getString(R.string.dialog_later)) { _, _ ->
                // Сбрасываем флаг, чтобы диалог не появлялся снова при следующем закрытии
                val shellFragment = supportFragmentManager.findFragmentByTag("f${ParametersPagerAdapter.TAB_SHELL}") as? ShellFragment
                shellFragment?.setNeedsRestart(false)
                shellFragment?.resetColorSchemeChangedFlag()
                // Просто закрываем диалог, остаёмся в параметрах
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

    // ===== Методы интерфейса ParametersEventListener =====

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
        // DevMode будет прочитан при выходе из параметров
        // Цветовая схема обновляется через ShellFragment
        currentColorScheme = configManager.getColorScheme()
    }

    /**
     * Обновление всех фрагментов (вызывается при необходимости)
     */
    fun refreshAllFragments() {
        updateFragmentReferences()
        shellFragment?.refreshShells()
        displayFragment?.refreshParameters()
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