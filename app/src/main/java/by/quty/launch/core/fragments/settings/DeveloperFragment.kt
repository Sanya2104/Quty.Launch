// *** core/fragments/settings/DeveloperFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Разработчикам" для новых Настроек
 * Содержит: DevMode, WebView-отладка, управление данными, логи, инструменты
 * 
 * TODO: Скопировать функционал из DeveloperFragment (parameters)
 * - WebView-отладка
 * - Информация об оболочке
 * - Системная информация
 * - Управление данными
 * - Управление логами
 * - Инструменты
 */
class DeveloperFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_developer, container, false)
    }

}