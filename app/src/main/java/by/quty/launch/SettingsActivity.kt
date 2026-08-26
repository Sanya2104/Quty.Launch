// *** SettingsActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.settings.AboutFragment
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.GeneralFragment
import by.quty.launch.core.fragments.settings.ShellFragment
import by.quty.launch.core.fragments.settings.UpdateFragment
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.databinding.ActivitySettingsBinding

/**
 * Новая активность Настроек Quty.Launch с меню-списком
 * Создана параллельно с ParametersActivity
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isRootScreen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientation()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBackPressedDispatcher()
        showMenu()
    }

    /**
     * Настройка тулбара
     */
    private fun setupToolbar() {
        // Кнопка "Назад" — возвращает в меню (через диспетчер)
        binding.btnBack.setOnClickListener {
            // Эмулируем нажатие системной кнопки "Назад"
            onBackPressedDispatcher.onBackPressed()
        }

        // Кнопка "Закрыть" — закрывает настройки
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
                if (!isRootScreen) {
                    // Если не на корневом экране — возвращаемся в меню
                    supportFragmentManager.popBackStack()
                    isRootScreen = true

                    // Скрываем кнопку "Назад", показываем заголовок "Настройки"
                    binding.btnBack.visibility = View.GONE
                    binding.tvTitle.text = getString(R.string.settings_title)

                    // Показываем RecyclerView
                    binding.recyclerMenu.visibility = View.VISIBLE
                } else {
                    // Если на корневом экране — закрываем настройки
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    /**
     * Показывает главное меню
     */
    private fun showMenu() {
        isRootScreen = true

        // Скрываем кнопку "Назад", показываем заголовок "Настройки"
        binding.btnBack.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.settings_title)

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

        // Настраиваем RecyclerView
        val adapter = SettingsMenuAdapter(menuItems) { item ->
            openFragment(item.fragment, item.title)
        }

        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter
        binding.recyclerMenu.visibility = View.VISIBLE
    }

    /**
     * Открывает фрагмент
     * @param fragmentClass класс фрагмента
     * @param titleRes ресурс заголовка
     */
    private fun openFragment(fragmentClass: Class<out Fragment>, @StringRes titleRes: Int) {
        isRootScreen = false

        // Показываем кнопку "Назад", меняем заголовок
        binding.btnBack.visibility = View.VISIBLE
        binding.tvTitle.text = getString(titleRes)

        // Скрываем RecyclerView
        binding.recyclerMenu.visibility = View.GONE

        // Создаём фрагмент через FragmentFactory (без deprecated newInstance())
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            fragmentClass.classLoader!!,
            fragmentClass.name
        )

        // Добавляем фрагмент
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем backStack при уничтожении
        try {
            supportFragmentManager.popBackStackImmediate(null, 0)
        } catch (_: Exception) {
            // Игнорируем
        }
    }

    /**
     * Включаем иммерсивный режим
     */
    override fun onResume() {
        super.onResume()
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}