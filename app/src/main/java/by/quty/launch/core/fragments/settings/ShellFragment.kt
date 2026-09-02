// *** core/fragments/settings/ShellFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Персонализация" для Настроек
 * Содержит: список оболочек, цветовые схемы, кнопка "Магазин"
 */
class ShellFragment : Fragment() {

    // Флаг, что требуется перезагрузка
    private var needsRestart = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_shell, container, false)
    }

    /**
     * Возвращает флаг необходимости перезагрузки
     */
    fun getNeedsRestart(): Boolean = needsRestart

    /**
     * Устанавливает флаг необходимости перезагрузки
     */
    fun setNeedsRestart(value: Boolean) {
        needsRestart = value
    }
}