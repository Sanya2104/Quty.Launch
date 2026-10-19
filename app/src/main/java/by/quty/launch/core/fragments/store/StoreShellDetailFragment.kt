// *** core/fragments/store/StoreShellDetailFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.core.utilities.UpdateHelper
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Фрагмент детального просмотра оболочки в магазине.
 *
 * Отображает информацию аналогично диалогу "Информация об оболочке".
 * Кнопка действия внизу — "Установить" / "Обновить" / "Применить" / "Применено".
 */
class StoreShellDetailFragment : Fragment() {

    companion object {
        private const val ARG_SHELL = "arg_shell"

        fun newInstance(shell: ShellStoreModel): StoreShellDetailFragment {
            return StoreShellDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHELL, Json.encodeToString(ShellStoreModel.serializer(), shell))
                }
            }
        }
    }

    private lateinit var shell: ShellStoreModel
    private lateinit var btnInstall: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jsonString = arguments?.getString(ARG_SHELL) ?: return
        shell = Json.decodeFromString(ShellStoreModel.serializer(), jsonString)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_shell_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI(view)
    }

    private fun setupUI(view: View) {
        val previewImage: ImageView = view.findViewById(R.id.shell_preview)
        val nameText: TextView = view.findViewById(R.id.shell_name)
        val authorText: TextView = view.findViewById(R.id.shell_author)
        val versionText: TextView = view.findViewById(R.id.shell_version)
        val typeText: TextView = view.findViewById(R.id.shell_type)
        val minVersionText: TextView = view.findViewById(R.id.shell_min_version)
        val orientationText: TextView = view.findViewById(R.id.shell_orientation)
        val repoContainer: LinearLayout = view.findViewById(R.id.shell_repo_container)
        val repoButton: Button = view.findViewById(R.id.shell_repo_button)
        val telegramContainer: LinearLayout = view.findViewById(R.id.shell_telegram_container)
        val telegramButton: Button = view.findViewById(R.id.shell_telegram_button)
        btnInstall = view.findViewById(R.id.btn_install)

        // Заполняем данные
        Glide.with(this)
            .load(shell.previewUrl)
            .placeholder(R.drawable.ic_image)
            .error(R.drawable.ic_image)
            .centerCrop()
            .into(previewImage)

        nameText.text = shell.displayName
        authorText.text = shell.author
        versionText.text = shell.version
        typeText.text = getString(R.string.shell_type_custom)
        minVersionText.text = shell.minQutyLaunchVersion

        // Ориентация
        orientationText.text = when (shell.orientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            "sensor" -> getString(R.string.orientation_auto)
            "user" -> getString(R.string.orientation_auto)
            else -> getString(R.string.shell_info_orientation_none)
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
        val telegramUrl = shell.telegram
        if (!telegramUrl.isNullOrEmpty()) {
            telegramContainer.visibility = View.VISIBLE
            telegramButton.setOnClickListener {
                openUrl(telegramUrl)
            }
        } else {
            telegramContainer.visibility = View.GONE
        }

        // Кнопка действия
        updateButtonState()
    }

    private fun updateButtonState() {
        val activity = activity ?: return
        val shellManager = by.quty.launch.core.managers.ShellManager(
            activity,
            (activity as? by.quty.launch.BaseActivity)?.configManager
                ?: by.quty.launch.core.managers.ConfigManager(activity)
        )

        // Установленная версия — из getAvailableShells()
        val installedShell = shellManager.getAvailableShells()
            .find { it.name == shell.name }
        val installedVersion = installedShell?.version

        // Активная оболочка — из getActiveShell()
        val activeShell = shellManager.getActiveShell()
        val isActiveSameVersion =
            activeShell?.name == shell.name && activeShell.version == shell.version

        when {
            isActiveSameVersion -> {
                // Активна с той же версией
                btnInstall.text = getString(R.string.store_applied)
                btnInstall.isEnabled = false
                btnInstall.alpha = 0.5f
                btnInstall.setOnClickListener(null)
            }
            installedVersion != null && UpdateHelper.isNewerVersion(shell.version, installedVersion) -> {
                // Установлена, но версия в магазине новее — "Обновить"
                btnInstall.text = getString(R.string.store_update)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener {
                    // Установка через StoreShellsFragment — возвращаемся к списку
                    Toast.makeText(
                        requireContext(),
                        R.string.store_update_hint,
                        Toast.LENGTH_LONG
                    ).show()
                    (activity as? StoreActivity)?.hideDetail()
                }
            }
            installedVersion != null -> {
                // Установлена, версия совпадает (или магазин старее), но не активна
                btnInstall.text = getString(R.string.store_apply)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener {
                    applyShell(shellManager)
                }
            }
            else -> {
                // Не установлена — "Установить"
                btnInstall.text = getString(R.string.store_install)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener {
                    Toast.makeText(
                        requireContext(),
                        R.string.store_install_hint,
                        Toast.LENGTH_LONG
                    ).show()
                    (activity as? StoreActivity)?.hideDetail()
                }
            }
        }
    }

    private fun applyShell(shellManager: by.quty.launch.core.managers.ShellManager) {
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

            // Требуется перезапуск — сообщаем об этом через активность
            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            // Обновляем состояние кнопки
            updateButtonState()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, shell.displayName),
                Toast.LENGTH_SHORT
            ).show()
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
}