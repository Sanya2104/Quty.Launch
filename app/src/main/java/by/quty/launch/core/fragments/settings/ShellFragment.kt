// *** core/fragments/settings/ShellFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Персонализация" для новых Настроек
 * Содержит: список оболочек, цветовые схемы, кнопка "Магазин"
 * 
 * TODO: Перенести функционал из ShellFragment (parameters)
 * - Убрать ориентацию (она в GeneralFragment)
 * - Добавить кнопку "Магазин" → StoreActivity
 */
class ShellFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_shell, container, false)
    }

}