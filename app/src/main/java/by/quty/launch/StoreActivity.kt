// *** StoreActivity.kt *** //
package by.quty.launch

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.store.StoreCatalogFragment
import by.quty.launch.core.fragments.store.StoreImportFragment
import by.quty.launch.core.fragments.store.StoreItemDetailFragment
import by.quty.launch.core.fragments.store.StoreSettingsFragment
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.MenuModel
import by.quty.launch.core.model.StoreItemModel
import by.quty.launch.core.model.StoreRemoteItemModel
import by.quty.launch.databinding.ActivityStoreBinding
import kotlinx.coroutines.launch

/**
 * Магазин: Каталог / Настройки / Импорт. На телефоне — переключение, на планшете — две панели.
 * Детальный экран — через addToBackStack.
 */
class StoreActivity : BaseActivity() {

    companion object {
        private const val CATALOG_FRAGMENT_ID = 1
    }

    private lateinit var binding: ActivityStoreBinding

    private lateinit var shellManager: ShellManager
    private lateinit var storeManager: StoreManager

    private var isFragmentVisible = false
    private var isTabletMode = false
    private var selectedItemId = -1

    private var menuItems: List<MenuModel> = emptyList()

    private var currentItems: List<StoreItemModel> = emptyList()
    private var currentRemote: List<StoreRemoteItemModel> = emptyList()

    private val menuWidthPx: Int by lazy {
        (340 * resources.displayMetrics.density).toInt()
    }

    /** Инициализирует экран магазина, меню, восстанавливает состояние, загружает каталог. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientation()

        binding = ActivityStoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shellManager = ShellManager(this, configManager)
        storeManager = StoreManager(this)

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

        if (selectedItemId == -1 && menuItems.isNotEmpty()) {
            showItem(menuItems.first())
        }

        applyMode()

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        refreshItems()
    }

    /** Сохраняет выбранный пункт и видимость фрагмента. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_item_id", selectedItemId)
        outState.putBoolean("fragment_visible", isFragmentVisible)
    }

    /** Перестраивает UI при повороте/смене размера. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        applyOrientation()

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

    /** Определяет планшетный режим по ширине экрана. */
    private fun checkMode() {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        isTabletMode = widthDp >= 600
    }

    /** Раскладывает панели под текущий режим (планшет/телефон). */
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
            binding.contentTitle.text = getCurrentTitle()

            if (isTabletMode) {
                binding.recyclerMenu.visibility = View.VISIBLE
                binding.btnBackContent.visibility = View.GONE
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(selectedItemId)
            } else {
                binding.recyclerMenu.visibility = View.GONE
                binding.btnBackContent.visibility = View.VISIBLE
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
                updateBackButtonIcon()
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

    /** Каталог — ic_menu; остальные — ic_back. */
    private fun updateBackButtonIcon() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isDetail = currentFragment is StoreItemDetailFragment
        val isCatalog = selectedItemId == CATALOG_FRAGMENT_ID

        val iconRes = if (isDetail || !isCatalog) {
            R.drawable.ic_back
        } else {
            R.drawable.ic_menu
        }
        binding.btnBackContentIcon.setImageResource(iconRes)
    }

    /** Возвращает заголовок: имя элемента при детальном экране, иначе заголовок пункта меню. */
    private fun getCurrentTitle(): String {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is StoreItemDetailFragment) {
            val name = currentFragment.requireArguments().getString("arg_item_name")
            val found = currentItems.find { it.name == name }
            return found?.displayName ?: name ?: ""
        }
        val item = menuItems.find { it.id == selectedItemId }
        return if (item != null) getString(item.title) else ""
    }

    /** Настраивает RecyclerView меню магазина. */
    private fun setupMenu() {
        val adapter = SettingsMenuAdapter(menuItems) { item ->
            showItem(item)
        }
        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter
    }

    /** Вешает клики на кнопки закрытия/назад с учётом диалога перезапуска. */
    private fun setupButtons() {
        binding.btnClose.setOnClickListener { checkAndShowRestartDialog() }
        binding.btnCloseContent.setOnClickListener { checkAndShowRestartDialog() }
        binding.btnBackContent.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Настраивает обработчик «Назад»: backStack → деталь, затем меню, затем выход. */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStackImmediate()
                        binding.contentTitle.text = getMenuItemTitle()
                        applyMode()
                        return
                    }

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

    /** Строит список пунктов меню магазина. */
    private fun buildMenuItems(): List<MenuModel> {
        return listOf(
            MenuModel(
                CATALOG_FRAGMENT_ID,
                R.drawable.ic_store,
                R.string.store_menu_catalog,
                R.string.store_menu_catalog_desc,
                StoreCatalogFragment::class.java,
                R.color.scheme_green_primary,
                true
            ),
            MenuModel(
                2,
                R.drawable.ic_settings,
                R.string.store_menu_settings,
                R.string.store_menu_settings_desc,
                StoreSettingsFragment::class.java,
                R.color.scheme_cyan_primary,
                true
            ),
            MenuModel(
                3,
                R.drawable.ic_folder,
                R.string.store_menu_import,
                R.string.store_menu_import_desc,
                StoreImportFragment::class.java,
                R.color.scheme_blue_primary,
                true
            )
        )
    }

    /** Возвращает строковый заголовок выбранного пункта меню. */
    private fun getMenuItemTitle(): String {
        val item = menuItems.find { it.id == selectedItemId }
        return if (item != null) getString(item.title) else ""
    }

    /** Показывает фрагмент выбранного пункта меню. */
    private fun showItem(item: MenuModel) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }

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

    /** Восстанавливает UI для выбранного пункта без пересоздания фрагмента. */
    private fun restoreFragment(item: MenuModel) {
        isFragmentVisible = true
        selectedItemId = item.id
        binding.contentTitle.text = getString(item.title)

        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(
            if (isTabletMode) item.id else -1
        )

        applyMode()
    }

    // ============================================================
    // ДЕТАЛЬНЫЙ ЭКРАН
    // ============================================================

    /** Открывает детальный экран элемента через addToBackStack. */
    fun showItemDetail(item: StoreItemModel) {
        val fragment = StoreItemDetailFragment.newInstance(item.name)

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        binding.contentTitle.text = item.displayName
        binding.btnBackContent.visibility = View.VISIBLE
        binding.btnBackContentIcon.setImageResource(R.drawable.ic_back)
    }

    /** Закрывает детальный экран и возвращается к меню. */
    fun hideItemDetail() {
        if (isFinishing || isDestroyed) return

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        binding.contentTitle.text = getMenuItemTitle()
        applyMode()
    }

    // ============================================================
    // ДАННЫЕ ДЛЯ ДЕТАЛЬНОГО ФРАГМЕНТА
    // ============================================================

    /** Ищет элемент каталога по имени. */
    fun findItemByName(name: String): StoreItemModel? {
        return currentItems.find { it.name == name }
    }

    /** Пересобирает currentItems из кэша и локальных shell'ов. */
    fun refreshItems(onDone: (() -> Unit)? = null) {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch

            val cached = storeManager.getCachedItems() ?: emptyList()
            currentRemote = cached

            currentItems = StoreManager.buildItems(
                remote = cached,
                localShells = shellManager.getAvailableShells(),
                activeName = configManager.getActiveShell(),
                activeVersion = configManager.getActiveShellVersion()
            )

            if (isFinishing || isDestroyed) return@launch

            onDone?.invoke()
        }
    }

    /** При возврате обновляет ориентацию, каталог и immersive. */
    override fun onResume() {
        super.onResume()

        applyOrientation()

        refreshItems()

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}