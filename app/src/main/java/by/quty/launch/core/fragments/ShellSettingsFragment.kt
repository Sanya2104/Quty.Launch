// *** core/fragments/ShellSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.Shell
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.ShellRepoInfo
import by.quty.launch.core.managers.ShellUpdateManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.interfaces.SettingsEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.ZipFile

class ShellSettingsFragment : Fragment() {

    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager
    private lateinit var shellsAdapter: ShellsAdapter
    private var settingsEventListener: SettingsEventListener? = null

    // Флаг для предотвращения множественных применений оболочки
    private var isApplyingShell = false

    // Флаг, что требуется перезагрузка интерфейса
    private var needsRestart = false

    // JSON парсер
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val EXTRA_SHELL_NAME = "shell_name"
        private const val DELAY_BEFORE_UI_UPDATE = 100L
    }

    // Регистрируем ActivityResult для выбора файла оболочки
    private val selectShellLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                installShellFromUri(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_shell, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем SettingsActivity как listener
        settingsEventListener = activity as? SettingsEventListener

        // Инициализируем менеджеры через активность
        (activity as? SettingsActivity)?.let { settingsActivity ->
            shellManager = settingsActivity.shellManager
            storageManager = StorageManager(requireContext())
        }

        setupShellSelector(view)
        setupInstallButton(view)
    }

    override fun onResume() {
        super.onResume()
        isApplyingShell = false
        shellsAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        isApplyingShell = false
    }

    /**
     * Устанавливает флаг необходимости перезагрузки
     */
    fun setNeedsRestart(value: Boolean) {
        this.needsRestart = value
    }

    /**
     * Возвращает флаг необходимости перезагрузки
     */
    fun getNeedsRestart(): Boolean = needsRestart

    /**
     * Настройка выбора оболочки оформления с превью и информацией
     */
    private fun setupShellSelector(view: View) {
        val shellsList = view.findViewById<ListView>(R.id.shells_list)
        val shells = shellManager.getAvailableShells()

        shellsAdapter = ShellsAdapter(shells)
        shellsList.adapter = shellsAdapter
    }

    /**
     * Внутренний метод применения оболочки
     */
    private fun applyShellInternal(shell: Shell) {
        if (isApplyingShell) return

        isApplyingShell = true

        val currentActive = shellManager.getActiveShell()
        val isDifferent = currentActive?.name != shell.name

        lifecycleScope.launch {
            shellManager.setActiveShell(shell)

            withContext(Dispatchers.Main) {
                Handler(Looper.getMainLooper()).postDelayed({
                    shellsAdapter.notifyDataSetChanged()
                    isApplyingShell = false
                }, DELAY_BEFORE_UI_UPDATE)

                settingsEventListener?.onShellChanged(shell.name)
                settingsEventListener?.onSettingChanged()

                Handler(Looper.getMainLooper()).postDelayed({
                    (activity as? SettingsActivity)?.let { settingsActivity ->
                        settingsActivity.displayFragment?.updateOrientationLockState()
                    }
                }, DELAY_BEFORE_UI_UPDATE)

                if (isDifferent) {
                    needsRestart = true
                }

                val message = getString(R.string.shell_applied, shell.displayName ?: shell.name)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

                val resultIntent = Intent()
                resultIntent.putExtra(EXTRA_SHELL_NAME, shell.name)
                requireActivity().setResult(SettingsActivity.RESULT_SHELL_CHANGED, resultIntent)
            }
        }
    }

    private fun setupInstallButton(view: View) {
        val btnInstall = view.findViewById<Button>(R.id.btn_install_shell)
        btnInstall.setOnClickListener {
            selectShellFile()
        }
    }

    /**
     * Открывает файловый менеджер для выбора оболочки
     */
    private fun selectShellFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/octet-stream"
                ))
                putExtra(Intent.EXTRA_TITLE, getString(R.string.select_shell_title))
            }
            selectShellLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            selectShellAlternative()
        }
    }

    /**
     * Альтернативный способ выбора файла
     */
    private fun selectShellAlternative() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/octet-stream"
                ))
            }
            selectShellLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showFileManagerSuggestion()
        }
    }

    /**
     * Показывает диалог с предложением установить файловый менеджер
     */
    private fun showFileManagerSuggestion() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.file_manager_not_found)
            .setMessage(R.string.file_manager_suggestion)
            .setPositiveButton(R.string.install_material_files) { _, _ ->
                openPlayStore("me.zhanghai.android.files")
            }
            .setNeutralButton(R.string.other_file_manager) { _, _ ->
                openPlayStore("com.ghisler.android.TotalCommander")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Открывает Google Play для установки приложения
     */
    private fun openPlayStore(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    R.string.cannot_open_play_store,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Устанавливает оболочку из выбранного URI
     */
    private fun installShellFromUri(uri: Uri) {
        try {
            val fileName = getFileNameFromUri(uri)
            if (fileName == null || !ShellManager.SHELL_EXTENSIONS_WITH_DOT.any { fileName.endsWith(it, ignoreCase = true) }) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.invalid_shell_extensions),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val shellInfo = validateShell(uri)
            if (shellInfo == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.shell_incompatible),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            showConfirmInstallDialog(uri, shellInfo.first, shellInfo.second, shellInfo.third)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.shell_install_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Получает имя файла из URI
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        return it.getString(displayNameIndex)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет валидность оболочки и возвращает название, версию и минимальную версию Quty.Launch
     */
    private fun validateShell(uri: Uri): Triple<String, String, String?>? {
        return try {
            // Создаём временный файл через StorageManager
            val tempFile = storageManager.createTempFile(
                prefix = "temp_shell_validation",
                extension = "zip"
            )

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            ZipFile(tempFile).use { zip ->
                val entry = zip.getEntry("manifest.json")
                if (entry == null) {
                    tempFile.delete()
                    return null
                }

                val manifestContent = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val manifest = json.decodeFromString<ShellManifest>(manifestContent)

                tempFile.delete()

                // Проверяем совместимость
                if (!isLauncherCompatible(manifest.minQutyLaunchVersion)) {
                    return null
                }

                return Triple(manifest.name, manifest.version, manifest.minQutyLaunchVersion)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет, совместима ли оболочка с текущей версией Quty.Launch
     */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true

        val currentVersion = getCurrentLauncherVersion()
        if (currentVersion.isEmpty()) return true

        return compareVersions(currentVersion, minVersion) >= 0
    }

    /**
     * Получает текущую версию Quty.Launch
     */
    private fun getCurrentLauncherVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            }
            packageInfo.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Сравнивает две версии (формат x.y.z)
     */
    private fun compareVersions(v1: String, v2: String): Int {
        return try {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }

                when {
                    p1 > p2 -> return 1
                    p1 < p2 -> return -1
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Показывает диалог подтверждения установки
     */
    private fun showConfirmInstallDialog(uri: Uri, shellName: String, shellVersion: String, minQutyLaunchVersion: String?) {
        val existingShell = shellManager.getAvailableShells().find {
            it.displayName == shellName || it.name == shellName
        }

        val compatibilityInfo = if (!minQutyLaunchVersion.isNullOrEmpty()) {
            "\n\n${getString(R.string.shell_min_launcher_version, minQutyLaunchVersion)}"
        } else {
            ""
        }

        val message = if (existingShell != null) {
            getString(R.string.shell_already_exists, shellName) + compatibilityInfo
        } else {
            getString(R.string.shell_install_confirm_message, shellName, shellVersion) + compatibilityInfo
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.shell_install_confirm))
            .setMessage(message)
            .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                performShellInstall(uri, shellName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Выполняет установку оболочки через StorageManager
     */
    private fun performShellInstall(uri: Uri, shellName: String) {
        try {
            val fileName = "$shellName${ShellManager.SHELL_EXTENSION_WITH_DOT}"

            // Сохраняем оболочку через StorageManager
            lifecycleScope.launch {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val success = storageManager.set(
                        directory = StorageDirectory.SHELLS,
                        name = fileName,
                        inputStream = inputStream,
                        overwrite = true
                    )

                    if (success) {
                        refreshShells()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.shell_install_success, shellName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.shell_install_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.shell_install_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // ВНУТРЕННИЙ АДАПТЕР
    // ============================================================

    inner class ShellsAdapter(private val shells: List<Shell>) : BaseAdapter() {

        override fun getCount(): Int = shells.size

        override fun getItem(position: Int): Shell = shells[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_shell, parent, false)
            val shell = getItem(position)

            val previewView = view.findViewById<ImageView>(R.id.shell_preview)
            val nameView = view.findViewById<TextView>(R.id.shell_name)
            val versionView = view.findViewById<TextView>(R.id.shell_version)
            val authorView = view.findViewById<TextView>(R.id.shell_author)
            val menuButton = view.findViewById<ImageButton>(R.id.shell_menu_button)

            // Устанавливаем название
            nameView.text = shell.displayName ?: shell.name

            // Устанавливаем версию
            if (!shell.version.isNullOrEmpty()) {
                versionView.text = getString(R.string.shell_version_with_label, shell.version)
                versionView.visibility = View.VISIBLE
            } else {
                versionView.visibility = View.GONE
            }

            // Устанавливаем автора
            authorView.text = shell.author ?: if (shell.isCustom) {
                getString(R.string.author_custom)
            } else {
                getString(R.string.author_default)
            }

            // Устанавливаем превью
            if (!shell.previewBase64.isNullOrEmpty()) {
                try {
                    val imageBytes = Base64.decode(shell.previewBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    previewView.setImageBitmap(bitmap)
                    previewView.visibility = View.VISIBLE
                } catch (_: Exception) {
                    previewView.setImageResource(R.drawable.ic_settings)
                }
            } else {
                previewView.setImageResource(R.drawable.ic_settings)
            }

            // Получаем актуальную активную оболочку
            val activeShell = shellManager.getActiveShell()

            // Подсвечиваем активную оболочку
            val isActive = shell.name == activeShell?.name
            if (isActive) {
                view.setBackgroundColor(resources.getColor(R.color.shell_active_background, null))
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Устанавливаем клик на всю строку
            view.setOnClickListener {
                if (isApplyingShell) return@setOnClickListener

                if (shell.name == activeShell?.name) {
                    Toast.makeText(requireContext(), R.string.shell_already_active, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                applyShellInternal(shell)
            }

            // Настройка кнопки меню
            setupShellMenuButton(menuButton, shell, isActive)

            return view
        }

        /**
         * Настройка кнопки меню для оболочки
         */
        private fun setupShellMenuButton(menuButton: ImageButton, shell: Shell, isActive: Boolean) {
            menuButton.setOnClickListener { view ->
                view.parent.requestDisallowInterceptTouchEvent(true)
                showShellMenu(view, shell, isActive)
            }
        }

        /**
         * Показывает выпадающее меню для управления оболочкой
         */
        private fun showShellMenu(anchor: View, shell: Shell, isActive: Boolean) {
            val popupMenu = PopupMenu(requireContext(), anchor)

            val menu = popupMenu.menu

            // Пункт "Применить" - только если не активна
            if (!isActive) {
                menu.add(0, 1, 0, getString(R.string.shell_menu_apply))
            }

            // Пункт "Информация" - ВСЕГДА
            menu.add(0, 2, 0, getString(R.string.shell_menu_info))

            // Пункт "Удалить" - ТОЛЬКО для кастомных оболочек
            if (shell.isCustom) {
                val isBuiltInUpdate = shellManager.isBuiltInShellUpdate(shell)
                val menuText = if (isBuiltInUpdate) {
                    getString(R.string.shell_menu_delete_update)
                } else {
                    getString(R.string.shell_menu_delete)
                }
                menu.add(0, 3, 0, menuText)
            }

            // Пункт "Поделиться" - только для кастомных оболочек
            if (shell.isCustom) {
                menu.add(0, 4, 0, getString(R.string.shell_menu_share))
            }

            // "Проверить обновления" — если есть repoUrl
            if (!shell.repoUrl.isNullOrEmpty()) {
                menu.add(0, 5, 0, getString(R.string.shell_menu_check_updates))
            }

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> applyShellInternal(shell)
                    2 -> showShellInfo(shell)
                    3 -> deleteShell(shell)
                    4 -> shareShell(shell)
                    5 -> checkShellUpdates(shell)
                }
                true
            }

            popupMenu.show()
        }

        /**
         * Проверка обновлений для оболочки
         */
        private fun checkShellUpdates(shell: Shell) {
            val updateManager = ShellUpdateManager(requireContext())

            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shell_update_checking))
                .setMessage(getString(R.string.shell_update_checking_message))
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                val updateInfo = updateManager.checkForUpdate(shell)
                progressDialog.dismiss()

                if (updateInfo == null) {
                    Toast.makeText(requireContext(), getString(R.string.shell_update_not_found), Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateConfirmDialog(shell, updateInfo)
                }
            }
        }

        /**
         * Диалог подтверждения обновления
         */
        private fun showUpdateConfirmDialog(shell: Shell, updateInfo: ShellRepoInfo) {
            if (!isLauncherCompatible(updateInfo.minQutyLaunchVersion)) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.shell_update_incompatible_title))
                    .setMessage(getString(R.string.shell_update_incompatible_message, updateInfo.version))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }

            val message = buildString {
                append(getString(R.string.shell_update_available_message))
                append("\n\n")
                append(getString(R.string.shell_update_current_version, shell.version ?: "—"))
                append("\n")
                append(getString(R.string.shell_update_new_version, updateInfo.version))

                if (!updateInfo.minQutyLaunchVersion.isNullOrEmpty()) {
                    append("\n")
                    append(getString(R.string.shell_update_min_launcher, updateInfo.minQutyLaunchVersion))
                }

                if (updateInfo.changelog.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.shell_update_changelog, updateInfo.changelog))
                }
                if (updateInfo.fileSize.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.shell_update_size, updateInfo.fileSize))
                }
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shell_update_confirm_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.shell_update_install)) { _, _ ->
                    downloadShellUpdate(shell, updateInfo)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        /**
         * Скачивание и установка обновления оболочки
         */
        private fun downloadShellUpdate(shell: Shell, updateInfo: ShellRepoInfo) {
            val updateManager = ShellUpdateManager(requireContext())

            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shell_update_downloading))
                .setMessage(getString(R.string.shell_update_preparing))
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                updateManager.downloadShellUpdate(updateInfo, object : ShellUpdateManager.DownloadListener {
                    override fun onProgress(percent: Int) {
                        progressDialog.setMessage(getString(R.string.shell_update_progress, percent))
                    }

                    override fun onSuccess() {
                        progressDialog.dismiss()

                        shellManager.reloadActiveShell()
                        refreshShells()

                        // Проверяем, была ли обновлена активная оболочка
                        val activeShell = shellManager.getActiveShell()
                        if (activeShell?.name == shell.name) {
                            needsRestart = true
                        }

                        Toast.makeText(
                            requireContext(),
                            getString(R.string.shell_update_success, updateInfo.version),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onError(message: String) {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.shell_update_error, message), Toast.LENGTH_LONG).show()
                    }
                })
            }
        }

        /**
         * Показать информацию об оболочке
         */
        private fun showShellInfo(shell: Shell): Boolean {
            val type = if (shell.isCustom) {
                getString(R.string.shell_type_custom)
            } else {
                getString(R.string.shell_type_builtin)
            }

            val version = shell.version ?: "—"
            val author = shell.author ?: if (shell.isCustom) {
                getString(R.string.author_custom)
            } else {
                getString(R.string.author_default)
            }

            val minVersion = shell.minQutyLaunchVersion?.let {
                getString(R.string.shell_info_min_version, it)
            } ?: getString(R.string.shell_info_min_version_not_specified)

            val message = getString(
                R.string.shell_info_message,
                shell.displayName ?: shell.name,
                version,
                author,
                type,
                minVersion
            )

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shell_info_title, shell.displayName ?: shell.name))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()

            return true
        }

        /**
         * Удалить кастомную оболочку
         */
        private fun deleteShell(shell: Shell): Boolean {
            if (!shell.isCustom) {
                Toast.makeText(requireContext(), getString(R.string.shell_cant_delete_default), Toast.LENGTH_SHORT).show()
                return false
            }

            val isBuiltInUpdate = shellManager.isBuiltInShellUpdate(shell)

            if (isBuiltInUpdate) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.shell_delete_active_update_title))
                    .setMessage(getString(R.string.shell_delete_active_update_confirm))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        performDeleteShell(shell)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
                return true
            }

            val activeShell = shellManager.getActiveShell()
            if (shell.name == activeShell?.name) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.shell_cant_delete_active),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shell_delete_confirm))
                .setMessage(getString(R.string.shell_delete_message, shell.displayName ?: shell.name))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    performDeleteShell(shell)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()

            return true
        }

        /**
         * Выполнить удаление оболочки
         */
        private fun performDeleteShell(shell: Shell) {
            try {
                val shellName = shell.name
                val wasActive = shellManager.getActiveShell()?.name == shellName
                val isBuiltInUpdate = shellManager.isBuiltInShellUpdate(shell)

                lifecycleScope.launch {
                    val deleted = shellManager.deleteShellByName(shell.name)

                    if (deleted) {
                        shellManager.reloadActiveShell()
                        refreshShells()

                        var needRestart = false

                        if (wasActive || isBuiltInUpdate) {
                            needRestart = true
                        }

                        if (needRestart) {
                            this@ShellSettingsFragment.needsRestart = true
                        }

                        val message = if (isBuiltInUpdate) {
                            getString(R.string.shell_delete_update_success, shell.displayName ?: shell.name)
                        } else {
                            getString(R.string.shell_delete_success, shell.displayName ?: shell.name)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.shell_delete_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.shell_delete_error), Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Поделиться оболочкой (отправить файл)
         */
        private fun shareShell(shell: Shell): Boolean {
            try {
                val uri = shellManager.getShellUri(shell)
                if (uri == null) {
                    Toast.makeText(requireContext(), getString(R.string.shell_file_not_found), Toast.LENGTH_SHORT).show()
                    return false
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.shell_share_title)))
                return true

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.shell_share_error), Toast.LENGTH_SHORT).show()
                return false
            }
        }
    }

    /**
     * Обновление списка оболочек
     */
    fun refreshShells() {
        isApplyingShell = false
        shellsAdapter = ShellsAdapter(shellManager.getAvailableShells())
        val shellsList = view?.findViewById<ListView>(R.id.shells_list)
        shellsList?.adapter = shellsAdapter
        shellsAdapter.notifyDataSetChanged()
    }

    // Внутренний класс для парсинга manifest.json
    @Serializable
    data class ShellManifest(
        val name: String,
        val author: String = "",
        val version: String = "0.0.1",
        val preview: String? = null,
        val orientation: String? = null,
        val repoUrl: String? = null,
        val minQutyLaunchVersion: String? = null
    )
}