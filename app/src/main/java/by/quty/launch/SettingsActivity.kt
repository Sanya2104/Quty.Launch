// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
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
        applyOrientation()
        themeManager = ThemeManager(this, configManager)

        // Включаем иммерсив до отрисовки (для Android 10)
        enableImmersiveMode()

        setContentView(R.layout.activity_settings)

        // Принудительно убиваем padding (для Android 13)
        window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?.getChildAt(0)?.setPadding(0, 0, 0, 0)

        orientationGroup = findViewById(R.id.orientation_group)
        fullscreenCheckbox = findViewById(R.id.fullscreen_checkbox)

        setupThemeSelector()
        setupOrientationSelector()
        setupFullscreenSelector()
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
     * Настройка выбора темы оформления с превью и информацией
     */
    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        // Кастомный адаптер для отображения тем с превью
        val adapter = object : ArrayAdapter<Theme>(this, 0, themes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_theme, parent, false)
                val theme = getItem(position)

                val previewView = view.findViewById<ImageView>(R.id.theme_preview)
                val nameView = view.findViewById<TextView>(R.id.theme_name)
                val versionView = view.findViewById<TextView>(R.id.theme_version)
                val authorView = view.findViewById<TextView>(R.id.theme_author)
                val radioView = view.findViewById<RadioButton>(R.id.theme_radio)

                // Устанавливаем название
                nameView.text = theme?.displayName ?: theme?.name ?: "Unknown"

                // Устанавливаем версию
                versionView.text = if (!theme?.version.isNullOrEmpty()) {
                    "v${theme.version}"
                } else {
                    ""
                }

                // Устанавливаем автора
                authorView.text = theme?.author ?: if (theme?.isCustom == true) "Пользовательская" else "Quty"

                // Устанавливаем превью
                if (!theme?.previewBase64.isNullOrEmpty()) {
                    try {
                        val imageBytes = Base64.decode(theme.previewBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        previewView.setImageBitmap(bitmap)
                        previewView.visibility = View.VISIBLE
                    } catch (_: Exception) {
                        previewView.setImageResource(R.drawable.ic_settings)
                    }
                } else {
                    previewView.setImageResource(R.drawable.ic_settings)
                }

                // Отмечаем радио-кнопку если тема активна
                radioView.isChecked = theme?.name == activeThemeId

                return view
            }
        }

        themesList.adapter = adapter
        themesList.choiceMode = ListView.CHOICE_MODE_SINGLE

        themesList.setOnItemClickListener { _, view, position, _ ->
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
     */
    private fun setupOrientationSelector() {
        val currentOrientation = configManager.getOrientation()

        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        // Устанавливаем текущее значение
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
     * Получает versionName из PackageManager и форматирует с пробелом
     */
    private fun setupVersionInfo() {
        val versionTextView = findViewById<TextView>(R.id.version_text)
        val fullVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.version_unknown)
        }

        // Добавляем пробел между цифрами и суффиксом (например, "0.0.3 alpha")
        val versionText = fullVersionName?.replace(Regex("([0-9.]+)([a-zA-Z].*)"), "$1 $2")
            ?: getString(R.string.version_unknown)

        versionTextView.text = getString(R.string.version_format, versionText)
    }
}