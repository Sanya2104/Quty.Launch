// *** core/fragments/settings/ShellFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.ColorSchemeAdapter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.model.ColorSchemeModel

/**
 * Фрагмент "Персонализация" для Настроек
 */
class ShellFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var colorSchemeAdapter: ColorSchemeAdapter

    // Карточка активной оболочки
    private lateinit var shellCard: View
    private lateinit var shellEmpty: TextView
    private lateinit var shellPreview: ImageView
    private lateinit var shellName: TextView
    private lateinit var shellVersion: TextView
    private lateinit var shellMenuButton: ImageButton

    // Ссылка на магазин
    private lateinit var storeRow: View

    // Секция «Анимации»
    private lateinit var animationsSwitch: SwitchCompat
    private lateinit var animationsSystemHint: TextView

    // Safe Mode баннер и кнопка выхода
    private lateinit var safeModeBanner: View
    private lateinit var safeModeExitButton: Button

    private var needsRestart = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_shell, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)
            needsRestart = settingsActivity.getNeedsRestart()
        }

        shellCard = view.findViewById(R.id.shell_card)
        shellEmpty = view.findViewById(R.id.shell_empty)
        shellPreview = view.findViewById(R.id.shell_preview)
        shellName = view.findViewById(R.id.shell_name)
        shellVersion = view.findViewById(R.id.shell_version)
        shellMenuButton = view.findViewById(R.id.shell_menu_button)
        storeRow = view.findViewById(R.id.store_row)

        animationsSwitch = view.findViewById(R.id.animations_switch)
        animationsSystemHint = view.findViewById(R.id.animations_system_hint)

        safeModeBanner = view.findViewById(R.id.safe_mode_banner)
        safeModeExitButton = view.findViewById(R.id.safe_mode_exit_button)

        shellMenuButton.setOnClickListener {
            showShellInfoDialog()
        }

        storeRow.setOnClickListener {
            openStore()
        }

        setupColorSchemeSelector(view)
        setupAnimationsSection()
        setupSafeModeBanner()
        refreshActiveShell()
    }

    override fun onResume() {
        super.onResume()

        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }

        refreshActiveShell()
        setupAnimationsSection()
        setupSafeModeBanner()
    }

    // ============================================================
    // SAFE MODE БАННЕР
    // ============================================================

    /**
     * Показывает/скрывает баннер Safe Mode в зависимости от флага.
     * Кнопка «Выйти» снимает флаг и перезапускает приложение.
     */
    private fun setupSafeModeBanner() {
        val isSafeMode = configManager.isSafeModeActive()

        if (isSafeMode) {
            safeModeBanner.visibility = View.VISIBLE

            safeModeExitButton.setOnClickListener {
                configManager.setSafeModeActive(false)
                Toast.makeText(
                    requireContext(),
                    R.string.safe_mode_exited_toast,
                    Toast.LENGTH_LONG
                ).show()

                // Перезапускаем, чтобы ShellManager подхватил обычную оболочку
                val baseActivity = activity as? by.quty.launch.BaseActivity
                val intent = Intent(requireContext(), by.quty.launch.MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (baseActivity != null) {
                    baseActivity.startActivityAnimated(intent)
                    baseActivity.finishAnimated()
                } else {
                    startActivity(intent)
                    requireActivity().finish()
                }
            }
        } else {
            safeModeBanner.visibility = View.GONE
            safeModeExitButton.setOnClickListener(null)
        }
    }

    // ============================================================
    // ОБОЛОЧКА
    // ============================================================

    private fun refreshActiveShell() {
        val shell = shellManager.getActiveShell()

        if (shell == null) {
            shellCard.visibility = View.GONE
            shellEmpty.visibility = View.VISIBLE
            return
        }

        shellCard.visibility = View.VISIBLE
        shellEmpty.visibility = View.GONE

        shellName.text = shell.displayName ?: shell.name

        shellVersion.text = if (!shell.version.isNullOrEmpty()) {
            getString(R.string.shell_version_with_label, shell.version)
        } else {
            getString(R.string.shell_version_with_label, getString(R.string.unknown))
        }

        loadPreview(shell.previewBase64)
    }

    private fun loadPreview(base64: String?) {
        if (base64.isNullOrEmpty()) {
            shellPreview.setImageResource(R.drawable.ic_image)
            return
        }

        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                shellPreview.setImageBitmap(bitmap)
            } else {
                shellPreview.setImageResource(R.drawable.ic_image)
            }
        } catch (_: Exception) {
            shellPreview.setImageResource(R.drawable.ic_image)
        }
    }

    private fun setupColorSchemeSelector(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.color_scheme_list)

        recyclerView.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )

        val currentSchemeId = configManager.getColorScheme()

        colorSchemeAdapter = ColorSchemeAdapter { scheme ->
            applyColorScheme(scheme)
        }

        recyclerView.adapter = colorSchemeAdapter
        colorSchemeAdapter.setSelectedScheme(currentSchemeId)
    }

    private fun applyColorScheme(scheme: ColorSchemeModel) {
        configManager.setColorScheme(scheme.id)

        (activity as? SettingsActivity)?.markRestartRequired()

        val appContext = requireContext().applicationContext
        val displayName = scheme.getDisplayName(appContext)
        Toast.makeText(
            appContext,
            appContext.getString(R.string.color_scheme_applied, displayName),
            Toast.LENGTH_SHORT
        ).show()

        (activity as? SettingsActivity)?.recreate()
    }

    private fun setupAnimationsSection() {
        val systemEnabled = configManager.isSystemAnimationEnabled()
        val userEnabled = configManager.isAnimationsEnabled()

        if (systemEnabled) {
            animationsSwitch.isEnabled = true
            animationsSwitch.alpha = 1.0f
            animationsSwitch.isChecked = userEnabled
            animationsSystemHint.visibility = View.GONE

            animationsSwitch.setOnCheckedChangeListener { _, isChecked ->
                configManager.setAnimationsEnabled(isChecked)
                (activity as? SettingsActivity)?.markRestartRequired()
            }
        } else {
            animationsSwitch.isEnabled = false
            animationsSwitch.alpha = 0.5f
            animationsSwitch.isChecked = false
            animationsSystemHint.visibility = View.VISIBLE
            animationsSwitch.setOnCheckedChangeListener(null)
        }
    }

    private fun openStore() {
        try {
            val intent = Intent(requireContext(), StoreActivity::class.java)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.store_load_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("InflateParams")
    private fun showShellInfoDialog() {
        val shell = shellManager.getActiveShell()
        if (shell == null) {
            Toast.makeText(requireContext(), R.string.settings_personalization_no_shell, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_shell_info, null)

        dialogView.findViewById<TextView>(R.id.shell_info_name_value).text =
            shell.displayName ?: shell.name

        dialogView.findViewById<TextView>(R.id.shell_info_version_value).text =
            shell.version ?: getString(R.string.unknown)

        dialogView.findViewById<TextView>(R.id.shell_info_author_value).text =
            shell.author ?: getString(R.string.author_default)

        dialogView.findViewById<TextView>(R.id.shell_info_type_value).text =
            if (shell.isCustom) getString(R.string.shell_type_custom)
            else getString(R.string.shell_type_builtin)

        dialogView.findViewById<TextView>(R.id.shell_info_min_version_value).text =
            shell.minQutyLaunchVersion
                ?: getString(R.string.shell_info_min_version_not_specified)

        dialogView.findViewById<TextView>(R.id.shell_info_orientation_value).text =
            mapOrientation(shell.orientation)

        val repoContainer = dialogView.findViewById<LinearLayout>(R.id.shell_info_repo_container)
        val repoButton = dialogView.findViewById<Button>(R.id.shell_info_repo_button)

        if (!shell.repoUrl.isNullOrEmpty()) {
            repoContainer.visibility = View.VISIBLE
            repoButton.setOnClickListener {
                openUrl(shell.repoUrl)
            }
        } else {
            repoContainer.visibility = View.GONE
        }

        val telegramContainer = dialogView.findViewById<LinearLayout>(R.id.shell_info_telegram_container)
        val telegramButton = dialogView.findViewById<Button>(R.id.shell_info_telegram_button)

        if (!shell.telegramUrl.isNullOrEmpty()) {
            telegramContainer.visibility = View.VISIBLE
            telegramButton.setOnClickListener {
                openUrl(shell.telegramUrl)
            }
        } else {
            telegramContainer.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        dialogView.findViewById<ImageButton>(R.id.shell_info_close_button).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun mapOrientation(orientation: String?): String {
        return when (orientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            "sensor" -> getString(R.string.orientation_auto)
            "user" -> getString(R.string.orientation_auto)
            else -> getString(R.string.shell_info_orientation_none)
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun getNeedsRestart(): Boolean = needsRestart

    fun setNeedsRestart(value: Boolean) {
        needsRestart = value
    }
}