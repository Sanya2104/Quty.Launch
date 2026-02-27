// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.pm.ActivityInfo  // Добавить импорт
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
        super.onCreate(savedInstanceState)

        // Инициализация
        configManager = ConfigManager(this)
        applyOrientation()
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

    // Применяем сохраненную ориентацию
    private fun applyOrientation() {
        val orientation = configManager.getOrientation()

        requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        android.util.Log.d("SettingsActivity", "Applied orientation: $orientation")
    }

    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()

        // Получаем активную тему
        val activeTheme = themeManager.getActiveTheme()
        val activeThemeId = configManager.getActiveTheme()

        // Создаем адаптер с радио-кнопками
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
                } else {
                    textView.text = "Неизвестная тема"
                }

                textView.setTextColor(android.graphics.Color.WHITE)
                return view
            }
        }

        themesList.adapter = adapter
        themesList.choiceMode = ListView.CHOICE_MODE_SINGLE

        // Отмечаем текущую тему по ИМЕНИ, а не по sourcePath
        val activeIndex = themes.indexOfFirst { it.name == activeThemeId }

        if (activeIndex >= 0) {
            themesList.setItemChecked(activeIndex, true)
            themesList.setSelection(activeIndex)
        } else {
            // Если не нашли по имени, пробуем другие варианты
            val fallbackIndex = themes.indexOfFirst {
                it.name == activeTheme?.name || it.sourcePath == activeTheme?.sourcePath
            }
            if (fallbackIndex >= 0) {
                themesList.setItemChecked(fallbackIndex, true)
                themesList.setSelection(fallbackIndex)
            }
        }

        themesList.setOnItemClickListener { _, _, position, _ ->
            val selectedTheme = themes[position]

            // Применяем тему
            themeManager.setActiveTheme(selectedTheme)

            Toast.makeText(
                this,
                "Тема '${selectedTheme.displayName ?: selectedTheme.name}' применена",
                Toast.LENGTH_SHORT
            ).show()

            // Создаем Intent для возврата результата
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_THEME_NAME, selectedTheme.name)
            setResult(RESULT_THEME_CHANGED, resultIntent)

            // Закрываем настройки
            finish()
        }
    }

    // Метод для выбора ориентации
    private fun setupOrientationSelector() {
        val currentOrientation = configManager.getOrientation()

        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientation = when (checkedId) {
                R.id.orientation_portrait -> "portrait"
                R.id.orientation_landscape -> "landscape"
                else -> "sensor"
            }

            configManager.setOrientation(orientation)

            Toast.makeText(
                this,
                "Ориентация изменена. Перезапустите приложение.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupVersionInfo() {
        val versionTextView = findViewById<TextView>(R.id.version_text)

        // Получаем версию из PackageManager
        val fullVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }

        // Разделяем версию на основную часть и суффикс
        // Предполагаем, что суффикс начинается с буквы после цифр
        val versionText = fullVersionName?.replace(Regex("([0-9.]+)([a-zA-Z].*)"), "$1 $2")
        versionTextView.text = "v: $versionText"
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}