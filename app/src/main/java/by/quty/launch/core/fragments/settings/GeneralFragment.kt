// *** core/fragments/settings/GeneralFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Основное" для новых Настроек
 * Содержит быстрые настройки: полноэкранный режим, строгий режим, ориентация
 * 
 * TODO: Перенести функционал из DisplayFragment
 */
class GeneralFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_general, container, false)
    }

}