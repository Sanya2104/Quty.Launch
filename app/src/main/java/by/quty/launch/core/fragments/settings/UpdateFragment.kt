// *** core/fragments/settings/UpdateFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Центр обновления" для новых Настроек
 * Содержит: проверка обновлений, локальная установка APK
 * 
 * TODO: Перенести функционал из SystemFragment
 * - Проверка обновлений (SystemUpdateManager)
 * - Локальная установка APK
 */
class UpdateFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_updates, container, false)
    }

}