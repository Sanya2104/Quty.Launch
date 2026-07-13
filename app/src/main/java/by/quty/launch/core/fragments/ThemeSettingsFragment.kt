// *** core/fragments/ThemeSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.interfaces.SettingsEventListener

class ThemeSettingsFragment : Fragment() {

    private lateinit var themeManager: ThemeManager
    private lateinit var themesAdapter: ThemesAdapter
    private var settingsEventListener: SettingsEventListener? = null

    // Флаг для предотвращения множественных применений темы
    private var isApplyingTheme = false

    companion object {
        const val EXTRA_THEME_NAME = "theme_name"
        private const val DELAY_BEFORE_UI_UPDATE = 100L // Задержка перед обновлением UI (мс) - теперь Long
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_theme, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем SettingsActivity как listener
        settingsEventListener = activity as? SettingsEventListener

        // Инициализируем ThemeManager через активность
        (activity as? SettingsActivity)?.let { settingsActivity ->
            themeManager = settingsActivity.themeManager
        }

        setupThemeSelector(view)
    }

    override fun onResume() {
        super.onResume()
        // Сбрасываем флаг при возврате во вкладку
        isApplyingTheme = false
        // Обновляем список
        themesAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        // Сбрасываем флаг при уходе с вкладки
        isApplyingTheme = false
    }

    /**
     * Настройка выбора темы оформления с превью и информацией
     */
    private fun setupThemeSelector(view: View) {
        val themesList = view.findViewById<ListView>(R.id.themes_list)
        val themes = themeManager.getAvailableThemes()

        themesAdapter = ThemesAdapter(themes)
        themesList.adapter = themesAdapter

        themesList.setOnItemClickListener { _, _, position, _ ->
            // Предотвращаем множественные нажатия
            if (isApplyingTheme) return@setOnItemClickListener

            val selectedTheme = themes[position]
            isApplyingTheme = true

            // Применяем тему
            themeManager.setActiveTheme(selectedTheme)

            // Обновляем адаптер с задержкой, чтобы избежать мерцания
            Handler(Looper.getMainLooper()).postDelayed({
                themesAdapter.notifyDataSetChanged()
                isApplyingTheme = false
            }, DELAY_BEFORE_UI_UPDATE)

            // Уведомляем Activity об изменении темы
            settingsEventListener?.onThemeChanged(selectedTheme.name)
            settingsEventListener?.onSettingChanged()

            // Обновляем состояние во вкладке "Экран" с задержкой
            Handler(Looper.getMainLooper()).postDelayed({
                (activity as? SettingsActivity)?.let { settingsActivity ->
                    settingsActivity.displayFragment?.updateOrientationLockState()
                }
            }, DELAY_BEFORE_UI_UPDATE)

            val message = getString(R.string.theme_applied, selectedTheme.displayName ?: selectedTheme.name)
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

            // Возвращаем результат в MainActivity
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_THEME_NAME, selectedTheme.name)
            requireActivity().setResult(SettingsActivity.RESULT_THEME_CHANGED, resultIntent)
        }
    }

    /**
     * Внутренний класс адаптера для тем
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
                view.setBackgroundColor(resources.getColor(R.color.theme_active_background, null))
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            return view
        }
    }

    /**
     * Обновление списка тем (вызывается из Activity при необходимости)
     */
    fun refreshThemes() {
        isApplyingTheme = false
        themesAdapter.notifyDataSetChanged()
    }
}