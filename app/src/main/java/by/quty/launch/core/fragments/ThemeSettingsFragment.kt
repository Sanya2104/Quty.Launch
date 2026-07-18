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
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.interfaces.SettingsEventListener
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
            Toast.makeText(requireContext(),
                getString(R.string.error_open_file_manager),
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Устанавливает тему из выбранного URI.
     */
    private fun installThemeFromUri(uri: Uri) {
        try {
            val fileName = getFileNameFromUri(uri)
            if (fileName == null ||
                !(fileName.endsWith(".qutytheme") || fileName.endsWith(".qt"))) {
                Toast.makeText(requireContext(),
                    getString(R.string.invalid_theme),
                    Toast.LENGTH_LONG).show()
                return
            }

            val themeInfo = validateTheme(uri)
            if (themeInfo == null) {
                Toast.makeText(requireContext(),
                    getString(R.string.invalid_theme),
                    Toast.LENGTH_LONG).show()
                return
            }

            showConfirmInstallDialog(uri, themeInfo.first, themeInfo.second)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(),
                getString(R.string.theme_install_error),
                Toast.LENGTH_LONG).show()
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

            val fileName = "$themeName.qt"
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
        val version: String = "1.0.0",
        val preview: String? = null,
        val orientation: String? = null
    )
}