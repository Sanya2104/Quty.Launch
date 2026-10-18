// *** core/fragments/store/StoreInstalledFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import by.quty.launch.R

/**
 * Фрагмент «Мои установленные» магазина.
 *
 * Пока — заглушка. В будущем здесь будет список установленных
 * оболочек с возможностью удаления/обновления.
 */
class StoreInstalledFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = view.findViewById<TextView>(R.id.placeholder_title)
        val message = view.findViewById<TextView>(R.id.placeholder_message)

        title.text = getString(R.string.store_menu_installed)
        message.text = getString(R.string.store_placeholder_installed)
    }
}