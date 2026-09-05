// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.settings.AboutFragment
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.GeneralFragment
import by.quty.launch.core.fragments.settings.RecoveryFragment
import by.quty.launch.core.fragments.settings.ShellFragment
import by.quty.launch.core.fragments.settings.StorageFragment
import by.quty.launch.core.fragments.settings.UpdateFragment
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private lateinit var shellManager: ShellManager

    private var isFragmentVisible = false
    private var isTabletMode = false
    private var selectedItemId = -1

    // Флаг для предотвращения множественных перезапусков
    private var isRestarting = false

    // Флаг, что были изменения, требующие перезапуска
    private var needsRestart = false

    // Ширина меню в пикселях для планшетного режима
    private val menuWidthPx: Int by lazy {
        (340 * resources.displayMetrics.density).toInt()
    }

    // Список пунктов меню с цветами (создаётся один раз)
    private val menuItems: List<SettingsMenuModel> by lazy {
        listOf(
            // === ГРУППА 1: Основное ===
            SettingsMenuModel(
                1,
                R.drawable.ic_settings,
                R.string.settings_menu_main,
                R.string.settings_menu_main_desc,
                GeneralFragment::class.java,
                R.color.scheme_green_primary,
                true
            ),
            // === РАЗДЕЛИТЕЛЬ ===
            SettingsMenuModel(
                -1, 0, 0, 0, GeneralFragment::class.java, 0
            ),
            // === ГРУППА 2: Персонализация ===
            SettingsMenuModel(
                2,
                R.drawable.ic_palette,
                R.string.settings_menu_personalization,
                R.string.settings_menu_personalization_desc,
                ShellFragment::class.java,
                R.color.scheme_purple_primary,
                false
            ),
            // === РАЗДЕЛИТЕЛЬ ===
            SettingsMenuModel(
                -2, 0, 0, 0, GeneralFragment::class.java, 0
            ),
            // === ГРУППА 3: Система ===
            SettingsMenuModel(
                6,
                R.drawable.ic_storage,
                R.string.settings_menu_storage,
                R.string.settings_menu_storage_desc,
                StorageFragment::class.java,
                R.color.scheme_cyan_primary,
                true
            ),
            SettingsMenuModel(
                3,
                R.drawable.ic_download,
                R.string.settings_menu_updates,
                R.string.settings_menu_updates_desc,
                UpdateFragment::class.java,
                R.color.scheme_orange_primary,
                true
            ),
            SettingsMenuModel(
                4,
                R.drawable.ic_developer,
                R.string.settings_menu_developer,
                R.string.settings_menu_developer_desc,
                DeveloperFragment::class.java,
                R.color.scheme_red_primary,
                true
            ),
            SettingsMenuModel(
                7,
                R.drawable.ic_recovery,
                R.string.settings_menu_recovery,
                R.string.settings_menu_recovery_desc,
                RecoveryFragment::class.java,
                R.color.scheme_teal_primary,
                true
            ),
            SettingsMenuModel(
                5,
                R.drawable.ic_info,
                R.string.settings_menu_about,
                R.string.settings_menu_about_desc,
                AboutFragment::class.java,
                R.color.scheme_blue_primary,
                true
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shellManager = ShellManager(this, configManager)
        applyOrientation(shellManager)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkMode()
        setupMenu()
        setupButtons()
        setupBackHandler()

        if (savedInstanceState != null) {
            selectedItemId = savedInstanceState.getInt("selected_item_id", -1)
            isFragmentVisible = savedInstanceState.getBoolean("fragment_visible", false)

            /*
             * Восстанавливаем состояние необходимости перезапуска.
             *
             * Ранее этот флаг находился только внутри Fragment,
             * поэтому после пересоздания Fragment состояние могло потеряться.
             */
            needsRestart = savedInstanceState.getBoolean("needs_restart", false)

            if (isFragmentVisible && selectedItemId != -1) {
                val item = menuItems.find { it.id == selectedItemId }
                if (item != null) {
                    restoreFragment(item)
                }
            }
        }

        // На планшете при первом открытии сразу показываем первый пункт
        if (isTabletMode && selectedItemId == -1) {
            showItem(menuItems.first())
        }

        applyMode()

        LoggerManager.d(
            "SettingsActivity",
            getString(R.string.log_settings_restored_needs_restart, needsRestart)
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt("selected_item_id", selectedItemId)
        outState.putBoolean("fragment_visible", isFragmentVisible)

        /*
         * Сохраняем флаг необходимости перезапуска.
         *
         * Это важно при изменении ориентации, темы или другой
         * конфигурации, когда Android может пересоздать Activity.
         */
        outState.putBoolean("needs_restart", needsRestart)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        applyOrientation(shellManager)

        val previousTabletMode = isTabletMode
        checkMode()

        // Если переключились на планшет
        if (!previousTabletMode && isTabletMode) {
            if (selectedItemId == -1) {
                // Ничего не выбрано — показываем первый пункт
                showItem(menuItems.first())
            } else {
                // Есть выбранный пункт — восстанавливаем его
                val item = menuItems.find { it.id == selectedItemId }
                if (item != null) {
                    showItem(item)
                }
            }
            return
        }

        applyMode()
    }

    private fun checkMode() {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        isTabletMode = widthDp >= 600
        LoggerManager.d("SettingsActivity", getString(R.string.log_settings_width_tablet, widthDp, isTabletMode))
    }

    private fun applyMode() {
        val menuParams = binding.menuPane.layoutParams as LinearLayout.LayoutParams
        val contentParams = binding.contentPane.layoutParams as LinearLayout.LayoutParams

        if (isTabletMode) {
            // === ПЛАНШЕТ: меню слева 340dp, контент справа ===
            menuParams.width = menuWidthPx
            menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.weight = 0f

            contentParams.width = 0
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.weight = 1f

            binding.menuPane.visibility = View.VISIBLE
            binding.contentPane.visibility = View.VISIBLE

            /*
             * В планшетном режиме крестик закрытия находится
             * только в панели содержимого.
             */
            binding.btnClose.visibility = View.GONE
            binding.btnCloseContent.visibility = View.VISIBLE

        } else {
            // === ТЕЛЕФОН: только одна панель на весь экран ===
            menuParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.weight = 0f

            contentParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.weight = 0f

            /*
             * В телефонном режиме оба крестика работают
             */
            binding.btnClose.visibility = View.VISIBLE
            binding.btnCloseContent.visibility = View.VISIBLE

            if (isFragmentVisible) {
                binding.menuPane.visibility = View.GONE
                binding.contentPane.visibility = View.VISIBLE
            } else {
                binding.menuPane.visibility = View.VISIBLE
                binding.contentPane.visibility = View.GONE
            }
        }

        binding.menuPane.layoutParams = menuParams
        binding.contentPane.layoutParams = contentParams

        // === ВИДИМОСТЬ КОНТЕНТА ===
        if (isFragmentVisible) {
            binding.fragmentContainer.visibility = View.VISIBLE
            binding.contentToolbar.visibility = View.VISIBLE
            binding.contentTitle.text = getMenuItemTitle()

            if (isTabletMode) {
                binding.recyclerMenu.visibility = View.VISIBLE
                binding.btnBackContent.visibility = View.GONE

                /*
                 * В планшетном режиме активный пункт меню
                 * подсвечивается.
                 */
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(selectedItemId)
            } else {
                binding.recyclerMenu.visibility = View.GONE
                binding.btnBackContent.visibility = View.VISIBLE

                /*
                 * В телефонном режиме активный пункт меню
                 * не должен подсвечиваться.
                 */
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
            }

        } else {
            binding.fragmentContainer.visibility = View.GONE
            binding.recyclerMenu.visibility = View.VISIBLE
            binding.contentToolbar.visibility = View.GONE
            binding.btnBackContent.visibility = View.GONE
            binding.contentTitle.text = ""

            /*
             * В телефонном режиме при возврате из фрагмента
             * меню отображается без активного пункта.
             *
             * В планшетном режиме этот блок нужен только для случая,
             * когда фрагмент ещё не выбран.
             */
            if (!isTabletMode) {
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
            } else if (selectedItemId != -1) {
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(selectedItemId)
            }
        }
    }

    private fun getMenuItemTitle(): String {
        val item = menuItems.find { it.id == selectedItemId }
        return if (item != null) getString(item.title) else ""
    }

    private fun setupMenu() {
        val adapter = SettingsMenuAdapter(menuItems) { item ->
            showItem(item)
        }

        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener {
            checkAndShowRestartDialog()
        }

        binding.btnCloseContent.setOnClickListener {
            checkAndShowRestartDialog()
        }

        binding.btnBackContent.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!isTabletMode && isFragmentVisible) {
                        /*
                         * Сначала сбрасываем подсветку активного пункта меню,
                         * чтобы избежать моргания.
                         */
                        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)

                        /*
                         * В телефонном режиме возвращаемся
                         * из фрагмента обратно в меню.
                         *
                         * selectedItemId НЕ сбрасываем,
                         * чтобы сохранить последний выбор для
                         * возможного перехода в планшетный режим.
                         */
                        isFragmentVisible = false
                        applyMode()
                        return
                    }

                    // В планшетном режиме или при закрытом фрагменте — проверяем изменения
                    checkAndShowRestartDialog()
                }
            }
        )
    }

    // ============================================================
    // СОСТОЯНИЕ НАСТРОЕК
    // ============================================================

    /**
     * Отмечает, что пользователь изменил настройку,
     * которая требует перезапуска приложения.
     *
     * Флаг хранится в SettingsActivity, а не в Fragment.
     * Поэтому переключение между разделами, пересоздание Fragment
     * или изменение конфигурации не теряет состояние.
     */
    fun markRestartRequired() {
        if (!needsRestart) {
            needsRestart = true
            LoggerManager.d("SettingsActivity", getString(R.string.log_settings_restart_required))
        }
    }

    /**
     * Возвращает текущее состояние необходимости перезапуска.
     */
    fun getNeedsRestart(): Boolean {
        return needsRestart
    }

    // ============================================================
    // ДИАЛОГ ПЕРЕЗАПУСКА
    // ============================================================

    /**
     * Проверяет, были ли изменения, требующие перезапуска.
     *
     * ВАЖНО:
     * Состояние больше не определяется через текущий Fragment.
     *
     * Ранее использовался findFragmentByTag()/findFragmentById(),
     * из-за чего состояние могло теряться при пересоздании Fragment
     * или при переключении между разделами.
     */
    private fun checkAndShowRestartDialog() {
        LoggerManager.d(
            "SettingsActivity",
            getString(R.string.log_settings_checking_restart_state, needsRestart)
        )

        if (needsRestart) {
            showRestartDialog()
        } else {
            // Если изменений нет — просто закрываем
            finish()
        }
    }

    /**
     * Показывает кастомный Glassmorphism диалог с предложением перезапустить приложение
     */
    private fun showRestartDialog() {
        if (isRestarting) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_restart, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Делаем фон диалога прозрачным
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ===== РАЗМЫТИЕ ДЛЯ ANDROID 12+ =====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window?.decorView?.rootView
            decorView?.let { view ->
                val renderEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                view.setRenderEffect(renderEffect)
                dialog.setOnDismissListener {
                    view.setRenderEffect(null)
                }
            }
        }

        // Настраиваем кнопки
        dialogView.findViewById<Button>(R.id.btn_restart).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }
            isRestarting = true
            restartApp()
        }

        dialogView.findViewById<Button>(R.id.btn_later).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }

            /*
             * НЕ СБРАСЫВАЕМ needsRestart!
             *
             * Пользователь выбрал "Позже", поэтому изменения
             * всё ещё требуют перезапуска.
             *
             * При следующем закрытии настроек диалог снова появится.
             */
        }

        dialog.show()
    }

    /**
     * Перезапуск приложения с задержкой
     */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(intent)
            finish()
        }, 500)
    }

    fun restartAppWithOrientation() {
        configManager.setRestartForOrientationFlag()
        restartApp()
    }

    private fun showItem(item: SettingsMenuModel) {
        isFragmentVisible = true
        selectedItemId = item.id
        binding.contentTitle.text = getString(item.title)

        try {
            val fragment = supportFragmentManager.fragmentFactory.instantiate(
                item.fragment.classLoader!!,
                item.fragment.name
            )

            supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()

        } catch (e: Exception) {
            LoggerManager.e("SettingsActivity", getString(R.string.log_settings_error), e)
        }

        /*
         * Подсветка активного пункта меню разрешена
         * только в планшетном режиме.
         */
        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(
            if (isTabletMode) item.id else -1
        )

        applyMode()
    }

    private fun restoreFragment(item: SettingsMenuModel) {
        isFragmentVisible = true
        selectedItemId = item.id
        binding.contentTitle.text = getString(item.title)

        /*
         * Подсветка активного пункта меню разрешена
         * только в планшетном режиме.
         */
        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(
            if (isTabletMode) item.id else -1
        )

        applyMode()
    }

    override fun onResume() {
        super.onResume()

        applyOrientation(shellManager)

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onDestroy() {
        try {
            supportFragmentManager.popBackStackImmediate(null, 0)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}