// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.pm.PackageManager
import by.quty.launch.core.ConfigManager
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager


class SettingsActivity : AppCompatActivity() {

    private lateinit var themeManager: ThemeManager
    private lateinit var configManager: ConfigManager

    companion object {
        const val RESULT_THEME_CHANGED = 1
        const val EXTRA_THEME_NAME = "theme_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configManager = ConfigManager(this)
        themeManager = ThemeManager(this, configManager)

        setContentView(R.layout.activity_settings)

        setupThemeSelector()
        setupVersionInfo()
        setupFullScreen()

        // Кнопка закрытия
        findViewById<Button>(R.id.close_button).setOnClickListener {
            finish()
        }
    }

    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()

        // Получаем активную тему
        val activeTheme = themeManager.getActiveTheme()
        val activeThemeId = configManager.getActiveTheme() // для надёжности

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
            themesList.setSelection(activeIndex) // прокручиваем к активной теме
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