// *** SettingsActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import by.quty.launch.core.adapters.SettingsMenuAdapter
import by.quty.launch.core.fragments.settings.AboutFragment
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.GeneralFragment
import by.quty.launch.core.fragments.settings.ShellFragment
import by.quty.launch.core.fragments.settings.UpdateFragment
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isFragmentVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation()

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMenu()
        setupButtons()
        setupBackHandler()

        if (savedInstanceState != null) {
            val showing = savedInstanceState.getBoolean("fragment_visible", false)
            if (showing) {
                // Восстанавливаем — показываем меню, фрагмент сам восстановится
                showMenu()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("fragment_visible", isFragmentVisible)
    }

    private fun getMenuItems(): List<SettingsMenuModel> {
        return listOf(
            SettingsMenuModel(1, R.drawable.ic_settings, R.string.settings_menu_main, R.string.settings_menu_main_desc, GeneralFragment::class.java),
            SettingsMenuModel(2, R.drawable.ic_palette, R.string.settings_menu_personalization, R.string.settings_menu_personalization_desc, ShellFragment::class.java),
            SettingsMenuModel(3, R.drawable.ic_download, R.string.settings_menu_updates, R.string.settings_menu_updates_desc, UpdateFragment::class.java),
            SettingsMenuModel(4, R.drawable.ic_developer, R.string.settings_menu_developer, R.string.settings_menu_developer_desc, DeveloperFragment::class.java),
            SettingsMenuModel(5, R.drawable.ic_info, R.string.settings_menu_about, R.string.settings_menu_about_desc, AboutFragment::class.java)
        )
    }

    private fun setupMenu() {
        val adapter = SettingsMenuAdapter(getMenuItems()) { item ->
            showItem(item)
        }
        binding.recyclerMenu.layoutManager = LinearLayoutManager(this)
        binding.recyclerMenu.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFragmentVisible) {
                    showMenu()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun showMenu() {
        isFragmentVisible = false
        binding.recyclerMenu.visibility = View.VISIBLE
        binding.fragmentContainer.visibility = View.GONE
        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(-1)
    }

    private fun showItem(item: SettingsMenuModel) {
        isFragmentVisible = true
        binding.recyclerMenu.visibility = View.GONE
        binding.fragmentContainer.visibility = View.VISIBLE

        try {
            val fragment = supportFragmentManager.fragmentFactory.instantiate(
                item.fragment.classLoader!!,
                item.fragment.name
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        } catch (e: Exception) {
            LoggerManager.e("SettingsActivity", "Error", e)
        }

        (binding.recyclerMenu.adapter as? SettingsMenuAdapter)?.setSelectedItem(item.id)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}