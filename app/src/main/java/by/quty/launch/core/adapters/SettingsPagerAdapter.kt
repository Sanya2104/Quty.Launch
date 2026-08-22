// *** core/adapters/SettingsPagerAdapter.kt *** //
package by.quty.launch.core.adapters

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.quty.launch.core.fragments.settings.DeveloperFragment
import by.quty.launch.core.fragments.settings.DisplayFragment
import by.quty.launch.core.fragments.settings.SystemFragment
import by.quty.launch.core.fragments.settings.ShellFragment

class SettingsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        private const val NUM_TABS_BASE = 3
        private const val NUM_TABS_DEV = 4

        const val TAB_SHELL = 0
        const val TAB_DISPLAY = 1
        const val TAB_SYSTEM = 2
        const val TAB_DEVELOPER = 3
    }

    private val isDeveloperMode: Boolean by lazy {
        val prefs = activity.getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        prefs.getBoolean("developer_mode", false)
    }

    override fun getItemCount(): Int {
        return if (isDeveloperMode) NUM_TABS_DEV else NUM_TABS_BASE
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