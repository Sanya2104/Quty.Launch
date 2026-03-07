// *** core/fragments/SystemSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import by.quty.launch.R

class SystemSettingsFragment : Fragment() {

    private lateinit var versionTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_system, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        versionTextView = view.findViewById(R.id.version_text)
        setupVersionInfo()
    }

    /**
     * Отображение версии приложения
     * Получает versionName из PackageManager и форматирует с пробелом
     */
    private fun setupVersionInfo() {
        val fullVersionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.version_unknown)
        }

        // Добавляем пробел между цифрами и суффиксом (например, "0.0.3 alpha")
        val versionText = fullVersionName?.replace(Regex("([0-9.]+)([a-zA-Z].*)"), "$1 $2")
            ?: getString(R.string.version_unknown)

        versionTextView.text = getString(R.string.version_format, versionText)
    }

    /**
     * Обновление информации (вызывается из Activity при необходимости)
     */
    fun refreshInfo() {
        setupVersionInfo()
    }
}