// *** core/fragments/settings/ShellFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "Персонализация" для Настроек
 * Показывает информацию об активной оболочке оформления.
 *
 * Функционал будет расширяться (цветовые схемы, магазин и т.д.)
 */
class ShellFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // Карточка активной оболочки
    private lateinit var shellCard: LinearLayout
    private lateinit var shellEmpty: TextView
    private lateinit var shellPreview: ImageView
    private lateinit var shellName: TextView
    private lateinit var shellVersion: TextView
    private lateinit var shellAuthor: TextView

    // Детали
    private lateinit var shellType: TextView
    private lateinit var shellMinVersion: TextView

    // Флаг, что требуется перезагрузка
    private var needsRestart = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_shell, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)
            needsRestart = settingsActivity.getNeedsRestart()
        }

        shellCard = view.findViewById(R.id.shell_card)
        shellEmpty = view.findViewById(R.id.shell_empty)
        shellPreview = view.findViewById(R.id.shell_preview)
        shellName = view.findViewById(R.id.shell_name)
        shellVersion = view.findViewById(R.id.shell_version)
        shellAuthor = view.findViewById(R.id.shell_author)
        shellType = view.findViewById(R.id.shell_type_value)
        shellMinVersion = view.findViewById(R.id.shell_min_version_value)

        refreshActiveShell()
    }

    override fun onResume() {
        super.onResume()

        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }

        // Обновляем информацию об активной оболочке при каждом возврате
        refreshActiveShell()
    }

    /**
     * Читает активную оболочку и заполняет карточку.
     */
    private fun refreshActiveShell() {
        val shell = shellManager.getActiveShell()

        if (shell == null) {
            shellCard.visibility = View.GONE
            shellEmpty.visibility = View.VISIBLE
            return
        }

        shellCard.visibility = View.VISIBLE
        shellEmpty.visibility = View.GONE

        // Название
        shellName.text = shell.displayName ?: shell.name

        // Версия
        shellVersion.text = if (!shell.version.isNullOrEmpty()) {
            getString(R.string.shell_version_with_label, shell.version)
        } else {
            getString(R.string.shell_version_with_label, getString(R.string.unknown))
        }

        // Автор
        shellAuthor.text = shell.author ?: getString(R.string.author_default)

        // Тип
        shellType.text = if (shell.isCustom) {
            getString(R.string.shell_type_custom)
        } else {
            getString(R.string.shell_type_builtin)
        }

        // Минимальная версия Quty.Launch
        shellMinVersion.text = shell.minQutyLaunchVersion
            ?: getString(R.string.shell_info_min_version_not_specified)

        // Превью
        loadPreview(shell.previewBase64)
    }

    /**
     * Загружает превью из Base64. Если пусто или ошибка — fallback.
     */
    private fun loadPreview(base64: String?) {
        if (base64.isNullOrEmpty()) {
            shellPreview.setImageResource(R.drawable.ic_parameters)
            return
        }

        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                shellPreview.setImageBitmap(bitmap)
            } else {
                shellPreview.setImageResource(R.drawable.ic_parameters)
            }
        } catch (_: Exception) {
            shellPreview.setImageResource(R.drawable.ic_parameters)
        }
    }

    /**
     * Возвращает флаг необходимости перезагрузки
     */
    fun getNeedsRestart(): Boolean = needsRestart

    /**
     * Устанавливает флаг необходимости перезагрузки
     */
    fun setNeedsRestart(value: Boolean) {
        needsRestart = value
    }
}