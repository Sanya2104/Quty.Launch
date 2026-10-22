// *** core/fragments/store/StoreLibraryDetailFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.Shell
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.io.File

/**
 * Фрагмент детального просмотра ЛОКАЛЬНОЙ оболочки в Библиотеке.
 *
 * Отличается от StoreCatalogDetailFragment (магазин):
 * - данные из Shell (локальная оболочка), а не ShellStoreModel;
 * - превью из previewBase64 (нет previewUrl);
 * - показывает размер и путь к файлу (только для кастомных);
 * - кнопки «Поделиться» и «Удалить» (только для кастомных);
 * - «Обновить» пока не поддерживается (следующий шаг).
 *
 * Все поля из manifest.json, которые сохраняются в Shell:
 * - имя, автор, версия, тип, ориентация;
 * - minQutyLaunchVersion;
 * - repoUrl, telegramUrl.
 *
 * Не показываются:
 * - fileSize — только для кастомных (File.length());
 * - datePublished — у локальной оболочки даты нет (только на сервере);
 * - apiMethods — пока не выводим (можно добавить позже).
 *
 * Кнопка действия — динамическая:
 * - "Активна" (secondary, disabled) — активна с этой версией;
 * - "Применить" (primary) — установлена, но не активна.
 *
 * Удаление:
 * - доступно только для кастомных (isCustom = true);
 * - только если оболочка НЕ активна;
 * - с диалогом подтверждения.
 */
class StoreLibraryDetailFragment : Fragment() {

    companion object {
        private const val ARG_SHELL_NAME = "arg_shell_name"

        fun newInstance(shellName: String): StoreLibraryDetailFragment {
            return StoreLibraryDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHELL_NAME, shellName)
                }
            }
        }
    }

    private lateinit var shellName: String

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager

    private lateinit var shell: Shell

    private lateinit var btnApply: Button
    private lateinit var btnShare: Button
    private lateinit var btnDelete: Button
    private lateinit var actionsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellName = arguments?.getString(ARG_SHELL_NAME) ?: return
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_library_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configManager = (activity as? by.quty.launch.BaseActivity)?.configManager
            ?: ConfigManager(requireContext())
        shellManager = ShellManager(requireContext(), configManager)
        storageManager = StorageManager(requireContext())

        // Находим оболочку по имени
        val found = shellManager.getAvailableShells().find { it.name == shellName }
        if (found == null) {
            // Оболочки нет (удалили?) — закрываем экран
            Toast.makeText(
                requireContext(),
                R.string.shell_active_not_found,
                Toast.LENGTH_SHORT
            ).show()
            (activity as? by.quty.launch.StoreActivity)?.hideLibraryDetail()
            return
        }
        shell = found

        setupUI(view)
    }

    private fun setupUI(view: View) {
        val previewImage: ImageView = view.findViewById(R.id.shell_preview)
        val nameText: TextView = view.findViewById(R.id.shell_name)
        val authorText: TextView = view.findViewById(R.id.shell_author)
        val versionText: TextView = view.findViewById(R.id.shell_version)
        val typeText: TextView = view.findViewById(R.id.shell_type)
        val sizeText: TextView = view.findViewById(R.id.shell_size)
        val pathText: TextView = view.findViewById(R.id.shell_path)
        val orientationText: TextView = view.findViewById(R.id.shell_orientation)
        val minVersionText: TextView = view.findViewById(R.id.shell_min_version)

        val sizeContainer: LinearLayout = view.findViewById(R.id.shell_size_container)
        val sizeDivider: View = view.findViewById(R.id.shell_size_divider)
        val pathContainer: LinearLayout = view.findViewById(R.id.shell_path_container)
        val pathDivider: View = view.findViewById(R.id.shell_path_divider)
        val orientationContainer: LinearLayout = view.findViewById(R.id.shell_orientation_container)
        val orientationDivider: View = view.findViewById(R.id.shell_orientation_divider)
        val minVersionContainer: LinearLayout = view.findViewById(R.id.shell_min_version_container)
        val minVersionDivider: View = view.findViewById(R.id.shell_min_version_divider)

        val repoContainer: LinearLayout = view.findViewById(R.id.shell_repo_container)
        val repoButton: Button = view.findViewById(R.id.shell_repo_button)
        val telegramContainer: LinearLayout = view.findViewById(R.id.shell_telegram_container)
        val telegramButton: Button = view.findViewById(R.id.shell_telegram_button)

        btnApply = view.findViewById(R.id.btn_apply)
        btnShare = view.findViewById(R.id.btn_share)
        btnDelete = view.findViewById(R.id.btn_delete)
        actionsContainer = view.findViewById(R.id.shell_actions_container)

        // Превью
        bindPreview(previewImage)

        // Название и автор
        nameText.text = shell.displayName ?: shell.name
        authorText.text = shell.author ?: getString(R.string.author_default)

        // Версия
        versionText.text = "v${shell.version ?: getString(R.string.unknown)}"

        // Тип
        typeText.text = if (shell.isCustom) {
            getString(R.string.shell_type_custom)
        } else {
            getString(R.string.shell_type_builtin)
        }

        // Размер и путь — только для кастомных
        if (shell.isCustom) {
            sizeContainer.visibility = View.VISIBLE
            sizeDivider.visibility = View.VISIBLE
            pathContainer.visibility = View.VISIBLE
            pathDivider.visibility = View.VISIBLE

            val file = File(shell.sourcePath)
            if (file.exists()) {
                sizeText.text = storageManager.formatSize(file.length())
                pathText.text = file.absolutePath
            } else {
                sizeText.text = getString(R.string.unknown)
                pathText.text = shell.sourcePath
            }
        } else {
            sizeContainer.visibility = View.GONE
            sizeDivider.visibility = View.GONE
            pathContainer.visibility = View.GONE
            pathDivider.visibility = View.GONE
        }

        // Ориентация — если задана в manifest
        val orientation = shell.orientation
        if (!orientation.isNullOrEmpty()) {
            orientationContainer.visibility = View.VISIBLE
            orientationDivider.visibility = View.VISIBLE
            orientationText.text = when (orientation) {
                "portrait" -> getString(R.string.orientation_portrait)
                "landscape" -> getString(R.string.orientation_landscape)
                "sensor" -> getString(R.string.orientation_auto)
                "user" -> getString(R.string.orientation_auto)
                else -> getString(R.string.shell_info_orientation_none)
            }
        } else {
            orientationContainer.visibility = View.GONE
            orientationDivider.visibility = View.GONE
        }

        // Мин. версия Quty.Launch — если задана в manifest
        val minVersion = shell.minQutyLaunchVersion
        if (!minVersion.isNullOrEmpty()) {
            minVersionContainer.visibility = View.VISIBLE
            minVersionDivider.visibility = View.VISIBLE
            minVersionText.text = minVersion
        } else {
            minVersionContainer.visibility = View.GONE
            minVersionDivider.visibility = View.GONE
        }

        // Репозиторий
        val repoUrl = shell.repoUrl
        if (!repoUrl.isNullOrEmpty()) {
            repoContainer.visibility = View.VISIBLE
            repoButton.setOnClickListener {
                openUrl(repoUrl)
            }
        } else {
            repoContainer.visibility = View.GONE
        }

        // Telegram
        val telegramUrl = shell.telegramUrl
        if (!telegramUrl.isNullOrEmpty()) {
            telegramContainer.visibility = View.VISIBLE
            telegramButton.setOnClickListener {
                openUrl(telegramUrl)
            }
        } else {
            telegramContainer.visibility = View.GONE
        }

        // Кнопки «Поделиться» / «Удалить» — только для кастомных
        if (shell.isCustom) {
            actionsContainer.visibility = View.VISIBLE
            btnShare.setOnClickListener { shareShell() }
            btnDelete.setOnClickListener { confirmDelete() }
        } else {
            actionsContainer.visibility = View.GONE
        }

        // Кнопка «Применить» / «Активна»
        updateButtonState()
    }

    /**
     * Загружает превью из Base64. Если превью нет — placeholder.
     */
    private fun bindPreview(imageView: ImageView) {
        val base64 = shell.previewBase64
        if (base64.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_image)
            return
        }

        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            Glide.with(this)
                .load(bytes)
                .placeholder(R.drawable.ic_image)
                .error(R.drawable.ic_image)
                .centerCrop()
                .into(imageView)
        } catch (_: Exception) {
            imageView.setImageResource(R.drawable.ic_image)
        }
    }

    /**
     * Пересчитывает состояние кнопки «Применить».
     */
    private fun updateButtonState() {
        if (!isAdded) return

        val activeName = configManager.getActiveShell()
        val activeVersion = configManager.getActiveShellVersion()

        val isActive = activeName == shell.name && activeVersion == shell.version

        if (isActive) {
            // «Активна» (secondary, disabled)
            btnApply.setBackgroundResource(R.drawable.bg_glass_button_secondary)
            btnApply.setTextColor(getThemeColor(requireContext(), R.attr.textPrimaryColor))
            btnApply.text = getString(R.string.store_applied)
            btnApply.isEnabled = false
            btnApply.alpha = 0.7f
            btnApply.setOnClickListener(null)

            // Активную удалять нельзя — скрываем кнопку «Удалить»
            if (shell.isCustom) {
                btnDelete.visibility = View.GONE
            }
        } else {
            // «Применить» (primary)
            btnApply.setBackgroundResource(R.drawable.bg_glass_button_primary)
            btnApply.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            btnApply.text = getString(R.string.store_apply)
            btnApply.isEnabled = true
            btnApply.alpha = 1.0f
            btnApply.setOnClickListener { applyShell() }

            // Не активную кастомную можно удалить
            if (shell.isCustom) {
                btnDelete.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Применяет оболочку: делает её активной.
     */
    private fun applyShell() {
        viewLifecycleOwner.lifecycleScope.launch {
            val availableShell = shellManager.getAvailableShells()
                .find { it.name == shell.name }

            if (availableShell == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.store_install_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            shellManager.setActiveShell(availableShell)

            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            updateButtonState()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, shell.displayName ?: shell.name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Делится файлом оболочки.
     */
    private fun shareShell() {
        try {
            val file = File(shell.sourcePath)
            if (!file.exists()) return

            val uri = storageManager.getUri(file) ?: return

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (_: Exception) {
            // Игнорируем
        }
    }

    /**
     * Диалог подтверждения удаления.
     */
    private fun confirmDelete() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView = dialogView.findViewById<TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.store_library_detail_delete_title)
        messageView.text = getString(
            R.string.store_library_detail_delete_message,
            shell.displayName ?: shell.name
        )

        okButton.setText(R.string.store_library_detail_delete)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            performDelete()
        }

        dialog.show()
    }

    /**
     * Удаляет оболочку и закрывает экран.
     */
    private fun performDelete() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = shellManager.deleteShellByName(shell.name)

            if (success) {
                Toast.makeText(
                    requireContext(),
                    R.string.store_library_detail_delete_success,
                    Toast.LENGTH_SHORT
                ).show()

                (activity as? by.quty.launch.StoreActivity)?.hideLibraryDetail()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.store_library_detail_delete_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getThemeColor(context: android.content.Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}