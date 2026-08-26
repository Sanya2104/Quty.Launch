// *** SettingsActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.settings.AboutFragment
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.GeneralFragment
import by.quty.launch.core.fragments.settings.ShellFragment
import by.quty.launch.core.fragments.settings.UpdateFragment
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.databinding.ActivitySettingsBinding

/**
 * Новая активность Настроек Quty.Launch с двухпанельным режимом
 * - На телефонах: список меню, клик → открывается фрагмент на всю ширину
 * - На планшетах: слева меню, справа содержимое
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedItemId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientation()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSlidingPane()
        setupMenu()
        setupCloseButton()
        setupBackPressedDispatcher()

        // Восстанавливаем состояние при повороте
        if (savedInstanceState != null) {
            val restoredId = savedInstanceState.getInt("selected_item_id", -1)
            if (restoredId != -1) {
                selectMenuItem(restoredId)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_item_id", selectedItemId)
    }

    /**
     * Настройка SlidingPaneLayout
     */
    private fun setupSlidingPane() {
        binding.slidingPaneLayout.apply {
            lockMode = SlidingPaneLayout.LOCK_MODE_UNLOCKED
        }
    }

    /**
     * Настройка меню
     */
    private fun setupMenu() {
        // Создаём список пунктов меню
        val menuItems = listOf(
            SettingsMenuModel(
                id = 1,
                icon = R.drawable.ic_settings,
                title = R.string.settings_menu_main,
                description = R.string.settings_menu_main_desc,
                fragment = GeneralFragment::class.java
            ),
            SettingsMenuModel(
                id = 2,
                icon = R.drawable.ic_palette,
                title = R.string.settings_menu_personalization,
                description = R.string.settings_menu_personalization_desc,
                fragment = ShellFragment::class.java
            ),
            SettingsMenuModel(
                id = 3,
                icon = R.drawable.ic_download,
                title = R.string.settings_menu_updates,
                description = R.string.settings_menu_updates_desc,
                fragment = UpdateFragment::class.java
            ),
            SettingsMenuModel(
                id = 4,
                icon = R.drawable.ic_developer,
                title = R.string.settings_menu_developer,
                description = R.string.settings_menu_developer_desc,
                fragment = DeveloperFragment::class.java
            ),
            SettingsMenuModel(
                id = 5,
                icon = R.drawable.ic_info,
                title = R.string.settings_menu_about,
                description = R.string.settings_menu_about_desc,
                fragment = AboutFragment::class.java
            )
        )

        // Настраиваем адаптер
        val adapter = SettingsMenuAdapter(
            items = menuItems,
            onItemClick = { item ->
                selectMenuItem(item.id)
            }
        )

        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter

        // Если двухпанельный режим — выбираем первый пункт
        if (binding.slidingPaneLayout.isSlideable) {
            if (selectedItemId == -1) {
                selectMenuItem(menuItems.first().id)
            }
        }
    }

    /**
     * Выбор пункта меню
     */
    private fun selectMenuItem(itemId: Int) {
        LoggerManager.d("SettingsActivity", "selectMenuItem: $itemId")

        selectedItemId = itemId

        val menuItems = (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.getItems() ?: return
        val selectedItem = menuItems.find { it.id == itemId } ?: return

        LoggerManager.d("SettingsActivity", "Selected: ${selectedItem.fragment.simpleName}")

        // Показываем контейнер для фрагмента, скрываем заглушку
        binding.fragmentContainer.visibility = View.VISIBLE
        binding.placeholder.visibility = View.GONE

        try {
            val fragment = supportFragmentManager.fragmentFactory.instantiate(
                selectedItem.fragment.classLoader!!,
                selectedItem.fragment.name
            )
            LoggerManager.d("SettingsActivity", "Fragment created: ${fragment.javaClass.simpleName}")

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        } catch (e: Exception) {
            LoggerManager.e("SettingsActivity", "Error creating fragment", e)
        }

        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(itemId)

        val isSlideable = binding.slidingPaneLayout.isSlideable
        val isOpen = binding.slidingPaneLayout.isOpen

        LoggerManager.d("SettingsActivity", "isSlideable: $isSlideable")
        LoggerManager.d("SettingsActivity", "isOpen: $isOpen")

        when {
            // Широкий экран (планшет) — открываем панель, чтобы показать контент
            isSlideable -> {
                LoggerManager.d("SettingsActivity", "Opening pane (tablet mode)")
                binding.slidingPaneLayout.openPane()
            }
            // Узкий экран (телефон) — закрываем панель после выбора
            else -> {
                LoggerManager.d("SettingsActivity", "Closing pane (phone mode)")
                binding.slidingPaneLayout.closePane()
            }
        }
    }

    /**
     * Настройка кнопки закрытия
     */
    private fun setupCloseButton() {
        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    /**
     * Настройка диспетчера для обработки нажатия системной кнопки "Назад"
     */
    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Если панель открыта и это телефон — закрываем панель
                if (!binding.slidingPaneLayout.isSlideable && binding.slidingPaneLayout.isOpen) {
                    binding.slidingPaneLayout.closePane()
                    return
                }
                // Иначе — закрываем настройки
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            supportFragmentManager.popBackStackImmediate(null, 0)
        } catch (_: Exception) {
            // Игнорируем
        }
    }
}