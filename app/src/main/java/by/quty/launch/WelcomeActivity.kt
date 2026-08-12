// *** WelcomeActivity.kt *** //
package by.quty.launch

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.PermissionManager

/**
 * Активность приветствия (онбординг)
 * Запрашивает необходимые разрешения перед запуском приложения
 */
class WelcomeActivity : BaseActivity() {

    // Код запроса разрешений (из конфига)
    private val permissionRequestCode = CoreConfig.PERMISSION_REQUEST_CODE

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

    // Флаг, что активность уже завершается
    private var isFinishingActivity = false

    // Регистрируем ActivityResult для запроса разрешений (Android 11+)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        isRequestingPermissions = false
        handlePermissionResults(results)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        initViews()
        setupVersionInfo()
        setupPermissionsUI()
        updatePermissionsUI()
        setupListeners()

        // Проверяем, все ли разрешения уже есть
        if (PermissionManager.hasAllRequiredPermissions(this)) {
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
        val permissionsData = listOf(
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
            )
        )

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
                resources.getDimension(R.dimen.icon_medium).toInt(),
                resources.getDimension(R.dimen.icon_medium).toInt()
            ).apply {
                marginEnd = resources.getDimension(R.dimen.spacing_l).toInt()
            }
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(this@WelcomeActivity, R.color.text_primary))
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
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.text_primary))
            textSize = resources.getDimension(R.dimen.text_l) / resources.displayMetrics.density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val descView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = description
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.text_dim))
            textSize = resources.getDimension(R.dimen.text_s) / resources.displayMetrics.density
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
                marginEnd = resources.getDimension(R.dimen.spacing_s).toInt()
            }
            text = getString(R.string.permission_denied)
            textSize = resources.getDimension(R.dimen.text_m) / resources.displayMetrics.density
            id = View.generateViewId()
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.text_error))
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
            textSize = resources.getDimension(R.dimen.text_xxl) / resources.displayMetrics.density
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.text_dim))
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
     * Запрашивает все необходимые разрешения
     */
    private fun requestAllPermissions() {
        if (isRequestingPermissions) return
        isRequestingPermissions = true

        val missingPermissions = PermissionManager.getMissingRequiredPermissions(this)

        if (missingPermissions.isEmpty()) {
            isRequestingPermissions = false
            finishAndGoToMain()
            return
        }

        // Используем ActivityResult для Android 11+
        permissionLauncher.launch(missingPermissions.toTypedArray())
    }

    /**
     * Обрабатывает результаты запроса разрешений
     */
    private fun handlePermissionResults(results: Map<String, Boolean>) {
        isRequestingPermissions = false

        val allGranted = PermissionManager.hasAllRequiredPermissions(this)

        if (allGranted) {
            finishAndGoToMain()
        } else {
            // Проверяем, были ли отклонены разрешения навсегда
            val hasDeniedPermanently = results.entries.any { (permission, granted) ->
                !granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }

            if (hasDeniedPermanently) {
                showPermissionDeniedError()
            } else {
                // Разрешения были отклонены, но их можно запросить снова
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

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isRequestingPermissions = false

        if (requestCode == permissionRequestCode) {
            // Для обратной совместимости со старыми устройствами
            val allGranted = PermissionManager.hasAllRequiredPermissions(this)

            if (allGranted) {
                finishAndGoToMain()
            } else {
                // Проверяем, были ли отклонены разрешения навсегда
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

    private fun Int.isGranted(): Boolean {
        return this == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("SetTextI18n")
    private fun updatePermissionsUI() {
        // Обновляем статусы
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
                    ContextCompat.getColor(this, R.color.text_error)
                }
            )
        }

        // Подсчитываем прогресс
        val requiredPermissions = PermissionManager.getRequiredPermissions()
        val totalCount = requiredPermissions.size

        val grantedCount = requiredPermissions.count { permission ->
            PermissionManager.hasPermission(this, permission)
        }

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

    private fun showPermissionDeniedError() {
        errorContainer.visibility = LinearLayout.VISIBLE
        errorContainer.post {
            errorContainer.requestFocus()
        }
    }

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

    private fun finishAndGoToMain() {
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

        if (isFinishingActivity) return
        isRequestingPermissions = false

        updatePermissionsUI()

        if (PermissionManager.hasAllRequiredPermissions(this)) {
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