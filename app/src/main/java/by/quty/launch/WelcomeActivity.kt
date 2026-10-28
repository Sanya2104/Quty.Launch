// *** WelcomeActivity.kt *** //
package by.quty.launch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.PermissionManager
import by.quty.launch.core.model.LoggerTagModel

/**
 * Онбординг: запрашивает обязательные разрешения перед стартом.
 */
class WelcomeActivity : BaseActivity() {

    private val permissionRequestCode = CoreConfig.PERMISSION_REQUEST_CODE

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var permissionsCounter: TextView
    private lateinit var btnGrant: Button
    private lateinit var errorContainer: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var btnSettings: Button
    private lateinit var permissionsContainer: LinearLayout

    /** permission > TextView статуса. */
    private val permissionStatusViews = mutableMapOf<String, TextView>()

    private var isRequestingPermissions = false
    private var isFinishingActivity = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        isRequestingPermissions = false
        handlePermissionResults(results)
    }

    /** Инициализирует UI, версию, список разрешений; при готовности — сразу на Main. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        LoggerManager.info(
            LoggerTagModel.CORE,
            "CORE.welcome  created"
        )

        initViews()
        setupVersionInfo()
        setupPermissionsUI()
        updatePermissionsUI()
        setupListeners()

        if (PermissionManager.hasAllRequiredPermissions(this)) {
            finishAndGoToMain()
        }
    }

    /** Находит все view по id. */
    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        permissionsCounter = findViewById(R.id.permissions_counter)
        btnGrant = findViewById(R.id.btn_grant)
        errorContainer = findViewById(R.id.error_container)
        btnRetry = findViewById(R.id.btn_retry)
        btnSettings = findViewById(R.id.btn_settings)
        permissionsContainer = findViewById(R.id.permissions_container)
    }

    /** Показывает текущую версию приложения. */
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

    // ============================================================
    // СПИСОК РАЗРЕШЕНИЙ
    // ============================================================

    /** Строит список строк разрешений с иконками и статусами. */
    private fun setupPermissionsUI() {
        permissionsContainer.removeAllViews()
        permissionStatusViews.clear()

        val inflater = layoutInflater

        val permissionsData = listOf(
            PermissionItem(
                permission = Manifest.permission.READ_PHONE_STATE,
                iconRes = R.drawable.ic_phone,
                titleRes = R.string.welcome_permission_phone,
                descRes = R.string.welcome_permission_phone_desc
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_NETWORK_STATE,
                iconRes = R.drawable.ic_network,
                titleRes = R.string.welcome_permission_network,
                descRes = R.string.welcome_permission_network_desc
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_WIFI_STATE,
                iconRes = R.drawable.ic_wifi,
                titleRes = R.string.welcome_permission_wifi,
                descRes = R.string.welcome_permission_wifi_desc
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                iconRes = R.drawable.ic_location,
                titleRes = R.string.welcome_permission_location,
                descRes = R.string.welcome_permission_location_desc
            )
        )

        permissionsData.forEachIndexed { index, item ->
            val row = inflater.inflate(
                R.layout.item_welcome_permission,
                permissionsContainer,
                false
            )

            val icon = row.findViewById<AppCompatImageView>(R.id.permission_icon)
            icon.setImageResource(item.iconRes)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColorFromAttribute(R.attr.textPrimaryColor)
            )

            row.findViewById<TextView>(R.id.permission_title).text = getString(item.titleRes)
            row.findViewById<TextView>(R.id.permission_description).text = getString(item.descRes)

            val statusView = row.findViewById<TextView>(R.id.permission_status)
            permissionStatusViews[item.permission] = statusView

            row.setOnClickListener { openAppSettings() }

            permissionsContainer.addView(row)

            if (index < permissionsData.lastIndex) {
                permissionsContainer.addView(createDivider())
            }
        }
    }

    /** Разделитель между строками разрешений. */
    private fun createDivider(): View {
        val height = (1 * resources.displayMetrics.density).toInt()
        val margin = resources.getDimensionPixelSize(R.dimen.spacing_l)

        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            ).apply {
                marginStart = margin
                marginEnd = margin
            }
            setBackgroundColor(getColorFromAttribute(R.attr.dividerColor))
        }
    }

    /** Вешает клики на кнопки запроса/повтора/настроек. */
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

    /** Запрашивает недостающие обязательные разрешения. */
    private fun requestAllPermissions() {
        if (isRequestingPermissions) return
        isRequestingPermissions = true

        val missingPermissions = PermissionManager.getMissingRequiredPermissions(this)

        if (missingPermissions.isEmpty()) {
            isRequestingPermissions = false
            finishAndGoToMain()
            return
        }

        permissionLauncher.launch(missingPermissions.toTypedArray())
    }

    /** Обрабатывает результат запроса разрешений из launcher-а. */
    private fun handlePermissionResults(results: Map<String, Boolean>) {
        isRequestingPermissions = false

        val allGranted = PermissionManager.hasAllRequiredPermissions(this)

        if (allGranted) {
            finishAndGoToMain()
        } else {
            val hasDeniedPermanently = results.entries.any { (permission, granted) ->
                !granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }

            if (hasDeniedPermanently) {
                showPermissionDeniedError()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.welcome_permission_denied_message),
                    Toast.LENGTH_LONG
                ).show()
                showPermissionDeniedError()
            }

            updatePermissionsUI()
        }
    }

    /** Устаревший колбэк — оставлен для совместимости. */
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isRequestingPermissions = false

        if (requestCode == permissionRequestCode) {
            val allGranted = PermissionManager.hasAllRequiredPermissions(this)

            if (allGranted) {
                finishAndGoToMain()
            } else {
                val hasDeniedPermanently = permissions.indices.any { i ->
                    !grantResults[i].isGranted() &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])
                }

                if (hasDeniedPermanently) {
                    showPermissionDeniedError()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.welcome_permission_denied_message),
                        Toast.LENGTH_LONG
                    ).show()
                    showPermissionDeniedError()
                }

                updatePermissionsUI()
            }
        }
    }

    /** Проверяет, выдан ли grantResult-код. */
    private fun Int.isGranted(): Boolean {
        return this == PackageManager.PERMISSION_GRANTED
    }

    /** Обновляет статусы, счётчик, прогресс-бар и кнопку. */
    private fun updatePermissionsUI() {
        for ((permission, statusView) in permissionStatusViews) {
            val isGranted = PermissionManager.hasPermission(this, permission)
            statusView.text = if (isGranted) {
                getString(R.string.permission_granted)
            } else {
                getString(R.string.permission_denied)
            }
            statusView.setTextColor(
                if (isGranted) {
                    ContextCompat.getColor(this, R.color.status_granted)
                } else {
                    getColorFromAttribute(R.attr.textErrorColor)
                }
            )
        }

        val requiredPermissions = PermissionManager.getRequiredPermissions()
        val totalCount = requiredPermissions.size

        val grantedCount = requiredPermissions.count { permission ->
            PermissionManager.hasPermission(this, permission)
        }

        permissionsCounter.text = getString(
            R.string.welcome_permissions_counter,
            grantedCount,
            totalCount
        )

        val finalProgress = if (totalCount > 0) {
            (grantedCount * 100 / totalCount)
        } else {
            100
        }

        progressBar.progress = finalProgress
        progressText.text = "$finalProgress%"

        val allRequiredGranted = PermissionManager.hasAllRequiredPermissions(this)

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

    /** Показывает блок ошибки о непредоставленных разрешениях. */
    private fun showPermissionDeniedError() {
        errorContainer.visibility = LinearLayout.VISIBLE
        errorContainer.post {
            errorContainer.requestFocus()
        }
    }

    /** Открывает системные настройки приложения. */
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    /** Завершает онбординг: пишет флаг и переходит в MainActivity. */
    private fun finishAndGoToMain() {
        if (isFinishingActivity) return
        isFinishingActivity = true

        LoggerManager.info(
            LoggerTagModel.CORE,
            "CORE.onboarding  completed"
        )

        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        prefs.edit {
            putBoolean("onboarding_completed", true)
            putLong("onboarding_timestamp", System.currentTimeMillis())
            remove("force_show_onboarding")
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivityAnimated(intent)
        finishAnimated()
    }

    /** При возврате обновляет статусы и при готовности — сразу на Main (если не форс). */
    override fun onResume() {
        super.onResume()

        if (isFinishingActivity) return
        isRequestingPermissions = false

        updatePermissionsUI()

        // force_show_onboarding: не автозапускаем Main, даже если разрешения уже есть.
        val forceShow = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .getBoolean("force_show_onboarding", false)

        if (forceShow) return

        if (PermissionManager.hasAllRequiredPermissions(this)) {
            finishAndGoToMain()
        }
    }

    /** Резолвит цвет из темы по атрибуту. */
    private fun getColorFromAttribute(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /** Описание одного разрешения для UI. */
    data class PermissionItem(
        val permission: String,
        val iconRes: Int,
        val titleRes: Int,
        val descRes: Int
    )
}