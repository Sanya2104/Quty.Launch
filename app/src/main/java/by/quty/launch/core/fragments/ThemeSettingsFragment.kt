// *** core/fragments/ThemeSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.Manifest
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.ThemeRepoInfo
import by.quty.launch.core.ThemeUpdateManager
import by.quty.launch.core.interfaces.SettingsEventListener
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ThemeSettingsFragment : Fragment() {

    private lateinit var themeManager: ThemeManager
    private lateinit var themesAdapter: ThemesAdapter
    private var settingsEventListener: SettingsEventListener? = null

    // Флаг для предотвращения множественных применений темы
    private var isApplyingTheme = false

    // JSON парсер (один экземпляр для всего класса)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val EXTRA_THEME_NAME = "theme_name"
        private const val DELAY_BEFORE_UI_UPDATE = 100L
    }

    // Регистрируем ActivityResult для выбора файла темы
    private val selectThemeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                installThemeFromUri(uri)
            }
        }
    }

    // Регистрируем ActivityResult для запроса доступа к хранилищу (Android 11+)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // После возврата из настроек пытаемся установить тему снова
        pendingThemeUri?.let { uri ->
            pendingThemeName?.let { name ->
                performThemeInstall(uri, name)
            }
        }
        pendingThemeUri = null
        pendingThemeName = null
    }

    // Для Android 10 и ниже
    private val requestStoragePermissionLegacy = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingThemeUri?.let { uri ->
                pendingThemeName?.let { name ->
                    performThemeInstall(uri, name)
                }
            }
            pendingThemeUri = null
            pendingThemeName = null
        } else {
            Toast.makeText(
                requireContext(),
                "Для установки тем необходимо разрешить доступ к хранилищу",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Переменные для хранения данных о теме, которую пытаемся установить
    private var pendingThemeUri: Uri? = null
    private var pendingThemeName: String? = null

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
        setupInstallButton(view)
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
    }

    /**
     * Внутренний метод применения темы (используется и при клике, и из меню)
     */
    private fun applyThemeInternal(theme: Theme) {
        if (isApplyingTheme) return

        isApplyingTheme = true

        // Применяем тему
        themeManager.setActiveTheme(theme)

        // Обновляем адаптер с задержкой, чтобы избежать мерцания
        Handler(Looper.getMainLooper()).postDelayed({
            themesAdapter.notifyDataSetChanged()
            isApplyingTheme = false
        }, DELAY_BEFORE_UI_UPDATE)

        // Уведомляем Activity об изменении темы
        settingsEventListener?.onThemeChanged(theme.name)
        settingsEventListener?.onSettingChanged()

        // Обновляем состояние во вкладке "Экран" с задержкой
        Handler(Looper.getMainLooper()).postDelayed({
            (activity as? SettingsActivity)?.let { settingsActivity ->
                settingsActivity.displayFragment?.updateOrientationLockState()
            }
        }, DELAY_BEFORE_UI_UPDATE)

        val message = getString(R.string.theme_applied, theme.displayName ?: theme.name)
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

        // Возвращаем результат в MainActivity
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_THEME_NAME, theme.name)
        requireActivity().setResult(SettingsActivity.RESULT_THEME_CHANGED, resultIntent)
    }

    private fun setupInstallButton(view: View) {
        val btnInstall = view.findViewById<Button>(R.id.btn_install_theme)
        btnInstall.setOnClickListener {
            selectThemeFile()
        }
    }

    /**
     * Проверяет доступ к хранилищу.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
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
    private fun checkStoragePermissionAndInstall(uri: Uri, themeName: String) {
        if (!hasStoragePermission()) {
            pendingThemeUri = uri
            pendingThemeName = themeName

            AlertDialog.Builder(requireContext())
                .setTitle("Доступ к хранилищу")
                .setMessage("Для установки тем необходимо разрешить доступ к файлам")
                .setPositiveButton("Разрешить") { _, _ ->
                    requestStoragePermission()
                }
                .setNegativeButton("Отмена") { _, _ ->
                    pendingThemeUri = null
                    pendingThemeName = null
                }
                .show()
            return
        }

        performThemeInstall(uri, themeName)
    }

    /**
     * Открывает файловый менеджер для выбора темы.
     */
    private fun selectThemeFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/octet-stream"
                ))
                putExtra(Intent.EXTRA_TITLE, getString(R.string.select_theme_title))
            }
            selectThemeLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.error_open_file_manager),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Устанавливает тему из выбранного URI.
     */
    private fun installThemeFromUri(uri: Uri) {
        try {
            val fileName = getFileNameFromUri(uri)
            if (fileName == null || !fileName.endsWith(ThemeManager.THEME_EXTENSION_WITH_DOT)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.invalid_theme),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val themeInfo = validateTheme(uri)
            if (themeInfo == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.invalid_theme),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            showConfirmInstallDialog(uri, themeInfo.first, themeInfo.second)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.theme_install_error),
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
     * Проверяет валидность темы и возвращает название и версию.
     */
    private fun validateTheme(uri: Uri): Pair<String, String>? {
        return try {
            val tempFile = File(requireContext().cacheDir, "temp_theme.zip")
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
                val manifest = json.decodeFromString<ThemeManifest>(manifestContent)

                tempFile.delete()
                return Pair(manifest.name, manifest.version)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Показывает диалог подтверждения установки.
     */
    private fun showConfirmInstallDialog(uri: Uri, themeName: String, themeVersion: String) {
        val existingTheme = themeManager.getAvailableThemes().find {
            it.displayName == themeName || it.name == themeName
        }

        val message = if (existingTheme != null) {
            getString(R.string.theme_already_exists, themeName)
        } else {
            getString(R.string.theme_install_confirm_message, themeName, themeVersion)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.theme_install_confirm))
            .setMessage(message)
            .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                checkStoragePermissionAndInstall(uri, themeName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Выполняет установку темы.
     */
    private fun performThemeInstall(uri: Uri, themeName: String) {
        try {
            val themesDir = File(
                android.os.Environment.getExternalStorageDirectory(),
                "QutyThemes"
            )
            if (!themesDir.exists()) {
                themesDir.mkdirs()
            }

            val fileName = "$themeName${ThemeManager.THEME_EXTENSION_WITH_DOT}"
            val destFile = File(themesDir, fileName)

            if (destFile.exists()) {
                destFile.delete()
            }

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            refreshThemes()

            Toast.makeText(
                requireContext(),
                getString(R.string.theme_install_success, themeName),
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                getString(R.string.theme_install_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
            val menuButton = view.findViewById<ImageButton>(R.id.theme_menu_button)

            // Устанавливаем название
            nameView.text = theme.displayName ?: theme.name

            // Устанавливаем версию (показываем только если есть)
            if (!theme.version.isNullOrEmpty()) {
                versionView.text = getString(R.string.theme_version_with_label, theme.version)
                versionView.visibility = View.VISIBLE
            } else {
                versionView.visibility = View.GONE
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
            val isActive = theme.name == activeTheme?.name
            if (isActive) {
                view.setBackgroundColor(resources.getColor(R.color.theme_active_background, null))
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Устанавливаем клик на всю строку (кроме кнопки меню)
            view.setOnClickListener {
                // Предотвращаем множественные нажатия
                if (isApplyingTheme) return@setOnClickListener

                // Проверяем, не активна ли уже тема
                if (theme.name == activeTheme?.name) {
                    Toast.makeText(requireContext(), "Тема уже активна", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                applyThemeInternal(theme)
            }

            // Настройка кнопки меню (передаём флаг isActive)
            setupThemeMenuButton(menuButton, theme, isActive)

            return view
        }

        /**
         * Настройка кнопки меню для темы
         * @param isActive true если тема уже активна
         */
        private fun setupThemeMenuButton(menuButton: ImageButton, theme: Theme, isActive: Boolean) {
            menuButton.setOnClickListener { view ->
                // Останавливаем распространение события, чтобы не сработал клик по строке
                view.parent.requestDisallowInterceptTouchEvent(true)
                showThemeMenu(view, theme, isActive)
            }
        }

        /**
         * Показывает выпадающее меню для управления темой
         * @param isActive true если тема уже активна
         */
        private fun showThemeMenu(anchor: View, theme: Theme, isActive: Boolean) {
            val popupMenu = PopupMenu(requireContext(), anchor)

            val menu = popupMenu.menu

            // Пункт "Применить" - только если не активна
            if (!isActive) {
                menu.add(0, 1, 0, getString(R.string.theme_menu_apply))
            }

            // Пункт "Информация"
            menu.add(0, 2, 0, getString(R.string.theme_menu_info))

            // Для кастомных тем добавляем "Удалить" и "Поделиться"
            if (theme.isCustom) {
                menu.add(0, 3, 0, getString(R.string.theme_menu_delete))
                menu.add(0, 4, 0, getString(R.string.theme_menu_share))

                // Если есть repoUrl - добавляем "Проверить обновления"
                if (!theme.repoUrl.isNullOrEmpty()) {
                    menu.add(0, 5, 0, getString(R.string.theme_menu_check_updates))
                }
            }

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> applyThemeInternal(theme)
                    2 -> showThemeInfo(theme)
                    3 -> deleteTheme(theme)
                    4 -> shareTheme(theme)
                    5 -> checkThemeUpdates(theme)
                }
                true
            }

            popupMenu.show()
        }

        /**
         * Проверка обновлений для темы
         */
        private fun checkThemeUpdates(theme: Theme) {
            val updateManager = ThemeUpdateManager(requireContext())

            // Показываем диалог с прогрессом
            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.theme_update_checking))
                .setMessage(getString(R.string.theme_update_checking_message))
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                val updateInfo = updateManager.checkForUpdate(theme)
                progressDialog.dismiss()

                if (updateInfo == null) {
                    Toast.makeText(requireContext(), getString(R.string.theme_update_not_found), Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateConfirmDialog(theme, updateInfo)
                }
            }
        }

        /**
         * Диалог подтверждения обновления
         */
        private fun showUpdateConfirmDialog(theme: Theme, updateInfo: ThemeRepoInfo) {
            val message = buildString {
                append(getString(R.string.theme_update_available_message))
                append("\n\n")
                append(getString(R.string.theme_update_current_version, theme.version ?: "—"))
                append("\n")
                append(getString(R.string.theme_update_new_version, updateInfo.version))
                if (updateInfo.changelog.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.theme_update_changelog, updateInfo.changelog))
                }
                if (updateInfo.fileSize.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.theme_update_size, updateInfo.fileSize))
                }
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.theme_update_confirm_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.theme_update_install)) { _, _ ->
                    downloadThemeUpdate(updateInfo)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        /**
         * Скачивание и установка обновления темы
         */
        private fun downloadThemeUpdate(updateInfo: ThemeRepoInfo) {
            val updateManager = ThemeUpdateManager(requireContext())

            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.theme_update_downloading))
                .setMessage(getString(R.string.theme_update_preparing))
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                updateManager.downloadThemeUpdate(updateInfo, object : ThemeUpdateManager.DownloadListener {
                    override fun onProgress(percent: Int) {
                        progressDialog.setMessage(getString(R.string.theme_update_progress, percent))
                    }

                    override fun onSuccess() {
                        progressDialog.dismiss()

                        // Принудительно перезагружаем активную тему из файла
                        themeManager.reloadActiveTheme()

                        // Обновляем список тем
                        refreshThemes()

                        Toast.makeText(
                            requireContext(),
                            getString(R.string.theme_update_success, updateInfo.version),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onError(message: String) {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.theme_update_error, message), Toast.LENGTH_LONG).show()
                    }
                })
            }
        }

        /**
         * Показать информацию о теме
         */
        private fun showThemeInfo(theme: Theme): Boolean {
            val type = if (theme.isCustom) {
                getString(R.string.theme_type_custom)
            } else {
                getString(R.string.theme_type_builtin)
            }

            val version = theme.version ?: "—"
            val author = theme.author ?: if (theme.isCustom) {
                getString(R.string.author_custom)
            } else {
                getString(R.string.author_default)
            }

            val message = getString(
                R.string.theme_info_message,
                theme.displayName ?: theme.name,
                version,
                author,
                type
            )

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.theme_info_title, theme.displayName ?: theme.name))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()

            return true
        }

        /**
         * Удалить кастомную тему
         */
        private fun deleteTheme(theme: Theme): Boolean {
            // Проверяем, что тему можно удалить
            if (!theme.isCustom) {
                Toast.makeText(requireContext(), getString(R.string.theme_cant_delete_default), Toast.LENGTH_SHORT).show()
                return false
            }

            // Проверяем, не активна ли тема
            val activeTheme = themeManager.getActiveTheme()
            if (theme.name == activeTheme?.name) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.theme_cant_delete_active),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            // Диалог подтверждения
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.theme_delete_confirm))
                .setMessage(getString(R.string.theme_delete_message, theme.displayName ?: theme.name))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    performDeleteTheme(theme)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()

            return true
        }

        /**
         * Выполнить удаление темы
         */
        private fun performDeleteTheme(theme: Theme) {
            try {
                val themeFile = File(theme.sourcePath)
                if (themeFile.exists()) {
                    themeFile.delete()
                }

                // Обновляем список тем
                refreshThemes()

                Toast.makeText(
                    requireContext(),
                    getString(R.string.theme_delete_success, theme.displayName ?: theme.name),
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.theme_delete_error), Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Поделиться темой (отправить файл)
         */
        private fun shareTheme(theme: Theme): Boolean {
            try {
                val themeFile = File(theme.sourcePath)
                if (!themeFile.exists()) {
                    Toast.makeText(requireContext(), getString(R.string.theme_file_not_found), Toast.LENGTH_SHORT).show()
                    return false
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    themeFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.theme_share_title)))
                return true

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.theme_share_error), Toast.LENGTH_SHORT).show()
                return false
            }
        }
    }

    /**
     * Обновление списка тем (вызывается из Activity при необходимости).
     */
    fun refreshThemes() {
        isApplyingTheme = false
        themesAdapter = ThemesAdapter(themeManager.getAvailableThemes())
        val themesList = view?.findViewById<ListView>(R.id.themes_list)
        themesList?.adapter = themesAdapter
        themesAdapter.notifyDataSetChanged()
    }

    // Внутренний класс для парсинга manifest.json
    @Serializable
    data class ThemeManifest(
        val name: String,
        val author: String = "",
        val version: String = "0.0.1",
        val preview: String? = null,
        val orientation: String? = null,
        val repoUrl: String? = null
    )
}