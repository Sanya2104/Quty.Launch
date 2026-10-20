// *** core/fragments/store/StoreDetailFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.core.utilities.UpdateHelper
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Фрагмент детального просмотра оболочки в магазине.
 *
 * Кнопка действия — динамическая:
 * - "Установить" (primary) — не установлена;
 * - "Обновить" (primary) — установлена, в магазине новее;
 * - "Активна" (secondary, disabled) — установлена, версия совпадает
 *   с магазином и реально загружена в WebView;
 * - "Применить" (primary) — установлена, версия совпадает с магазином,
 *   но не загружена в WebView (обновлена, но не применена;
 *   или не была активна).
 *
 * После установки/обновления:
 * - если оболочка была активна до этого (по имени) — вызываем
 *   setActiveShell(), чтобы active_shell_version = новая версия
 *   → кнопка "Активна";
 * - если не была активна — setActiveShell() НЕ вызываем
 *   → кнопка "Применить".
 *
 * Поле «Версия» при наличии обновления показывает
 * «vX.Y.Z → vX.Y.W», где старая версия зачёркнута и приглушена.
 *
 * При возврате в каталог StoreActivity.hideDetail() вызывает
 * StoreCatalogFragment.refresh() — статусы карточек перерисовываются.
 */
class StoreDetailFragment : Fragment() {

    companion object {
        private const val ARG_SHELL = "arg_shell"

        fun newInstance(shell: ShellStoreModel): StoreDetailFragment {
            return StoreDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHELL, Json.encodeToString(ShellStoreModel.serializer(), shell))
                }
            }
        }
    }

    private lateinit var shell: ShellStoreModel

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storeManager: StoreManager

    private lateinit var btnInstall: Button
    private lateinit var installProgressContainer: LinearLayout
    private lateinit var installProgressBar: ProgressBar
    private lateinit var installProgressText: TextView

    // Поле «Версия» — нужно для перерисовки при обновлении статусов
    private lateinit var versionValueText: TextView

    // Флаг, что установка/применение сейчас выполняется (блокирует повторный клик)
    private var isInstalling = false

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
        return inflater.inflate(R.layout.fragment_store_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configManager = (activity as? by.quty.launch.BaseActivity)?.configManager
            ?: ConfigManager(requireContext())
        shellManager = ShellManager(requireContext(), configManager)
        storeManager = StoreManager(requireContext())

        setupUI(view)
    }

    private fun setupUI(view: View) {
        val previewImage: ImageView = view.findViewById(R.id.shell_preview)
        val nameText: TextView = view.findViewById(R.id.shell_name)
        val authorText: TextView = view.findViewById(R.id.shell_author)
        versionValueText = view.findViewById(R.id.shell_version)
        val sizeText: TextView = view.findViewById(R.id.shell_size)
        val dateText: TextView = view.findViewById(R.id.shell_date)
        val typeText: TextView = view.findViewById(R.id.shell_type)
        val minVersionText: TextView = view.findViewById(R.id.shell_min_version)
        val orientationText: TextView = view.findViewById(R.id.shell_orientation)
        val repoContainer: LinearLayout = view.findViewById(R.id.shell_repo_container)
        val repoButton: Button = view.findViewById(R.id.shell_repo_button)
        val telegramContainer: LinearLayout = view.findViewById(R.id.shell_telegram_container)
        val telegramButton: Button = view.findViewById(R.id.shell_telegram_button)
        btnInstall = view.findViewById(R.id.btn_install)

        installProgressContainer = view.findViewById(R.id.install_progress_container)
        installProgressBar = view.findViewById(R.id.install_progress_bar)
        installProgressText = view.findViewById(R.id.install_progress_text)

        // Заполняем данные
        Glide.with(this)
            .load(shell.previewUrl)
            .placeholder(R.drawable.ic_image)
            .error(R.drawable.ic_image)
            .centerCrop()
            .into(previewImage)

        nameText.text = shell.displayName
        authorText.text = shell.author
        sizeText.text = shell.fileSize
        dateText.text = formatDate(shell.datePublished)
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

        // Поле «Версия» и кнопка действия
        bindVersionValue()
        updateButtonState()
    }

    /**
     * Форматирует дату из "YYYY-MM-DD" (Git) в "DD.MM.YYYY".
     */
    private fun formatDate(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)
            val date = inputFormat.parse(isoDate) ?: return isoDate
            outputFormat.format(date)
        } catch (_: Exception) {
            isoDate
        }
    }

    /**
     * Заполняет поле «Версия».
     *
     * Если у установленной оболочки версия в магазине новее —
     * показываем «vНовая → vСтарая», где старая зачёркнута и приглушена.
     * Иначе — просто «vX.Y.Z».
     */
    private fun bindVersionValue() {
        if (!isAdded) return

        val installedVersion = shellManager.getAvailableShells()
            .find { it.name == shell.name }?.version

        if (installedVersion == null ||
            !UpdateHelper.isNewerVersion(shell.version, installedVersion)
        ) {
            versionValueText.text = "v${shell.version}"
            return
        }

        val context = requireContext()
        val dimColor = getThemeColor(context, R.attr.textDimColor)
        val strikeSpan = StrikethroughSpan()
        val dimSpan = ForegroundColorSpan(dimColor)

        val builder = SpannableStringBuilder()
        builder.append("v")
        builder.append(shell.version)
        builder.append(" → v")

        val oldStart = builder.length
        builder.append(installedVersion)
        val oldEnd = builder.length

        builder.setSpan(strikeSpan, oldStart, oldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(dimSpan, oldStart, oldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        versionValueText.text = builder
    }

    /**
     * Пересчитывает состояние кнопки.
     *
     * Источник правды для «активна» — ConfigManager.getActiveShellVersion()
     * (версия, реально загруженная в WebView), а не версия файла на диске.
     */
    private fun updateButtonState() {
        if (!isAdded) return

        val installedVersion = shellManager.getAvailableShells()
            .find { it.name == shell.name }?.version

        val activeName = configManager.getActiveShell()
        val activeVersion = configManager.getActiveShellVersion()

        val isActiveSameVersion = activeName == shell.name && activeVersion == shell.version

        when {
            isActiveSameVersion -> {
                // Активна с той же версией — "Активна" (secondary, disabled)
                applyButtonStyle(isPrimary = false)
                btnInstall.text = getString(R.string.store_applied)
                btnInstall.isEnabled = false
                btnInstall.alpha = 0.7f
                btnInstall.setOnClickListener(null)
            }
            installedVersion != null && UpdateHelper.isNewerVersion(shell.version, installedVersion) -> {
                // Установлена, но в магазине новее — "Обновить" (primary)
                applyButtonStyle(isPrimary = true)
                btnInstall.text = getString(R.string.store_update)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener { installOrUpdate() }
            }
            installedVersion != null -> {
                // Установлена, версия = магазин, но не активна — "Применить" (primary)
                applyButtonStyle(isPrimary = true)
                btnInstall.text = getString(R.string.store_apply)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener { applyShell() }
            }
            else -> {
                // Не установлена — "Установить" (primary)
                applyButtonStyle(isPrimary = true)
                btnInstall.text = getString(R.string.store_install)
                btnInstall.isEnabled = true
                btnInstall.alpha = 1.0f
                btnInstall.setOnClickListener { installOrUpdate() }
            }
        }
    }

    /**
     * Применяет стиль кнопки:
     * - isPrimary = true  → bg_glass_button_primary + белый текст
     * - isPrimary = false → bg_glass_button_secondary + текст из ?attr/textPrimaryColor
     */
    private fun applyButtonStyle(isPrimary: Boolean) {
        val context = requireContext()

        if (isPrimary) {
            btnInstall.setBackgroundResource(R.drawable.bg_glass_button_primary)
            btnInstall.setTextColor(ContextCompat.getColor(context, R.color.white))
        } else {
            btnInstall.setBackgroundResource(R.drawable.bg_glass_button_secondary)
            btnInstall.setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
        }
    }

    /**
     * Резолвит цвет из атрибута темы.
     */
    private fun getThemeColor(context: android.content.Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /**
     * Установка или обновление оболочки: скачивание с прогрессом,
     * затем — активация (если оболочка была активна) или пересчёт
     * состояния кнопки (если не была).
     */
    private fun installOrUpdate() {
        if (isInstalling) return
        isInstalling = true

        showInstallProgress(0)

        // Запоминаем, была ли эта оболочка активна ДО установки.
        // Если да — после установки активируем новую версию, чтобы
        // не заставлять пользователя жать «Применить».
        val wasActive = configManager.getActiveShell() == shell.name

        viewLifecycleOwner.lifecycleScope.launch {
            val success = storeManager.installShell(
                shell = shell,
                listener = object : StoreManager.DownloadListener {
                    override fun onProgress(percent: Int) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!isAdded) return@launch
                            showInstallProgress(percent)
                        }
                    }

                    override fun onSuccess() {
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!isAdded) return@launch

                            isInstalling = false
                            hideInstallProgress()

                            // Перечитываем оболочку с диска (там уже новая версия)
                            val updatedShell = shellManager.getAvailableShells()
                                .find { it.name == shell.name }

                            if (updatedShell != null) {
                                if (wasActive) {
                                    // Была активна → активируем новую версию,
                                    // кнопка станет «Активна»
                                    shellManager.setActiveShell(updatedShell)
                                    (activity as? by.quty.launch.BaseActivity)
                                        ?.markRestartRequired()
                                } else {
                                    // Не была активна → просто обновляем
                                    // список в памяти (без активации)
                                    shellManager.reloadActiveShell()
                                }
                            }

                            bindVersionValue()
                            updateButtonState()

                            Toast.makeText(
                                requireContext(),
                                getString(R.string.store_install_success, shell.displayName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onError(message: String) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!isAdded) return@launch

                            isInstalling = false
                            hideInstallProgress()

                            // Восстанавливаем кнопку в исходное состояние
                            updateButtonState()

                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )

            // Дополнительная страховка: если installShell вернул false,
            // но listener не сработал — снимаем флаг.
            if (!success && isInstalling) {
                isInstalling = false
                if (isAdded) {
                    hideInstallProgress()
                    updateButtonState()
                }
            }
        }
    }

    /**
     * Применяет оболочку: делает её активной.
     */
    private fun applyShell() {
        if (isInstalling) return
        isInstalling = true

        viewLifecycleOwner.lifecycleScope.launch {
            val availableShell = shellManager.getAvailableShells()
                .find { it.name == shell.name }

            if (availableShell == null) {
                isInstalling = false
                Toast.makeText(
                    requireContext(),
                    R.string.store_install_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            shellManager.setActiveShell(availableShell)

            isInstalling = false

            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            updateButtonState()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, shell.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Показывает прогресс-бар установки с указанным процентом.
     * Скрывает кнопку действия, чтобы не отвлекала.
     */
    private fun showInstallProgress(percent: Int) {
        btnInstall.visibility = View.GONE
        installProgressContainer.visibility = View.VISIBLE
        installProgressBar.isIndeterminate = false
        installProgressBar.progress = percent
        installProgressText.text = getString(R.string.downloading_progress, percent)
    }

    /**
     * Скрывает прогресс-бар и возвращает кнопку действия.
     */
    private fun hideInstallProgress() {
        installProgressContainer.visibility = View.GONE
        btnInstall.visibility = View.VISIBLE
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