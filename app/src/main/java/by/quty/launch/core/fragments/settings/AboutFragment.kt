// *** core/fragments/settings/AboutFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "О системе" для новых Настроек
 * Содержит информацию о приложении и устройстве
 * 
 * TODO: Создать с нуля
 * - Иконка приложения
 * - Версия, код версии, канал сборки
 * - Модель устройства, Android версия, SDK уровень
 * - Информация о текущей оболочке
 */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_about, container, false)
    }

}