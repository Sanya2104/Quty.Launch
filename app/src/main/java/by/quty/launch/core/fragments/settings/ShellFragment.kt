// *** core/fragments/settings/ShellFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.StoreActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "Персонализация" для Настроек
 * Показывает информацию об активной оболочке оформления.
 */
class ShellFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // Карточка активной оболочки
    private lateinit var shellCard: View
    private lateinit var shellEmpty: TextView
    private lateinit var shellPreview: ImageView
    private lateinit var shellName: TextView
    private lateinit var shellVersion: TextView
    private lateinit var shellMenuButton: ImageButton

    // Ссылка на магазин
    private lateinit var storeRow: View

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
        shellMenuButton = view.findViewById(R.id.shell_menu_button)
        storeRow = view.findViewById(R.id.store_row)

        // Кнопка сразу открывает диалог с информацией об активной оболочке
        shellMenuButton.setOnClickListener {
            showShellInfoDialog()
        }

        // Ссылка на магазин
        storeRow.setOnClickListener {
            openStore()
        }

        refreshActiveShell()
    }

    override fun onResume() {
        super.onResume()

        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }

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

        shellName.text = shell.displayName ?: shell.name

        shellVersion.text = if (!shell.version.isNullOrEmpty()) {
            getString(R.string.shell_version_with_label, shell.version)
        } else {
            getString(R.string.shell_version_with_label, getString(R.string.unknown))
        }

        loadPreview(shell.previewBase64)
    }

    /**
     * Загружает превью из Base64. Если пусто или ошибка — fallback.
     */
    private fun loadPreview(base64: String?) {
        if (base64.isNullOrEmpty()) {
            shellPreview.setImageResource(R.drawable.ic_image)
            return
        }

        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                shellPreview.setImageBitmap(bitmap)
            } else {
                shellPreview.setImageResource(R.drawable.ic_image)
            }
        } catch (_: Exception) {
            shellPreview.setImageResource(R.drawable.ic_image)
        }
    }

    /**
     * Открывает магазин оболочек.
     */
    private fun openStore() {
        try {
            val intent = Intent(requireContext(), StoreActivity::class.java)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.store_load_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Показывает диалог с информацией об активной оболочке.
     * Использует прозрачную тему диалога, чтобы был виден CardView со скруглением.
     */
    @SuppressLint("InflateParams")
    private fun showShellInfoDialog() {
        val shell = shellManager.getActiveShell()
        if (shell == null) {
            Toast.makeText(requireContext(), R.string.settings_personalization_no_shell, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_shell_info, null)

        // Заполняем поля
        dialogView.findViewById<TextView>(R.id.shell_info_name_value).text =
            shell.displayName ?: shell.name

        dialogView.findViewById<TextView>(R.id.shell_info_version_value).text =
            shell.version ?: getString(R.string.unknown)

        dialogView.findViewById<TextView>(R.id.shell_info_author_value).text =
            shell.author ?: getString(R.string.author_default)

        dialogView.findViewById<TextView>(R.id.shell_info_type_value).text =
            if (shell.isCustom) getString(R.string.shell_type_custom)
            else getString(R.string.shell_type_builtin)

        dialogView.findViewById<TextView>(R.id.shell_info_min_version_value).text =
            shell.minQutyLaunchVersion
                ?: getString(R.string.shell_info_min_version_not_specified)

        dialogView.findViewById<TextView>(R.id.shell_info_orientation_value).text =
            mapOrientation(shell.orientation)

        // Репозиторий
        val repoContainer = dialogView.findViewById<LinearLayout>(R.id.shell_info_repo_container)
        val repoButton = dialogView.findViewById<Button>(R.id.shell_info_repo_button)

        if (!shell.repoUrl.isNullOrEmpty()) {
            repoContainer.visibility = View.VISIBLE
            repoButton.setOnClickListener {
                openUrl(shell.repoUrl)
            }
        } else {
            repoContainer.visibility = View.GONE
        }

        // Создаём диалог с прозрачной темой — фон прозрачный, чтобы был виден CardView со скруглением
        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .create()

        // Прозрачный фон окна диалога
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        // Кнопка-крестик закрывает диалог
        dialogView.findViewById<ImageButton>(R.id.shell_info_close_button).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Преобразует код ориентации в читаемый текст.
     */
    private fun mapOrientation(orientation: String?): String {
        return when (orientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            "sensor" -> getString(R.string.orientation_auto)
            "user" -> getString(R.string.orientation_auto)
            else -> getString(R.string.shell_info_orientation_none)
        }
    }

    /**
     * Открывает URL во внешнем браузере.
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
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