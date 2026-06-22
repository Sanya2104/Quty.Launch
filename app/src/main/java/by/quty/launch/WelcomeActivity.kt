// WelcomeActivity.kt

package by.quty.launch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.edit
import by.quty.launch.core.PermissionManager

class WelcomeActivity : BaseActivity() {

    private val permissionRequestCode = 100

    // UI элементы
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnGrant: Button
    private lateinit var errorContainer: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var btnSettings: Button
    private lateinit var permissionsContainer: LinearLayout
    private lateinit var tvOptionalNote: TextView
    private lateinit var tvRequiredNote: TextView

    // Карта статусов разрешений
    private val permissionStatusViews = mutableMapOf<String, TextView>()

    // Флаг, что мы уже запрашивали разрешения
    private var isRequestingPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        initViews()
        setupVersionInfo()
        setupPermissionsUI()
        updatePermissionsUI()
        setupListeners()

        // Если все обязательные разрешения уже есть - сразу переходим в MainActivity
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
        tvOptionalNote = findViewById(R.id.tv_optional_note)
        tvRequiredNote = findViewById(R.id.tv_required_note)
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

        // Список разрешений для отображения с их ресурсами
        val permissionsData = listOf(
            Triple(
                Manifest.permission.READ_PHONE_STATE,
                R.string.welcome_permission_phone,
                R.string.welcome_permission_phone_desc
            ),
            Triple(
                Manifest.permission.ACCESS_NETWORK_STATE,
                R.string.welcome_permission_network,
                R.string.welcome_permission_network_desc
            ),
            Triple(
                Manifest.permission.ACCESS_WIFI_STATE,
                R.string.welcome_permission_wifi,
                R.string.welcome_permission_wifi_desc
            ),
            Triple(
                Manifest.permission.ACCESS_FINE_LOCATION,
                R.string.welcome_permission_location,
                R.string.welcome_permission_location_desc
            ),
        )

        var hasOptional = false

        for ((permission, titleRes, descRes) in permissionsData) {
            val info = PermissionManager.getPermissionInfo(permission)
            if (!info.isRequired) hasOptional = true

            val itemView = createPermissionItem(
                permission = permission,
                title = getString(titleRes),
                description = getString(descRes)
            )
            permissionsContainer.addView(itemView)
        }

        tvOptionalNote.visibility = if (hasOptional) View.VISIBLE else View.GONE
    }

    private fun createPermissionItem(
        permission: String,
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

        // Основная строка с названием и статусом
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
        }

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = title
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.white))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val statusView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "❌"
            textSize = 20f
            id = View.generateViewId()
        }

        // Сохраняем для обновления
        permissionStatusViews[permission] = statusView

        rowLayout.addView(titleView)
        rowLayout.addView(statusView)

        // Описание разрешения
        val descView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
                setMargins(16, 0, 16, 0)
            }
            text = description
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, android.R.color.darker_gray))
            textSize = 14f
        }

        container.addView(rowLayout)
        container.addView(descView)

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

    private fun requestAllPermissions() {
        if (isRequestingPermissions) return
        isRequestingPermissions = true

        val missingPermissions = PermissionManager.getMissingRequiredPermissions(this)

        if (missingPermissions.isEmpty()) {
            finishAndGoToMain()
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

            if (allRequiredGranted) {
                finishAndGoToMain()
            } else {
                showPermissionDeniedError()
            }

            updatePermissionsUI()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePermissionsUI() {
        // Обновляем статусы
        for ((permission, statusView) in permissionStatusViews) {
            val isGranted = PermissionManager.hasPermission(this, permission)
            statusView.text = if (isGranted) "✅" else "❌"
        }

        // Обновляем прогресс
        val progress = PermissionManager.getRequiredProgress(this)
        progressBar.progress = progress
        progressText.text = "$progress%"

        // Проверяем, все ли обязательные разрешения получены
        val allRequiredGranted = PermissionManager.hasAllRequiredPermissions(this)
        val hasMissing = PermissionManager.getMissingRequiredPermissions(this).isNotEmpty()

        if (allRequiredGranted) {
            btnGrant.text = getString(R.string.welcome_button_start)
            btnGrant.isEnabled = true
            btnGrant.alpha = 1.0f
            btnGrant.setOnClickListener {
                finishAndGoToMain()
            }
            tvRequiredNote.text = getString(R.string.welcome_required_done)
            tvRequiredNote.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            btnGrant.text = getString(R.string.welcome_button_grant)
            btnGrant.isEnabled = hasMissing
            btnGrant.alpha = if (hasMissing) 1.0f else 0.5f
            btnGrant.setOnClickListener {
                requestAllPermissions()
            }
            tvRequiredNote.text = getString(R.string.welcome_required_note)
            tvRequiredNote.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
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

    private fun finishAndGoToMain() {
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
        updatePermissionsUI()

        if (PermissionManager.hasAllRequiredPermissions(this)) {
            finishAndGoToMain()
        }
    }
}