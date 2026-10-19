// *** core/fragments/store/StoreCatalogFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.StoreCatalogAdapter
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.ShellStoreModel
import kotlinx.coroutines.launch

/**
 * Фрагмент «Каталог» магазина.
 *
 * Загружает список оболочек с сервера, отображает карточки.
 * Позволяет устанавливать, обновлять и применять оболочки.
 *
 * В будущем здесь могут быть не только оболочки — темы, шрифты и т.п.
 * Пока — только оболочки.
 */
class StoreCatalogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var btnRetry: Button
    private lateinit var emptyState: LinearLayout

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
            storeManager = StoreManager(requireContext())
            shellManager = ShellManager(requireContext(), storeActivity.configManager)
        } ?: run {
            // Fallback, если фрагмент открыт не в StoreActivity
            storeManager = StoreManager(requireContext())
            val configManager = by.quty.launch.core.managers.ConfigManager(requireContext())
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
     */
    private fun getActiveShellInfo(): Pair<String?, String?> {
        val active = shellManager.getActiveShell()
        return Pair(active?.name, active?.version)
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
            },
            onInstallClick = { shell ->
                installShell(shell)
            },
            onUpdateClick = { shell ->
                installShell(shell)
            },
            onApplyClick = { shell ->
                applyShell(shell)
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
     * Устанавливает или обновляет оболочку: скачивает и сохраняет.
     *
     * Для «Установить» и «Обновить» используется один и тот же метод —
     * файл `.qutyshell` перезаписывается (overwrite = true).
     */
    private fun installShell(shell: ShellStoreModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.updateProgress(shell.id, 0)

            val success = storeManager.installShell(
                shell = shell,
                listener = object : StoreManager.DownloadListener {
                    override fun onProgress(percent: Int) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            adapter.updateProgress(shell.id, percent)
                        }
                    }

                    override fun onSuccess() {
                        viewLifecycleOwner.lifecycleScope.launch {
                            adapter.clearProgress(shell.id)
                            // Перезагружаем список, чтобы обновить состояние кнопки
                            loadShells()

                            Toast.makeText(
                                requireContext(),
                                getString(R.string.store_install_success, shell.displayName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onError(message: String) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            adapter.clearProgress(shell.id)
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            if (!success) {
                adapter.clearProgress(shell.id)
            }
        }
    }

    /**
     * Применяет оболочку: делает её активной.
     */
    private fun applyShell(shell: ShellStoreModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val availableShell = shellManager.getAvailableShells()
                .find { it.name == shell.name }

            if (availableShell == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.store_install_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            shellManager.setActiveShell(availableShell)

            // Требуется перезапуск — сообщаем об этом через активность
            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            // Обновляем список, чтобы кнопка стала "Применено"
            loadShells()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, shell.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
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