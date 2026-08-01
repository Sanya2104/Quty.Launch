// *** core/fragments/ShellSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.Shell
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.ShellRepoInfo
import by.quty.launch.core.managers.ShellUpdateManager
import by.quty.launch.core.interfaces.SettingsEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ShellSettingsFragment : Fragment() {

    private lateinit var shellManager: ShellManager
    private lateinit var shellsAdapter: ShellsAdapter
    private var settingsEventListener: SettingsEventListener? = null

    // Флаг для предотвращения множественных применений оболочки
    private var isApplyingShell = false

    // Флаг, что требуется перезагрузка интерфейса
    private var needsRestart = false

    // JSON парсер (один экземпляр для всего класса)
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

    // Регистрируем ActivityResult для запроса доступа к хранилищу (Android 11+)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // После возврата из настроек пытаемся установить оболочку снова
        pendingShellUri?.let { uri ->
            pendingShellName?.let { name ->
                performShellInstall(uri, name)
            }
        }
        pendingShellUri = null
        pendingShellName = null
    }

    // Для Android 10 и ниже
    private val requestStoragePermissionLegacy = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingShellUri?.let { uri ->
                pendingShellName?.let { name ->
                    performShellInstall(uri, name)
                }
            }
            pendingShellUri = null
            pendingShellName = null
        } else {
            Toast.makeText(
                requireContext(),
                R.string.storage_permission_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Переменные для хранения данных об оболочке, которую пытаемся установить
    private var pendingShellUri: Uri? = null
    private var pendingShellName: String? = null

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

        // Инициализируем ShellManager через активность
        (activity as? SettingsActivity)?.let { settingsActivity ->
            shellManager = settingsActivity.shellManager
        }

        setupShellSelector(view)
        setupInstallButton(view)
    }

    override fun onResume() {
        super.onResume()
        // Сбрасываем флаг при возврате во вкладку
        isApplyingShell = false
        // Обновляем список
        shellsAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        // Сбрасываем флаг при уходе с вкладки
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
     * Внутренний метод применения оболочки (используется и при клике, и из меню)
     * - Применение оболочки теперь в корутине
     * - setActiveShell() - suspend функция, не блокирует UI
     */
    private fun applyShellInternal(shell: Shell) {
        if (isApplyingShell) return

        isApplyingShell = true

        // Запоминаем, была ли оболочка уже активна
        val currentActive = shellManager.getActiveShell()
        val isDifferent = currentActive?.name != shell.name

        // Применяем оболочку в фоновом потоке
        lifecycleScope.launch {
            shellManager.setActiveShell(shell)

            // Обновляем UI в главном потоке после завершения
            withContext(Dispatchers.Main) {
                // Обновляем адаптер с задержкой, чтобы избежать мерцания
                Handler(Looper.getMainLooper()).postDelayed({
                    shellsAdapter.notifyDataSetChanged()
                    isApplyingShell = false
                }, DELAY_BEFORE_UI_UPDATE)

                // Уведомляем Activity об изменении оболочки
                settingsEventListener?.onShellChanged(shell.name)
                settingsEventListener?.onSettingChanged()

                // Обновляем состояние во вкладке "Экран" с задержкой
                Handler(Looper.getMainLooper()).postDelayed({
                    (activity as? SettingsActivity)?.let { settingsActivity ->
                        settingsActivity.displayFragment?.updateOrientationLockState()
                    }
                }, DELAY_BEFORE_UI_UPDATE)

                // Если применили другую оболочку — устанавливаем флаг перезагрузки
                if (isDifferent) {
                    needsRestart = true
                }

                val message = getString(R.string.shell_applied, shell.displayName ?: shell.name)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

                // Возвращаем результат в MainActivity
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
     * Проверяет доступ к хранилищу.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Запрашивает доступ к хранилищу.
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${requireContext().packageName}".toUri()
                storagePermissionLauncher.launch(intent)
            } catch (_: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storagePermissionLauncher.launch(intent)
            }
        } else {
            requestStoragePermissionLegacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Проверяет доступ к хранилищу и запрашивает при необходимости.
     */
    private fun checkStoragePermissionAndInstall(uri: Uri, shellName: String) {
        if (!hasStoragePermission()) {
            pendingShellUri = uri
            pendingShellName = shellName

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_message)
                .setPositiveButton(R.string.storage_permission_allow) { _, _ ->
                    requestStoragePermission()
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    pendingShellUri = null
                    pendingShellName = null
                }
                .show()
            return
        }

        performShellInstall(uri, shellName)
    }

    /**
     * Открывает файловый менеджер для выбора оболочки.
     * Сначала пробует ACTION_OPEN_DOCUMENT, затем ACTION_GET_CONTENT,
     * при ошибке предлагает установить альтернативный файловый менеджер.
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
            // Пробуем альтернативный способ
            selectShellAlternative()
        }
    }

    /**
     * Альтернативный способ выбора файла через ACTION_GET_CONTENT
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
            // Если и это не работает — предлагаем установить файловый менеджер
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
     * Устанавливает оболочку из выбранного URI.
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
     * Получает имя файла из URI.
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
            val tempFile = File(requireContext().cacheDir, "temp_shell.zip")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
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

                // Проверяем совместимость с Quty.Launch
                if (!isLauncherCompatible(manifest.minQutyLaunchVersion)) {
                    return null  // Quty.Launch слишком старый для этой оболочки
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
     * Показывает диалог подтверждения установки.
     */
    private fun showConfirmInstallDialog(uri: Uri, shellName: String, shellVersion: String, minQutyLaunchVersion: String?) {
        val existingShell = shellManager.getAvailableShells().find {
            it.displayName == shellName || it.name == shellName
        }

        // Добавляем информацию о совместимости
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
                checkStoragePermissionAndInstall(uri, shellName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Выполняет установку оболочки.
     */
    private fun performShellInstall(uri: Uri, shellName: String) {
        try {
            // Новая структура: Quty.Launch/Shells/
            val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
            val shellsDir = File(appDir, "Shells")

            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            if (!shellsDir.exists()) {
                shellsDir.mkdirs()
            }

            val fileName = "$shellName${ShellManager.SHELL_EXTENSION_WITH_DOT}"
            val destFile = File(shellsDir, fileName)

            if (destFile.exists()) {
                destFile.delete()
            }

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            refreshShells()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_install_success, shellName),
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.shell_install_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

            // Устанавливаем версию (показываем только если есть)
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

            // Получаем актуальную активную оболочку из менеджера
            val activeShell = shellManager.getActiveShell()

            // Подсвечиваем активную оболочку
            val isActive = shell.name == activeShell?.name
            if (isActive) {
                view.setBackgroundColor(resources.getColor(R.color.shell_active_background, null))
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Устанавливаем клик на всю строку (кроме кнопки меню)
            view.setOnClickListener {
                // Предотвращаем множественные нажатия
                if (isApplyingShell) return@setOnClickListener

                // Проверяем, не активна ли уже оболочка
                if (shell.name == activeShell?.name) {
                    Toast.makeText(requireContext(), R.string.shell_already_active, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                applyShellInternal(shell)
            }

            // Настройка кнопки меню (передаём флаг isActive)
            setupShellMenuButton(menuButton, shell, isActive)

            return view
        }

        /**
         * Настройка кнопки меню для оболочки
         * @param isActive true если оболочка уже активна
         */
        private fun setupShellMenuButton(menuButton: ImageButton, shell: Shell, isActive: Boolean) {
            menuButton.setOnClickListener { view ->
                // Останавливаем распространение события, чтобы не сработал клик по строке
                view.parent.requestDisallowInterceptTouchEvent(true)
                showShellMenu(view, shell, isActive)
            }
        }

        /**
         * Показывает выпадающее меню для управления оболочкой
         * @param isActive true если оболочка уже активна
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
                // Проверяем, является ли это обновлением встроенной оболочки
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

            // "Проверить обновления" — если есть repoUrl (для ЛЮБЫХ оболочек)
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

            // Показываем диалог с прогрессом
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
            // Проверяем совместимость при обновлении
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

                // Добавляем информацию о минимальной версии Quty.Launch
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
                    downloadShellUpdate(updateInfo)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        /**
         * Скачивание и установка обновления оболочки
         */
        private fun downloadShellUpdate(updateInfo: ShellRepoInfo) {
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

                        // Принудительно перезагружаем активную оболочку из файла
                        shellManager.reloadActiveShell()

                        // Обновляем список оболочек
                        refreshShells()

                        // Если обновлялась активная оболочка — устанавливаем флаг перезагрузки
                        val activeShell = shellManager.getActiveShell()
                        if (activeShell?.name == shellManager.getActiveShell()?.name) {
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

            // Добавляем информацию о минимальной версии Quty.Launch
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
         * При удалении обновлённой встроенной оболочки — возвращается встроенная версия
         */
        private fun deleteShell(shell: Shell): Boolean {
            // Проверяем, что оболочку можно удалить (только кастомные)
            if (!shell.isCustom) {
                Toast.makeText(requireContext(), getString(R.string.shell_cant_delete_default), Toast.LENGTH_SHORT).show()
                return false
            }

            // Проверяем, не активна ли оболочка
            val activeShell = shellManager.getActiveShell()
            if (shell.name == activeShell?.name) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.shell_cant_delete_active),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            // Диалог подтверждения
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
                // Сохраняем имя оболочки до удаления
                val shellName = shell.name
                val wasActive = shellManager.getActiveShell()?.name == shellName

                // Проверяем, является ли это обновлением встроенной оболочки
                val isBuiltInUpdate = shellManager.isBuiltInShellUpdate(shell)

                // Удаляем файл через ShellManager
                val deleted = shellManager.deleteShellByName(shell.name)

                if (deleted) {
                    // Принудительно перезагружаем активную оболочку из конфига
                    shellManager.reloadActiveShell()

                    // Обновляем список оболочек
                    refreshShells()

                    // Определяем, нужна ли перезагрузка
                    var needRestart = false

                    if (wasActive) {
                        // Если удалялась активная оболочка — точно нужна перезагрузка
                        needRestart = true
                    } else if (isBuiltInUpdate) {
                        // Если удаляется обновление встроенной оболочки — после удаления
                        // активная оболочка может смениться на встроенную
                        val newActiveShell = shellManager.getActiveShell()
                        // Если активная оболочка стала встроенной (isAsset = true) с тем же именем
                        if (newActiveShell?.isAsset == true && newActiveShell.name == shellName) {
                            needRestart = true
                        }
                    }

                    if (needRestart) {
                        needsRestart = true
                    }

                    val message = if (isBuiltInUpdate) {
                        getString(R.string.shell_delete_update_success, shell.displayName ?: shell.name)
                    } else {
                        getString(R.string.shell_delete_success, shell.displayName ?: shell.name)
                    }

                    Toast.makeText(
                        requireContext(),
                        message,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.shell_delete_error),
                        Toast.LENGTH_SHORT
                    ).show()
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
                val shellFile = File(shell.sourcePath)
                if (!shellFile.exists()) {
                    Toast.makeText(requireContext(), getString(R.string.shell_file_not_found), Toast.LENGTH_SHORT).show()
                    return false
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    shellFile
                )

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
     * Обновление списка оболочек (вызывается из Activity при необходимости).
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