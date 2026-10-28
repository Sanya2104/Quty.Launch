// *** SettingsActivity.kt *** //
package by.quty.launch

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.settings.AboutFragment
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.GeneralFragment
import by.quty.launch.core.fragments.settings.RecoveryFragment
import by.quty.launch.core.fragments.settings.ShellFragment
import by.quty.launch.core.fragments.settings.StorageFragment
import by.quty.launch.core.fragments.settings.ApiMethodsFragment
import by.quty.launch.core.fragments.settings.UpdateFragment
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.SystemUpdateManager
import by.quty.launch.core.model.MenuModel
import by.quty.launch.core.utilities.BadgeHelper
import by.quty.launch.databinding.ActivitySettingsBinding

/**
 * Настройки: меню слева, контент справа (телефон — переключение экранов).
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private lateinit var shellManager: ShellManager

    private var isFragmentVisible = false
    private var isTabletMode = false
    private var selectedItemId = -1

    private var menuItems: List<MenuModel> = emptyList()

    private var lastUpdateAvailable: Boolean = false

    private val menuWidthPx: Int by lazy {
        (340 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shellManager = ShellManager(this, configManager)
        applyOrientation(shellManager)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lastUpdateAvailable = try {
            SystemUpdateManager(this).getCachedUpdate()?.hasUpdate == true
        } catch (_: Exception) {
            false
        }

        menuItems = buildMenuItems()

        checkMode()
        setupMenu()
        setupButtons()
        setupBackHandler()

        if (savedInstanceState != null) {
            selectedItemId = savedInstanceState.getInt("selected_item_id", -1)
            isFragmentVisible = savedInstanceState.getBoolean("fragment_visible", false)

            if (isFragmentVisible && selectedItemId != -1) {
                val item = menuItems.find { it.id == selectedItemId }
                if (item != null) {
                    restoreFragment(item)
                } else {
                    isFragmentVisible = false
                    selectedItemId = -1
                }
            }
        }

        if (isTabletMode && selectedItemId == -1 && menuItems.isNotEmpty()) {
            showItem(menuItems.first())
        }

        applyMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt("selected_item_id", selectedItemId)
        outState.putBoolean("fragment_visible", isFragmentVisible)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        applyOrientation(shellManager)

        val previousTabletMode = isTabletMode
        checkMode()

        if (!previousTabletMode && isTabletMode) {
            if (selectedItemId == -1) {
                if (menuItems.isNotEmpty()) {
                    showItem(menuItems.first())
                }
            } else {
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
    }

    private fun applyMode() {
        val menuParams = binding.menuPane.layoutParams as LinearLayout.LayoutParams
        val contentParams = binding.contentPane.layoutParams as LinearLayout.LayoutParams

        if (isTabletMode) {
            menuParams.width = menuWidthPx
            menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.weight = 0f

            contentParams.width = 0
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.weight = 1f

            binding.menuPane.visibility = View.VISIBLE
            binding.contentPane.visibility = View.VISIBLE

            binding.btnClose.visibility = View.GONE
            binding.btnCloseContent.visibility = View.VISIBLE

        } else {
            menuParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            menuParams.weight = 0f

            contentParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.weight = 0f

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

        if (isFragmentVisible) {
            binding.fragmentContainer.visibility = View.VISIBLE
            binding.contentToolbar.visibility = View.VISIBLE
            binding.contentTitle.text = getMenuItemTitle()

            if (isTabletMode) {
                binding.recyclerMenu.visibility = View.VISIBLE
                binding.btnBackContent.visibility = View.GONE
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(selectedItemId)
            } else {
                binding.recyclerMenu.visibility = View.GONE
                binding.btnBackContent.visibility = View.VISIBLE
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
            }

        } else {
            binding.fragmentContainer.visibility = View.GONE
            binding.recyclerMenu.visibility = View.VISIBLE
            binding.contentToolbar.visibility = View.GONE
            binding.btnBackContent.visibility = View.GONE
            binding.contentTitle.text = ""

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
                        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
                        isFragmentVisible = false
                        applyMode()
                        return
                    }

                    checkAndShowRestartDialog()
                }
            }
        )
    }

    // ============================================================
    // МЕНЮ
    // ============================================================

    /** Строит меню: DevMode-пункт только при активном DevMode; маркер на «Центр обновления». */
    private fun buildMenuItems(): List<MenuModel> {
        val prefs = getSharedPreferences("developer_prefs", MODE_PRIVATE)
        val isDevMode = prefs.getBoolean("developer_mode", false)

        val cachedUpdate = try {
            SystemUpdateManager(this).getCachedUpdate()
        } catch (_: Exception) {
            null
        }
        val hasUpdate = cachedUpdate?.hasUpdate == true

        val updateBadgeColor: String?
        val updateBadgeColorHex: String?

        if (hasUpdate) {
            val isCritical = cachedUpdate.versionInfo?.isCritical == true
            val pair = BadgeHelper.resolveUpdateBadge(isCritical, this)
            updateBadgeColor = pair.first
            updateBadgeColorHex = pair.second
        } else {
            updateBadgeColor = null
            updateBadgeColorHex = null
        }

        val items = mutableListOf<MenuModel>()

        items.add(
            MenuModel(
                1,
                R.drawable.ic_settings,
                R.string.settings_menu_main,
                R.string.settings_menu_main_desc,
                GeneralFragment::class.java,
                R.color.scheme_green_primary,
                true
            )
        )

        items.add(
            MenuModel(
                2,
                R.drawable.ic_palette,
                R.string.settings_menu_personalization,
                R.string.settings_menu_personalization_desc,
                ShellFragment::class.java,
                R.color.scheme_purple_primary,
                false
            )
        )

        items.add(
            MenuModel(
                6,
                R.drawable.ic_storage,
                R.string.settings_menu_storage,
                R.string.settings_menu_storage_desc,
                StorageFragment::class.java,
                R.color.scheme_cyan_primary,
                true
            )
        )

        items.add(
            MenuModel(
                8,
                R.drawable.ic_api_method,
                R.string.settings_menu_api_methods,
                R.string.settings_menu_api_methods_desc,
                ApiMethodsFragment::class.java,
                R.color.scheme_pink_primary,
                true
            )
        )

        items.add(
            MenuModel(
                3,
                R.drawable.ic_download,
                R.string.settings_menu_updates,
                R.string.settings_menu_updates_desc,
                UpdateFragment::class.java,
                R.color.scheme_orange_primary,
                true,
                badge = hasUpdate,
                badgeColor = updateBadgeColor,
                badgeColorHex = updateBadgeColorHex
            )
        )

        if (isDevMode) {
            items.add(
                MenuModel(
                    4,
                    R.drawable.ic_developer,
                    R.string.settings_menu_developer,
                    R.string.settings_menu_developer_desc,
                    DeveloperFragment::class.java,
                    R.color.scheme_red_primary,
                    true
                )
            )
        }

        items.add(
            MenuModel(
                7,
                R.drawable.ic_recovery,
                R.string.settings_menu_recovery,
                R.string.settings_menu_recovery_desc,
                RecoveryFragment::class.java,
                R.color.scheme_teal_primary,
                true
            )
        )

        items.add(
            MenuModel(
                5,
                R.drawable.ic_info,
                R.string.settings_menu_about,
                R.string.settings_menu_about_desc,
                AboutFragment::class.java,
                R.color.scheme_blue_primary,
                true
            )
        )

        return items
    }

    /** Пересобирает меню (DevMode переключён) и закрывает исчезнувший фрагмент. */
    fun refreshMenuItems() {
        menuItems = buildMenuItems()

        if (isFragmentVisible && selectedItemId != -1) {
            val stillExists = menuItems.any { it.id == selectedItemId }
            if (!stillExists) {
                isFragmentVisible = false
                selectedItemId = -1

                supportFragmentManager.findFragmentById(R.id.fragment_container)?.let { fragment ->
                    supportFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .remove(fragment)
                        .commitNow()
                }
            }
        }

        binding.recyclerMenu.adapter = SettingsMenuAdapter(menuItems) { item ->
            showItem(item)
        }

        applyMode()
    }

    // ============================================================
    // СМЕНА ЯЗЫКА
    // ============================================================

    /** Пересоздаёт SettingsActivity после смены языка. */
    fun applyLanguageAndRecreate() {
        recreate()
    }

    private fun showItem(item: MenuModel) {
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
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container, fragment)
                .commitNow()

        } catch (_: Exception) {
        }

        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(
            if (isTabletMode) item.id else -1
        )

        applyMode()
    }

    private fun restoreFragment(item: MenuModel) {
        isFragmentVisible = true
        selectedItemId = item.id
        binding.contentTitle.text = getString(item.title)

        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(
            if (isTabletMode) item.id else -1
        )

        applyMode()
    }

    override fun onResume() {
        super.onResume()

        applyOrientation(shellManager)

        val currentUpdateAvailable = try {
            SystemUpdateManager(this).getCachedUpdate()?.hasUpdate == true
        } catch (_: Exception) {
            false
        }

        if (currentUpdateAvailable != lastUpdateAvailable) {
            lastUpdateAvailable = currentUpdateAvailable
            refreshMenuItems()
        }

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}