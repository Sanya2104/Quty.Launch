// *** core/fragments/store/StoreCatalogFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.StoreCatalogAdapter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.ShellStoreModel
import kotlinx.coroutines.launch

/**
 * Фрагмент «Каталог» магазина.
 *
 * Загружает список оболочек с сервера, отображает карточки.
 *
 * Действия (установка/обновление/применение) в карточках отсутствуют —
 * пользователь открывает карточку и выполняет действия в детальном
 * экране (StoreDetailFragment) или в Библиотеке.
 *
 * При возврате из детального экрана StoreActivity вызывает refresh(),
 * чтобы обновить статусы (Активна / Есть обновление / Установлено).
 */
class StoreCatalogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var btnRetry: Button
    private lateinit var emptyState: LinearLayout

    private lateinit var configManager: ConfigManager
    private lateinit var storeManager: StoreManager
    private lateinit var shellManager: ShellManager
    private lateinit var adapter: StoreCatalogAdapter

    private var shells: List<ShellStoreModel> = emptyList()
    private var isLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_catalog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? StoreActivity)?.let { storeActivity ->
            configManager = storeActivity.configManager
            storeManager = StoreManager(requireContext())
            shellManager = ShellManager(requireContext(), configManager)
        } ?: run {
            // Fallback, если фрагмент открыт не в StoreActivity
            configManager = ConfigManager(requireContext())
            storeManager = StoreManager(requireContext())
            shellManager = ShellManager(requireContext(), configManager)
        }

        recyclerView = view.findViewById(R.id.recycler_shells)
        loadingState = view.findViewById(R.id.loading_state)
        errorState = view.findViewById(R.id.error_state)
        errorMessage = view.findViewById(R.id.error_message)
        btnRetry = view.findViewById(R.id.btn_retry)
        emptyState = view.findViewById(R.id.empty_state)

        setupRecyclerView()
        setupButtons()
        loadShells()
    }

    private fun setupRecyclerView() {
        adapter = buildAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnRetry.setOnClickListener {
            loadShells()
        }
    }

    /**
     * Возвращает карту name → version установленных оболочек.
     */
    private fun getInstalledShellVersions(): Map<String, String> {
        return shellManager.getAvailableShells()
            .mapNotNull { shell -> shell.version?.let { shell.name to it } }
            .toMap()
    }

    /**
     * Возвращает пару (name, version) активной оболочки.
     *
     * ВАЖНО: version берётся из ConfigManager.getActiveShellVersion(),
     * а не из ShellManager.getActiveShell().version. Первое — это
     * «версия, реально загруженная в WebView», второе — версия файла
     * на диске (может отличаться, если оболочка была обновлена, но
     * новая версия ещё не применена).
     */
    private fun getActiveShellInfo(): Pair<String?, String?> {
        val activeName = configManager.getActiveShell()
        val activeVersion = configManager.getActiveShellVersion()
        return Pair(activeName, activeVersion)
    }

    /**
     * Создаёт новый адаптер с актуальными данными.
     */
    private fun buildAdapter(shells: List<ShellStoreModel>): StoreCatalogAdapter {
        val (activeName, activeVersion) = getActiveShellInfo()

        return StoreCatalogAdapter(
            shells = shells,
            installedShellVersions = getInstalledShellVersions(),
            activeShellName = activeName,
            activeShellVersion = activeVersion,
            onItemClick = { shell ->
                (activity as? StoreActivity)?.showDetail(shell)
            }
        )
    }

    /**
     * Загружает список оболочек с сервера.
     */
    private fun loadShells() {
        if (isLoading) return
        isLoading = true

        showLoading()
        hideError()
        hideEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = storeManager.fetchShells()

            isLoading = false
            hideLoading()

            if (result != null) {
                shells = result
                if (shells.isEmpty()) {
                    showEmpty()
                } else {
                    // Пересоздаём адаптер с актуальными данными
                    adapter = buildAdapter(shells)
                    recyclerView.adapter = adapter
                    recyclerView.visibility = View.VISIBLE
                }
            } else {
                showError(getString(R.string.store_load_error))
            }
        }
    }

    /**
     * Публичный метод для перерисовки карточек при возврате из детального
     * экрана. Не перезапрашивает список с сервера — только пересоздаёт
     * адаптер с актуальными installed/active данными.
     *
     * Вызывается из StoreActivity.hideDetail().
     */
    fun refresh() {
        if (!isAdded) return
        if (shells.isEmpty()) return

        adapter = buildAdapter(shells)
        recyclerView.adapter = adapter
    }

    // ============================================================
    // УПРАВЛЕНИЕ СОСТОЯНИЯМИ
    // ============================================================

    private fun showLoading() {
        loadingState.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingState.visibility = View.GONE
    }

    private fun showError(message: String) {
        errorMessage.text = message
        errorState.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorState.visibility = View.GONE
    }

    private fun showEmpty() {
        emptyState.visibility = View.VISIBLE
    }

    private fun hideEmpty() {
        emptyState.visibility = View.GONE
    }
}