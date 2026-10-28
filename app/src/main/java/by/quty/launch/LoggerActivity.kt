// *** LoggerActivity.kt *** //
package by.quty.launch

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.logger.LoggerFilesFragment
import by.quty.launch.core.fragments.logger.LoggerFragment
import by.quty.launch.core.fragments.logger.LoggerSettingsFragment
import by.quty.launch.core.processes.AppScope
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.MenuModel
import by.quty.launch.databinding.ActivityLoggerBinding
import kotlinx.coroutines.launch

/**
 * Логгер: Логи / Файлы / Настройки. На телефоне — переключение, на планшете — две панели.
 */
class LoggerActivity : BaseActivity() {

    companion object {
        private const val LOGGER_FRAGMENT_ID = 1
    }

    private lateinit var binding: ActivityLoggerBinding

    private var isFragmentVisible = false
    private var isTabletMode = false
    private var selectedItemId = -1

    private var menuItems: List<MenuModel> = emptyList()

    private val menuWidthPx: Int by lazy {
        (340 * resources.displayMetrics.density).toInt()
    }

    /** Инициализирует экран, меню, восстанавливает состояние, применяет режим. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientation()

        binding = ActivityLoggerBinding.inflate(layoutInflater)
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

        if (selectedItemId == -1 && menuItems.isNotEmpty()) {
            showItem(menuItems.first())
        }

        applyMode()
    }

    /** Сохраняет выбранный пункт и видимость фрагмента. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt("selected_item_id", selectedItemId)
        outState.putBoolean("fragment_visible", isFragmentVisible)
    }

    /** Перестраивает режим при повороте/смене размера. */
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
            binding.contentTitle.text = getMenuItemTitle()

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

    /** Логи — ic_menu; остальные — ic_back. */
    private fun updateBackButtonIcon() {
        val iconRes = if (selectedItemId == LOGGER_FRAGMENT_ID) {
            R.drawable.ic_menu
        } else {
            R.drawable.ic_back
        }
        binding.btnBackContentIcon.setImageResource(iconRes)
    }

    /** Возвращает строковый заголовок выбранного пункта меню. */
    private fun getMenuItemTitle(): String {
        val item = menuItems.find { it.id == selectedItemId }
        return if (item != null) getString(item.title) else ""
    }

    /** Настраивает RecyclerView меню. */
    private fun setupMenu() {
        val adapter = SettingsMenuAdapter(menuItems) { item ->
            showItem(item)
        }

        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter
    }

    /** Вешает клики на кнопки закрытия/назад. */
    private fun setupButtons() {
        binding.btnClose.setOnClickListener { finish() }
        binding.btnCloseContent.setOnClickListener { finish() }
        binding.btnBackContent.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Настраивает обработчик системной кнопки «Назад». */
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
                    finish()
                }
            }
        )
    }

    /** Строит список пунктов меню логгера. */
    private fun buildMenuItems(): List<MenuModel> {
        val items = mutableListOf<MenuModel>()

        items.add(
            MenuModel(
                LOGGER_FRAGMENT_ID,
                R.drawable.ic_logger_list,
                R.string.logger_menu_logs,
                R.string.logger_menu_logs_desc,
                LoggerFragment::class.java,
                R.color.scheme_green_primary,
                true
            )
        )

        items.add(
            MenuModel(
                2,
                R.drawable.ic_folder,
                R.string.logger_menu_files,
                R.string.logger_menu_files_desc,
                LoggerFilesFragment::class.java,
                R.color.scheme_orange_primary,
                true
            )
        )

        items.add(
            MenuModel(
                3,
                R.drawable.ic_settings,
                R.string.logger_menu_settings,
                R.string.logger_menu_settings_desc,
                LoggerSettingsFragment::class.java,
                R.color.scheme_cyan_primary,
                true
            )
        )

        return items
    }

    /** Показывает фрагмент выбранного пункта. */
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

    /** При выходе: если включено автосохранение — пишем буфер в файл через AppScope. */
    override fun onDestroy() {
        if (LoggerManager.isAutoSaveEnabled() && LoggerManager.size() > 0) {
            val appContext = applicationContext
            AppScope.scope.launch {
                LoggerManager.writeToFile(appContext)
            }
        }

        super.onDestroy()
    }

    /** При возврате в активность заново применяет ориентацию и immersive. */
    override fun onResume() {
        super.onResume()

        applyOrientation()

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}