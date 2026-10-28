// *** core/fragments/store/StoreCatalogFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.StoreItemAdapter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.model.StoreFilterModel
import by.quty.launch.core.model.StoreItemModel
import by.quty.launch.core.model.StoreRemoteItemModel
import kotlinx.coroutines.launch
import androidx.core.view.isNotEmpty

/**
 * Каталог: список элементов с фильтрами, кэш + фоновая загрузка, swipe-to-refresh.
 */
class StoreCatalogFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storeManager: StoreManager

    private lateinit var filtersContainer: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var btnRetry: Button
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyMessage: TextView

    private lateinit var adapter: StoreItemAdapter

    private var allItems: List<StoreItemModel> = emptyList()

    private var currentFilter: StoreFilterModel = StoreFilterModel.ALL

    private var isLoading = false
    private var isCacheShown = false

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
        } ?: run {
            configManager = ConfigManager(requireContext())
        }
        shellManager = ShellManager(requireContext(), configManager)
        storeManager = StoreManager(requireContext())

        filtersContainer = view.findViewById(R.id.store_filters_container)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        recyclerView = view.findViewById(R.id.recycler_items)
        loadingState = view.findViewById(R.id.loading_state)
        errorState = view.findViewById(R.id.error_state)
        errorMessage = view.findViewById(R.id.error_message)
        btnRetry = view.findViewById(R.id.btn_retry)
        emptyState = view.findViewById(R.id.empty_state)
        emptyMessage = view.findViewById(R.id.empty_message)

        setupSwipeRefresh()
        setupRecyclerView()
        setupButtons()
        buildFilterChips()
        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        if (isCacheShown) {
            rebuildFromLocal()
        }
    }

    // ============================================================
    // НАСТРОЙКА UI
    // ============================================================

    private fun setupSwipeRefresh() {
        val accentColor = getThemeColor(R.attr.buttonPrimaryColor)
        swipeRefresh.setColorSchemeColors(accentColor)

        swipeRefresh.setOnRefreshListener {
            if (isLoading) {
                swipeRefresh.isRefreshing = false
            } else {
                loadFromServer(isUserRefresh = true)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = StoreItemAdapter(emptyList()) { item ->
            (activity as? StoreActivity)?.showItemDetail(item)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnRetry.setOnClickListener {
            loadInitial()
        }
    }

    /** Чипы фильтров в HorizontalScrollView. */
    private fun buildFilterChips() {
        filtersContainer.removeAllViews()

        StoreFilterModel.entries.forEach { filter ->
            val chip = TextView(requireContext()).apply {
                text = getString(filter.labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                val padH = resources.getDimensionPixelSize(R.dimen.spacing_l)
                val padV = resources.getDimensionPixelSize(R.dimen.spacing_s)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (currentFilter == filter) return@setOnClickListener
                    currentFilter = filter
                    updateChipStyles()
                    applyFilter()
                }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (filtersContainer.isNotEmpty()) {
                lp.marginStart = resources.getDimensionPixelSize(R.dimen.spacing_s)
            }
            chip.layoutParams = lp
            filtersContainer.addView(chip)
        }

        updateChipStyles()
    }

    private fun updateChipStyles() {
        for (i in 0 until filtersContainer.childCount) {
            val chip = filtersContainer.getChildAt(i) as? TextView ?: continue
            val filter = StoreFilterModel.entries.getOrNull(i) ?: continue

            if (filter == currentFilter) {
                chip.setBackgroundResource(R.drawable.bg_store_filter_chip_selected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                chip.setBackgroundResource(R.drawable.bg_store_filter_chip)
                chip.setTextColor(getThemeColor(R.attr.textPrimaryColor))
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА: КЭШ + СЕРВЕР
    // ============================================================

    private fun loadInitial() {
        if (isLoading) return

        viewLifecycleOwner.lifecycleScope.launch {
            isLoading = true

            val cached = storeManager.getCachedItems()

            if (!cached.isNullOrEmpty()) {
                rebuildFromData(cached)
                isCacheShown = true
                isLoading = false
                loadFromServer(isUserRefresh = false, silentIfCache = true)
            } else {
                isLoading = false
                loadFromServer(isUserRefresh = false, silentIfCache = false)
            }
        }
    }

    private fun rebuildFromLocal() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = storeManager.getCachedItems() ?: emptyList()
            rebuildFromData(cached)
        }
    }

    private fun rebuildFromData(remote: List<StoreRemoteItemModel>) {
        val localShells = shellManager.getAvailableShells()
        val activeName = configManager.getActiveShell()
        val activeVersion = configManager.getActiveShellVersion()

        allItems = StoreManager.buildItems(
            remote = remote,
            localShells = localShells,
            activeName = activeName,
            activeVersion = activeVersion
        )

        applyFilter()
    }

    /**
     * Загрузка с сервера.
     * @param isUserRefresh true — swipe-to-refresh.
     * @param silentIfCache true — не показывать loading_state.
     */
    private fun loadFromServer(isUserRefresh: Boolean, silentIfCache: Boolean = false) {
        if (isLoading) {
            if (isUserRefresh) swipeRefresh.isRefreshing = false
            return
        }
        isLoading = true

        if (!silentIfCache && !isUserRefresh) {
            showLoading()
        }
        hideError()
        hideEmpty()
        swipeRefresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = storeManager.fetchRemoteItems()

            isLoading = false

            if (!isUserRefresh && !silentIfCache) {
                hideLoading()
            }
            if (isUserRefresh) {
                swipeRefresh.isRefreshing = false
            }

            if (result != null) {
                rebuildFromData(result)
                isCacheShown = true
                swipeRefresh.isEnabled = true
            } else {
                if (allItems.isNotEmpty()) {
                    swipeRefresh.isEnabled = true
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            R.string.store_cache_stale_toast,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    showError(getString(R.string.store_load_error))
                    swipeRefresh.isEnabled = false
                }
            }
        }
    }

    // ============================================================
    // ФИЛЬТРАЦИЯ
    // ============================================================

    private fun applyFilter() {
        val filtered = allItems.filter(currentFilter.predicate)
        render(filtered)
    }

    private fun render(items: List<StoreItemModel>) {
        if (items.isEmpty()) {
            showEmpty()
        } else {
            hideEmpty()
            adapter.submitItems(items)
            recyclerView.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // СОСТОЯНИЯ
    // ============================================================

    private fun showLoading() {
        loadingState.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingState.visibility = View.GONE
    }

    private fun showError(message: String) {
        errorMessage.text = message
        errorState.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun hideError() {
        errorState.visibility = View.GONE
    }

    private fun showEmpty() {
        emptyMessage.text = getString(currentFilter.emptyRes)
        emptyState.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun hideEmpty() {
        emptyState.visibility = View.GONE
    }

    // ============================================================
    // ХЕЛПЕРЫ
    // ============================================================

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}