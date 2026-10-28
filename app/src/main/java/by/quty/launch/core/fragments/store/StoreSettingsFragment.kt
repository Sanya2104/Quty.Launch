// *** core/fragments/store/StoreSettingsFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.StoreManager
import kotlinx.coroutines.launch

/**
 * Настройки магазина: источник каталога, очистка кэша.
 */
class StoreSettingsFragment : Fragment() {

    private lateinit var storeManager: StoreManager

    private lateinit var clearCacheRow: View
    private lateinit var sourceUrlValue: TextView

    /** Создаёт view фрагмента. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_settings, container, false)
    }

    /** Находит view, показывает URL источника и вешает очистку кэша. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        storeManager = StoreManager(requireContext())

        clearCacheRow = view.findViewById(R.id.store_clear_cache_row)
        sourceUrlValue = view.findViewById(R.id.store_source_url_value)

        sourceUrlValue.text = CoreConfig.STORE_URL

        clearCacheRow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val success = storeManager.clearCache()
                val message = if (success) {
                    R.string.store_cache_cleared
                } else {
                    R.string.store_cache_clear_error
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}