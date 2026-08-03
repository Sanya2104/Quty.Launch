// WelcomeActivity.kt

package by.quty.launch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import by.quty.launch.core.managers.PermissionManager

class WelcomeActivity : BaseActivity() {

    private val permissionRequestCode = 100
    private val storageRequestCode = 101

    // UI элементы
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnGrant: Button
    private lateinit var errorContainer: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var btnSettings: Button
    private lateinit var permissionsContainer: LinearLayout

    // Карта статусов разрешений
    private val permissionStatusViews = mutableMapOf<String, TextView>()

    // Флаг, что мы уже запрашивали разрешения
    private var isRequestingPermissions = false

    // Флаг, что мы уже запрашивали MANAGE_EXTERNAL_STORAGE
    private var isRequestingManageStorage = false

    // Флаг, что активность уже завершается (предотвращает повторные вызовы)
    private var isFinishingActivity = false

    // Регистрируем ActivityResult для запроса доступа к хранилищу (замена startActivityForResult)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Обновляем UI после возврата из настроек
        updatePermissionsUI()
        // Проверяем, дал ли пользователь разрешение
        if (PermissionManager.hasManageStoragePermission(this)) {
            Toast.makeText(this, R.string.storage_permission_granted, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        initViews()
        setupVersionInfo()
        setupPermissionsUI()
        updatePermissionsUI()
        setupListeners()

        if (PermissionManager.hasAllRequiredPermissions(this) && PermissionManager.hasManageStoragePermission(this)) {
            finishAndGoToMain()
        }
    }

    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        btnGrant = findViewById(R.id.btn_grant)
        errorContainer = findViewById(R.id.error_container)
        btnRetry = findViewById(R.id.btn_retry)
        btnSettings = findViewById(R.id.btn_settings)
        permissionsContainer = findViewById(R.id.permissions_container)
    }

    private fun setupVersionInfo() {
        val versionTextView = findViewById<TextView>(R.id.tv_version)
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName ?: getString(R.string.version_unknown)
            versionTextView.text = getString(R.string.version_format, versionName)
        } catch (_: Exception) {
            versionTextView.text = getString(R.string.version_format, getString(R.string.version_unknown))
        }
    }

    private fun setupPermissionsUI() {
        permissionsContainer.removeAllViews()
        permissionStatusViews.clear()

        // Список разрешений с иконками, названиями, описаниями
        val permissionsData = mutableListOf(
            PermissionItem(
                permission = Manifest.permission.READ_PHONE_STATE,
                iconRes = R.drawable.ic_phone,
                titleRes = R.string.welcome_permission_phone,
                descRes = R.string.welcome_permission_phone_desc,
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_NETWORK_STATE,
                iconRes = R.drawable.ic_network,
                titleRes = R.string.welcome_permission_network,
                descRes = R.string.welcome_permission_network_desc,
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_WIFI_STATE,
                iconRes = R.drawable.ic_wifi,
                titleRes = R.string.welcome_permission_wifi,
                descRes = R.string.welcome_permission_wifi_desc,
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                iconRes = R.drawable.ic_location,
                titleRes = R.string.welcome_permission_location,
                descRes = R.string.welcome_permission_location_desc,
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.READ_EXTERNAL_STORAGE,
                iconRes = R.drawable.ic_storage,
                titleRes = R.string.welcome_permission_storage,
                descRes = R.string.welcome_permission_storage_desc,
                isRequired = false
            )
        )

        // Добавляем MANAGE_EXTERNAL_STORAGE для Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissionsData.add(
                PermissionItem(
                    permission = "MANAGE_EXTERNAL_STORAGE",
                    iconRes = R.drawable.ic_storage,
                    titleRes = R.string.welcome_permission_manage_storage,
                    descRes = R.string.welcome_permission_manage_storage_desc,
                    isRequired = true
                )
            )
        }

        for (item in permissionsData) {
            val itemView = createPermissionItem(
                permission = item.permission,
                iconRes = item.iconRes,
                title = getString(item.titleRes),
                description = getString(item.descRes)
            )
            permissionsContainer.addView(itemView)
        }
    }

    /**
     * Создание элемента разрешения
     */
    @SuppressLint("SetTextI18n")
    private fun createPermissionItem(
        permission: String,
        iconRes: Int,
        title: String,
        description: String
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimension(R.dimen.permission_item_margin_bottom).toInt()
            }
        }

        // Основная строка: иконка + название + статус + стрелка
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = resources.getDimension(R.dimen.permission_item_padding).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.bg_permission_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openAppSettings()
            }
        }

        // Иконка
        val iconView = androidx.appcompat.widget.AppCompatImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                36.dpToPx(),
                36.dpToPx()
            ).apply {
                marginEnd = 16.dpToPx()
            }
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(this@WelcomeActivity, android.R.color.white))
        }

        // Блок с названием и описанием
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = title
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.white))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val descView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = description
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.darker_gray))
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        textContainer.addView(titleView)
        textContainer.addView(descView)

        // Статус + стрелка
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
            text = getString(R.string.permission_denied)
            textSize = 14f
            id = View.generateViewId()
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.holo_red_dark))
        }

        // Сохраняем для обновления
        permissionStatusViews[permission] = statusView

        // Стрелка
        val arrowView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "›"
            textSize = 24f
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.darker_gray))
        }

        statusContainer.addView(statusView)
        statusContainer.addView(arrowView)

        // Собираем строку
        rowLayout.addView(iconView)
        rowLayout.addView(textContainer)
        rowLayout.addView(statusContainer)

        container.addView(rowLayout)

        return container
    }

    /**
     * Конвертация dp в px
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun setupListeners() {
        btnGrant.setOnClickListener {
            requestAllPermissions()
        }

        btnRetry.setOnClickListener {
            errorContainer.visibility = LinearLayout.GONE
            requestAllPermissions()
        }

        btnSettings.setOnClickListener {
            openAppSettings()
        }
    }

    /**
     * Запрашивает MANAGE_EXTERNAL_STORAGE для Android 11+
     */
    private fun requestManageStoragePermission() {
        if (isRequestingManageStorage) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                isRequestingManageStorage = true
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    storagePermissionLauncher.launch(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        storagePermissionLauncher.launch(intent)
                    } catch (_: Exception) {
                        isRequestingManageStorage = false
                        Toast.makeText(
                            this,
                            R.string.cannot_open_manage_storage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun requestAllPermissions() {
        if (isRequestingPermissions) return
        isRequestingPermissions = true

        // Сначала запрашиваем MANAGE_EXTERNAL_STORAGE для Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageStoragePermission()
            isRequestingPermissions = false
            return
        }

        val missingPermissions = PermissionManager.getMissingRequiredPermissions(this)

        if (missingPermissions.isEmpty()) {
            if (PermissionManager.hasManageStoragePermission(this)) {
                finishAndGoToMain()
            } else {
                requestManageStoragePermission()
            }
            isRequestingPermissions = false
            return
        }

        ActivityCompat.requestPermissions(
            this,
            missingPermissions.toTypedArray(),
            permissionRequestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isRequestingPermissions = false

        if (requestCode == permissionRequestCode) {
            val allRequiredGranted = PermissionManager.hasAllRequiredPermissions(this)

            if (allRequiredGranted && PermissionManager.hasManageStoragePermission(this)) {
                finishAndGoToMain()
            } else {
                if (allRequiredGranted && !PermissionManager.hasManageStoragePermission(this)) {
                    requestManageStoragePermission()
                } else {
                    showPermissionDeniedError()
                }
            }

            updatePermissionsUI()
        } else if (requestCode == storageRequestCode) {
            // Обработка READ_EXTERNAL_STORAGE для Android 10 и ниже
            updatePermissionsUI()
            if (PermissionManager.hasAllRequiredPermissions(this) && PermissionManager.hasManageStoragePermission(this)) {
                finishAndGoToMain()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePermissionsUI() {
        // Обновляем статусы
        for ((permission, statusView) in permissionStatusViews) {
            val isGranted = when (permission) {
                "MANAGE_EXTERNAL_STORAGE" -> PermissionManager.hasManageStoragePermission(this)
                else -> PermissionManager.hasPermission(this, permission)
            }
            statusView.text = if (isGranted) {
                getString(R.string.permission_granted)
            } else {
                getString(R.string.permission_denied)
            }
            statusView.setTextColor(
                if (isGranted) {
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                } else {
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                }
            )
        }

        // Подсчитываем прогресс
        val requiredPermissions = PermissionManager.getRequiredPermissions()
        val totalCount = requiredPermissions.size + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 1 else 0

        val grantedCount = requiredPermissions.count { permission ->
            PermissionManager.hasPermission(this, permission)
        } + if (PermissionManager.hasManageStoragePermission(this)) 1 else 0

        val finalProgress = if (totalCount > 0) {
            (grantedCount * 100 / totalCount)
        } else {
            100
        }

        progressBar.progress = finalProgress
        progressText.text = "$finalProgress%"

        val allRequiredGranted = PermissionManager.hasAllRequiredPermissions(this) &&
                PermissionManager.hasManageStoragePermission(this)

        if (allRequiredGranted) {
            btnGrant.text = getString(R.string.welcome_button_start)
            btnGrant.isEnabled = true
            btnGrant.alpha = 1.0f
            btnGrant.setOnClickListener {
                finishAndGoToMain()
            }
        } else {
            btnGrant.text = getString(R.string.welcome_button_grant)
            btnGrant.isEnabled = true
            btnGrant.alpha = 1.0f
            btnGrant.setOnClickListener {
                requestAllPermissions()
            }
        }
    }

    private fun showPermissionDeniedError() {
        errorContainer.visibility = LinearLayout.VISIBLE
        errorContainer.post {
            errorContainer.requestFocus()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    /**
     * Проверяет, есть ли доступ к хранилищу
     * Вызывается из ShellSettingsFragment
     */
    @Suppress("unused")
    fun hasStoragePermission(): Boolean {
        return PermissionManager.hasManageStoragePermission(this)
    }

    /**
     * Запрашивает доступ к хранилищу
     * Вызывается из ShellSettingsFragment
     */
    @Suppress("unused")
    fun requestStoragePermission() {
        requestManageStoragePermission()
    }

    private fun finishAndGoToMain() {
        // Предотвращаем повторный вызов
        if (isFinishingActivity) return
        isFinishingActivity = true

        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        prefs.edit {
            putBoolean("onboarding_completed", true)
            putLong("onboarding_timestamp", System.currentTimeMillis())
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()

        // Если активность уже завершается — пропускаем
        if (isFinishingActivity) return

        // Сбрасываем флаг запроса, так как мы вернулись из настроек
        isRequestingManageStorage = false

        updatePermissionsUI()

        if (PermissionManager.hasAllRequiredPermissions(this) && PermissionManager.hasManageStoragePermission(this)) {
            finishAndGoToMain()
        }
    }

    // Внутренний класс для данных разрешения
    data class PermissionItem(
        val permission: String,
        val iconRes: Int,
        val titleRes: Int,
        val descRes: Int,
        val isRequired: Boolean
    )
}