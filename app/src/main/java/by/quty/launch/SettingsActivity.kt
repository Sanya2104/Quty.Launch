// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager

/**
 * Активность настроек лаунчера
 * Позволяет выбирать тему оформления, ориентацию экрана и полноэкранный режим
 */
class SettingsActivity : BaseActivity() {

    private lateinit var themeManager: ThemeManager
    private lateinit var orientationGroup: RadioGroup
    private lateinit var fullscreenCheckbox: CheckBox

    companion object {
        const val RESULT_THEME_CHANGED = 1
        const val EXTRA_THEME_NAME = "theme_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        applyOrientation() // Применяем сохраненную ориентацию из BaseActivity
        themeManager = ThemeManager(this, configManager) // configManager из BaseActivity

        // Включаем иммерсив до отрисовки (для Android 10)
        enableImmersiveMode()

        setContentView(R.layout.activity_settings)

        // Принудительно убиваем padding (для Android 13)
        window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?.getChildAt(0)?.setPadding(0, 0, 0, 0)

        orientationGroup = findViewById(R.id.orientation_group)
        fullscreenCheckbox = findViewById(R.id.fullscreen_checkbox)  // запоминаем чекбокс

        setupThemeSelector()
        setupOrientationSelector()
        setupFullscreenSelector()   // НОВЫЙ метод
        setupVersionInfo()

        // Дублируем вызов после отрисовки (для надежности)
        window.decorView.post {
            enableImmersiveMode()
        }

        // Кнопка закрытия
        findViewById<Button>(R.id.close_button).setOnClickListener {
            finish()
        }
    }

    /**
     * Настройка выбора темы оформления
     * Отображает список доступных тем с иконками:
     * - 📦 для кастомных тем
     * - ⭐ для дефолтной темы
     */
    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        // Адаптер для отображения списка тем с иконками
        val adapter = object : ArrayAdapter<Theme>(
            this,
            android.R.layout.simple_list_item_single_choice,
            themes
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val theme = getItem(position)
                val textView = view.findViewById<TextView>(android.R.id.text1)

                if (theme != null) {
                    textView.text = when {
                        theme.isCustom -> "📦 ${theme.displayName ?: theme.name}"
                        theme.isDefault -> "⭐ ${theme.displayName ?: theme.name}"
                        else -> theme.displayName ?: theme.name
                    }
                }
                return view
            }
        }

        themesList.adapter = adapter
        themesList.choiceMode = ListView.CHOICE_MODE_SINGLE

        // Отмечаем текущую активную тему
        val activeIndex = themes.indexOfFirst { it.name == activeThemeId }
        if (activeIndex >= 0) {
            themesList.setItemChecked(activeIndex, true)
        }

        // Обработчик выбора темы
        themesList.setOnItemClickListener { _, _, position, _ ->
            val selectedTheme = themes[position]
            themeManager.setActiveTheme(selectedTheme)

            val message = getString(R.string.theme_applied, selectedTheme.displayName ?: selectedTheme.name)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            // Возвращаем результат в MainActivity
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_THEME_NAME, selectedTheme.name)
            setResult(RESULT_THEME_CHANGED, resultIntent)
            finish()
        }
    }

    /**
     * Настройка выбора ориентации экрана
     * Позволяет выбрать:
     * - Авто (следовать за системой)
     * - Портретная (вертикальная)
     * - Ландшафтная (горизонтальная)
     */
    private fun setupOrientationSelector() {
        val currentOrientation = configManager.getOrientation()

        // Устанавливаем текущее значение
        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        // Сохраняем при изменении
        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientation = when (checkedId) {
                R.id.orientation_portrait -> "portrait"
                R.id.orientation_landscape -> "landscape"
                else -> "sensor"
            }
            configManager.setOrientation(orientation)
        }
    }

    /**
     * Настройка полноэкранного режима
     */
    private fun setupFullscreenSelector() {
        // Устанавливаем текущее значение из конфига
        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()

        fullscreenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            configManager.setFullscreenEnabled(isChecked)

            // Показываем сообщение, что изменения вступят после перезапуска
            Toast.makeText(
                this,
                getString(R.string.fullscreen_changed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Отображение версии приложения
     * Получает versionName из PackageManager
     */
    private fun setupVersionInfo() {
        val versionTextView = findViewById<TextView>(R.id.version_text)
        val fullVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.version_unknown)
        }
        versionTextView.text = getString(R.string.version_format, fullVersionName)
    }
}