// *** StoreActivity.kt *** //
package by.quty.launch

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.store.StoreCatalogFragment
import by.quty.launch.core.fragments.store.StoreDetailFragment
import by.quty.launch.core.fragments.store.StoreLibraryFragment
import by.quty.launch.core.fragments.store.StoreSettingsFragment
import by.quty.launch.core.model.MenuModel
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.databinding.ActivityStoreBinding

/**
 * Активность магазина Quty.Launch.
 *
 * Вёрстка по паттерну LoggerActivity:
 * - на телефоне — меню и контент переключаются как экраны;
 * - на планшете — меню слева, контент справа.
 */
class StoreActivity : BaseActivity() {

    companion object {
        /**
         * id пункта меню «Каталог» — используется для определения,
         * какая иконка должна быть на кнопке назад.
         */
        private const val CATALOG_FRAGMENT_ID = 1
    }

    private lateinit var binding: ActivityStoreBinding

    private var isFragmentVisible = false
    private var isTabletMode = false
    private var selectedItemId = -1

    private var menuItems: List<MenuModel> = emptyList()

    private val menuWidthPx: Int by lazy {
        (340 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientation()

        binding = ActivityStoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // При первом открытии сразу показываем первый пункт — «Каталог».
        if (selectedItemId == -1 && menuItems.isNotEmpty()) {
            showItem(menuItems.first())
        }

        applyMode()

        // Иммерсивный режим
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt("selected_item_id", selectedItemId)
        outState.putBoolean("fragment_visible", isFragmentVisible)
    }

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

            /*
             * Кнопка «Назад» в шапке контента:
             * - если открыт детальный экран (StoreDetailFragment) — показываем
             *   всегда (и на телефоне, и на планшете), с иконкой стрелки;
             * - иначе на планшете — скрываем;
             * - на телефоне для корневого «Каталог» — три полоски (ic_menu),
             *   для остальных — стрелка (ic_back).
             */
            if (isDetailVisible()) {
                binding.btnBackContent.visibility = View.VISIBLE
                binding.btnBackContentIcon.setImageResource(R.drawable.ic_back)
            } else if (isTabletMode) {
                binding.btnBackContent.visibility = View.GONE
            } else {
                binding.btnBackContent.visibility = View.VISIBLE
                updateBackButtonIcon()
            }

            if (isTabletMode) {
                binding.recyclerMenu.visibility = View.VISIBLE
                (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(selectedItemId)
            } else {
                binding.recyclerMenu.visibility = View.GONE
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

    /**
     * Иконка «Назад» в шапке контента:
     * - для корневого «Каталог» — три полоски (ic_menu),
     * - для остальных — стрелка (ic_back).
     */
    private fun updateBackButtonIcon() {
        val iconRes = if (selectedItemId == CATALOG_FRAGMENT_ID) {
            R.drawable.ic_menu
        } else {
            R.drawable.ic_back
        }
        binding.btnBackContentIcon.setImageResource(iconRes)
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
        binding.btnClose.setOnClickListener { checkAndShowRestartDialog() }
        binding.btnCloseContent.setOnClickListener { checkAndShowRestartDialog() }
        binding.btnBackContent.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Если открыт детальный экран — закрываем его
                    if (isDetailVisible()) {
                        hideDetail()
                        return
                    }

                    // Если открыто меню на телефоне — возвращаемся к списку меню
                    if (!isTabletMode && isFragmentVisible) {
                        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
                        isFragmentVisible = false
                        applyMode()
                        return
                    }

                    // Иначе — проверяем изменения (диалог перезапуска) или закрываем
                    checkAndShowRestartDialog()
                }
            }
        )
    }

    /**
     * Пункты меню магазина.
     */
    private fun buildMenuItems(): List<MenuModel> {
        val items = mutableListOf<MenuModel>()

        items.add(
            MenuModel(
                CATALOG_FRAGMENT_ID,
                R.drawable.ic_store,
                R.string.store_menu_catalog,
                R.string.store_menu_catalog_desc,
                StoreCatalogFragment::class.java,
                R.color.scheme_green_primary,
                true
            )
        )

        items.add(
            MenuModel(
                2,
                R.drawable.ic_folder,
                R.string.store_menu_library,
                R.string.store_menu_library_desc,
                StoreLibraryFragment::class.java,
                R.color.scheme_orange_primary,
                true
            )
        )

        items.add(
            MenuModel(
                3,
                R.drawable.ic_settings,
                R.string.store_menu_settings,
                R.string.store_menu_settings_desc,
                StoreSettingsFragment::class.java,
                R.color.scheme_cyan_primary,
                true
            )
        )

        return items
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

    // ============================================================
    // ДЕТАЛЬНЫЙ ЭКРАН
    // ============================================================

    private fun isDetailVisible(): Boolean {
        return supportFragmentManager.findFragmentById(R.id.fragment_container) is StoreDetailFragment
    }

    fun showDetail(shell: ShellStoreModel) {
        val fragment = StoreDetailFragment.newInstance(shell)

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        binding.contentTitle.text = shell.displayName

        /*
         * Принудительно показываем кнопку «Назад» — на планшете она
         * скрыта в обычном каталоге, но в детальном экране нужна,
         * чтобы пользователь мог вернуться обратно.
         */
        binding.btnBackContent.visibility = View.VISIBLE
        binding.btnBackContentIcon.setImageResource(R.drawable.ic_back)
    }

    fun hideDetail() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            /*
             * popBackStackImmediate() — синхронный pop, чтобы
             * StoreDetailFragment был удалён ДО applyMode().
             */
            supportFragmentManager.popBackStackImmediate()
        }
        binding.contentTitle.text = getMenuItemTitle()

        /*
         * После возврата из детального экрана нужно перерисовать карточки
         * каталога — статусы могли измениться (установили / применили
         * оболочку). refresh() пересоздаёт адаптер с актуальными данными
         * без повторной загрузки с сервера.
         */
        (supportFragmentManager.findFragmentById(R.id.fragment_container)
                as? StoreCatalogFragment)?.refresh()

        applyMode()
    }

    override fun onResume() {
        super.onResume()

        applyOrientation()

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}