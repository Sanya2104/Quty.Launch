// *** SettingsActivity.kt *** //
package by.quty.launch

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import by.quty.launch.core.ConfigManager
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var themeManager: ThemeManager
    private lateinit var configManager: ConfigManager
    private lateinit var orientationGroup: RadioGroup

    companion object {
        const val RESULT_THEME_CHANGED = 1
        const val EXTRA_THEME_NAME = "theme_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Применяем ориентацию ДО super.onCreate
        // Прозрачная тема скрывает начальный скачок
        configManager = ConfigManager(this)
        val orientation = configManager.getOrientation()

        requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        super.onCreate(savedInstanceState)

        // Инициализация
        themeManager = ThemeManager(this, configManager)
        setContentView(R.layout.activity_settings)
        orientationGroup = findViewById(R.id.orientation_group)
        setupThemeSelector()
        setupOrientationSelector()
        setupVersionInfo()
        setupFullScreen()

        // Кнопка закрытия
        findViewById<Button>(R.id.close_button).setOnClickListener {
            finish()
        }
    }

    /**
     * Настройка выбора темы оформления
     */
    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        // Адаптер для отображения списка тем
        val adapter = object : ArrayAdapter<Theme>(
            this,
            android.R.layout.simple_list_item_single_choice,
            themes
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
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

            // Возвращаем результат в MainActivity
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_THEME_NAME, selectedTheme.name)
            setResult(RESULT_THEME_CHANGED, resultIntent)
            finish()
        }
    }

    /**
     * Настройка выбора ориентации экрана
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
     * Отображение версии приложения
     */
    @SuppressLint("SetTextI18n")
    private fun setupVersionInfo() {
        val versionTextView = findViewById<TextView>(R.id.version_text)
        val fullVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
        versionTextView.text = "v: $fullVersionName"
    }

    /**
     * Установка полноэкранного режима
     */
    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}