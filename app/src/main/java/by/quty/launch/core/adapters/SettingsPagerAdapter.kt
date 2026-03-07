// *** core/adapters/SettingsPagerAdapter.kt *** //
package by.quty.launch.core.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.quty.launch.core.fragments.ThemeSettingsFragment
import by.quty.launch.core.fragments.DisplaySettingsFragment
import by.quty.launch.core.fragments.SystemSettingsFragment

class SettingsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        private const val NUM_TABS = 3
        const val TAB_THEME = 0
        const val TAB_DISPLAY = 1
        const val TAB_SYSTEM = 2
    }

    override fun getItemCount(): Int = NUM_TABS

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_THEME -> ThemeSettingsFragment()
            TAB_DISPLAY -> DisplaySettingsFragment()
            TAB_SYSTEM -> SystemSettingsFragment()
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}