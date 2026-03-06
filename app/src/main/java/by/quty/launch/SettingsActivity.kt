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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
    private lateinit var themesAdapter: ThemesAdapter
    private lateinit var closeButton: Button

    // Переменные для отслеживания изменений
    private var originalTheme: String? = null
    private var originalOrientation: String? = null
    private var originalFullscreen: Boolean? = null

    // Текущие значения (могут меняться)
    private var currentTheme: String? = null
    private var currentOrientation: String? = null
    private var currentFullscreen: Boolean? = null

    companion object {
        const val RESULT_THEME_CHANGED = 1
        const val EXTRA_THEME_NAME = "theme_name"
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

        // Включаем иммерсив до отрисовки (для Android 10)
        enableImmersiveMode()

        setContentView(R.layout.activity_settings)

        // Принудительно убиваем padding (для Android 13)
        window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?.getChildAt(0)?.setPadding(0, 0, 0, 0)

        orientationGroup = findViewById(R.id.orientation_group)
        fullscreenCheckbox = findViewById(R.id.fullscreen_checkbox)
        closeButton = findViewById(R.id.close_button)

        setupThemeSelector()
        setupOrientationSelector()
        setupFullscreenSelector()
        setupVersionInfo()
        setupCloseButton()
        setupBackPressedDispatcher()

        // Дублируем вызов после отрисовки (для надежности)
        window.decorView.post {
            enableImmersiveMode()
        }
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_apply_settings_title))
            .setMessage(getString(R.string.dialog_apply_settings_message))
            .setPositiveButton(getString(R.string.dialog_restart)) { _, _ ->
                restartApp()
            }
            .setNegativeButton(getString(R.string.dialog_later)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Перезапуск приложения
     */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    /**
     * Настройка выбора темы оформления с превью и информацией
     * Активная тема подсвечивается цветом вместо RadioButton
     */
    private fun setupThemeSelector() {
        val themesList = findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()

        themesAdapter = ThemesAdapter(themes)
        themesList.adapter = themesAdapter

        themesList.setOnItemClickListener { _, _, position, _ ->
            val selectedTheme = themes[position]

            // Применяем тему
            themeManager.setActiveTheme(selectedTheme)

            // Обновляем текущую тему
            currentTheme = selectedTheme.name

            // Обновляем адаптер, чтобы подсветка изменилась
            themesAdapter.notifyDataSetChanged()

            val message = getString(R.string.theme_applied, selectedTheme.displayName ?: selectedTheme.name)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            // Возвращаем результат в MainActivity
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_THEME_NAME, selectedTheme.name)
            setResult(RESULT_THEME_CHANGED, resultIntent)
        }
    }

    /**
     * Внутренний класс адаптера для тем
     * Использует актуальное состояние activeTheme из ThemeManager при каждом обновлении
     */
    inner class ThemesAdapter(private val themes: List<Theme>) : BaseAdapter() {

        override fun getCount(): Int = themes.size

        override fun getItem(position: Int): Theme = themes[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_theme, parent, false)
            val theme = getItem(position)

            val previewView = view.findViewById<ImageView>(R.id.theme_preview)
            val nameView = view.findViewById<TextView>(R.id.theme_name)
            val versionView = view.findViewById<TextView>(R.id.theme_version)
            val authorView = view.findViewById<TextView>(R.id.theme_author)

            // Устанавливаем название
            nameView.text = theme.displayName ?: theme.name

            // Устанавливаем версию
            versionView.text = if (!theme.version.isNullOrEmpty()) {
                "v.${theme.version}"
            } else {
                ""
            }

            // Устанавливаем автора
            authorView.text = theme.author ?: if (theme.isCustom) {
                getString(R.string.author_custom)
            } else {
                getString(R.string.author_default)
            }

            // Устанавливаем превью
            if (!theme.previewBase64.isNullOrEmpty()) {
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

            // Получаем актуальную активную тему из менеджера
            val activeTheme = themeManager.getActiveTheme()

            // Подсвечиваем активную тему
            if (theme.name == activeTheme?.name) {
                view.setBackgroundColor(getColor(R.color.theme_active_background))
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            return view
        }
    }

    /**
     * Настройка выбора ориентации экрана
     */
    private fun setupOrientationSelector() {
        // Используем свойство класса, а не локальную переменную
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
            currentOrientation = orientation // Обновляем свойство класса
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
            currentFullscreen = isChecked // Обновляем текущее значение

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