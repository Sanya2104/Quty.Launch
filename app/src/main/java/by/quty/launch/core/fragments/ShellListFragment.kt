// *** core/fragments/ShellListFragment.kt *** //
package by.quty.launch.core.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.ShellStoreAdapter
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.ShellStoreItem
import by.quty.launch.core.logger.Logger
import kotlinx.coroutines.launch

/**
 * Фрагмент со списком оболочек для магазина
 */
class ShellListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: ShellStoreAdapter

    private var storeManager: StoreManager? = null
    private var showOnlyInstalled: Boolean = false
    private var shells: List<ShellStoreItem> = emptyList()
    private var isViewCreated = false

    companion object {
        fun newInstance(showOnlyInstalled: Boolean): ShellListFragment {
            return ShellListFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("show_only_installed", showOnlyInstalled)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOnlyInstalled = arguments?.getBoolean("show_only_installed") ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_shell_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_shells)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyText = view.findViewById(R.id.empty_text)

        isViewCreated = true

        setupRecyclerView()
        updateData()
    }

    private fun setupRecyclerView() {
        adapter = ShellStoreAdapter(
            onItemClick = { shell ->
                (activity as? StoreActivity)?.showDetail(shell.id)
            },
            onInstallClick = { shell ->
                installShell(shell)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    fun setStoreManager(manager: StoreManager) {
        storeManager = manager
        if (isViewCreated) {
            updateData()
        }
    }

    fun notifyDataChanged() {
        if (isViewCreated) {
            updateData()
        }
    }

    private fun updateData() {
        val manager = storeManager ?: return
        if (!isViewCreated || !::recyclerView.isInitialized) return

        Logger.d("ShellListFragment", getString(R.string.log_shell_list_update_data, manager.isDataLoaded()))

        // Если данные уже загружены — используем кэш
        if (manager.isDataLoaded()) {
            val allShells = manager.getCachedShells() ?: emptyList()
            progressBar.visibility = View.GONE
            updateUI(allShells)
            return
        }

        // Если нет — загружаем
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val allShells = manager.fetchShells() ?: emptyList()
            progressBar.visibility = View.GONE
            updateUI(allShells)
        }
    }

    @Suppress("NotifyDataSetChanged")
    private fun updateUI(allShells: List<ShellStoreItem>) {
        Logger.d("ShellListFragment", getString(R.string.log_shell_list_update_ui, allShells.size))

        shells = if (showOnlyInstalled) {
            allShells.filter { it.isInstalled }
        } else {
            allShells
        }

        Logger.d("ShellListFragment", getString(R.string.log_shell_list_shells_size, shells.size))

        if (shells.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (showOnlyInstalled) {
                getString(R.string.store_empty_installed)
            } else {
                getString(R.string.store_empty_shells)
            }
            recyclerView.visibility = View.GONE
            Logger.d("ShellListFragment", getString(R.string.log_shell_list_empty))
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(shells.toList())
            adapter.notifyDataSetChanged()
            Logger.d("ShellListFragment", getString(R.string.log_shell_list_adapter_count, adapter.itemCount))
        }
    }

    private fun installShell(shell: ShellStoreItem) {
        val manager = storeManager ?: return

        val progressDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.store_installing))
            .setMessage(getString(R.string.store_preparing))
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            manager.installShell(shell, object : StoreManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.store_progress, percent))
                }

                override fun onSuccess() {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.store_install_success, shell.displayName),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    updateData()
                    // Обновляем также другой фрагмент
                    (activity as? StoreActivity)?.let { activity ->
                        activity.supportFragmentManager.fragments.forEach { fragment ->
                            if (fragment is ShellListFragment && fragment != this@ShellListFragment) {
                                fragment.notifyDataChanged()
                            }
                        }
                    }
                }

                override fun onError(message: String) {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        requireContext(),
                        message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }
}