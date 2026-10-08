// *** core/adapters/ParametersPagerAdapter.kt *** //
package by.quty.launch.core.adapters

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.quty.launch.core.fragments.parameters.DeveloperFragment
import by.quty.launch.core.fragments.parameters.DisplayFragment
import by.quty.launch.core.fragments.parameters.SystemFragment
import by.quty.launch.core.fragments.parameters.ShellFragment

class ParametersPagerAdapter(
    private val hostActivity: FragmentActivity
) : FragmentStateAdapter(hostActivity) {

    companion object {
        private const val NUM_TABS_BASE = 3
        private const val NUM_TABS_DEV = 4

        const val TAB_SHELL = 0
        const val TAB_DISPLAY = 1
        const val TAB_SYSTEM = 2
        const val TAB_DEVELOPER = 3
    }

    /**
     * Проверяет текущее состояние DevMode.
     *
     * ВАЖНО: НЕ используем by lazy — значение перечитывается при каждом
     * вызове, потому что DevMode может быть переключён в момент, когда
     * адаптер уже создан (например, через SystemFragment или SettingsActivity).
     */
    private fun isDeveloperMode(): Boolean {
        val prefs = hostActivity.getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("developer_mode", false)
    }

    override fun getItemCount(): Int {
        return if (isDeveloperMode()) NUM_TABS_DEV else NUM_TABS_BASE
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_SHELL -> ShellFragment()
            TAB_DISPLAY -> DisplayFragment()
            TAB_SYSTEM -> SystemFragment()
            TAB_DEVELOPER -> DeveloperFragment()
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}