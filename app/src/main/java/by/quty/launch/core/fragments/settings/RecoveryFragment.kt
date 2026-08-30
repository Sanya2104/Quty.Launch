// *** core/fragments/settings/RecoveryFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Восстановление и сброс" для Настроек
 * экспорт/импорт настроек
 *
 * TODO: Реализовать функционал:
 * - Экспорт настроек в .qutyconfig
 * - Импорт настроек из .qutyconfig
 */
class RecoveryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_recovery, container, false)
    }

}