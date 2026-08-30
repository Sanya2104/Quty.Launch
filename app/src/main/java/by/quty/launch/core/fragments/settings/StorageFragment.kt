// *** core/fragments/settings/StorageFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент "Память" для Настроек
 * Содержит информацию о занятом месте, очистку данных,
 *
 * TODO: Реализовать функционал:
 * - Отображение занятого места (приложение, кэш, оболочки, логи)
 * - Отображение свободного места
 * - Очистка кэша
 * - Очистка логов
 */
class StorageFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_storage, container, false)
    }

}