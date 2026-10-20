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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
 *
 * Swipe-to-refresh: потянуть список вниз — перезагрузить каталог с
 * сервера. Жест работает только когда список виден (не в loading /
 * error / empty), чтобы не конфликтовать с пустыми состояниями.
 *
 * Кэш каталога:
 * - при первом заходе показываем loading_state и грузим с сервера;
 * - если кэш есть — сразу показываем его (список виден), фоном
 *   обновляем с сервера;
 * - если сервер вернул новые данные — перерисовываем;
 * - если сервер вернул ошибку, а кэш был показан — оставляем кэш,
 *   показываем toast «Не удалось обновить»;
 * - если сервер вернул ошибку и кэша не было — error_state.
 */
class StoreCatalogFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
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

        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        recyclerView = view.findViewById(R.id.recycler_shells)
        loadingState = view.findViewById(R.id.loading_state)
        errorState = view.findViewById(R.id.error_state)
        errorMessage = view.findViewById(R.id.error_message)
        btnRetry = view.findViewById(R.id.btn_retry)
        emptyState = view.findViewById(R.id.empty_state)

        setupSwipeRefresh()
        setupRecyclerView()
        setupButtons()
        loadInitial()
    }

    /**
     * Настраивает SwipeRefreshLayout:
     * - цвет индикатора = акцент темы (buttonPrimaryColor);
     * - сам жест стартует сверху списка (по умолчанию).
     */
    private fun setupSwipeRefresh() {
        val accentColor = getThemeColor(requireContext(), R.attr.buttonPrimaryColor)
        swipeRefresh.setColorSchemeColors(accentColor)

        swipeRefresh.setOnRefreshListener {
            // Пользователь потянул вниз — грузим только с сервера,
            // кэш игнорируем (пользователь явно хочет свежие данные).
            if (!isLoading) {
                loadFromServer(isUserRefresh = true)
            } else {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = buildAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnRetry.setOnClickListener {
            loadInitial()
        }
    }

    // ============================================================
    // ЗАГРУЗКА: КЭШ + СЕРВЕР
    // ============================================================

    /**
     * Первичная загрузка: сначала пробуем кэш, потом сервер.
     *
     * - Кэш есть → показываем его сразу, фоном грузим сервер.
     * - Кэша нет → показываем loading_state и грузим только сервер.
     */
    private fun loadInitial() {
        if (isLoading) return

        viewLifecycleOwner.lifecycleScope.launch {
            isLoading = true

            // 1. Пробуем кэш
            val cached = storeManager.getCachedShells()

            if (!cached.isNullOrEmpty()) {
                // Показываем кэш сразу
                shells = cached
                adapter = buildAdapter(shells)
                recyclerView.adapter = adapter
                recyclerView.visibility = View.VISIBLE
                swipeRefresh.isEnabled = true

                hideLoading()
                hideError()
                hideEmpty()

                // Фоновая загрузка с сервера — без loading_state,
                // без индикатора SwipeRefreshLayout.
                isLoading = false
                loadFromServerInBackground()
            } else {
                // Кэша нет — грузим с нуля через сервер
                isLoading = false
                loadFromServer(isUserRefresh = false)
            }
        }
    }

    /**
     * Фоновая загрузка с сервера (когда кэш уже показан).
     *
     * Не показывает loading_state, не включает SwipeRefreshLayout.
     * Если сервер вернул новые данные — перерисовывает список.
     * Если ошибка — оставляет кэш и показывает короткий toast.
     */
    private fun loadFromServerInBackground() {
        if (isLoading) return
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = storeManager.fetchShells()
            isLoading = false

            if (result != null) {
                shells = result
                if (shells.isEmpty()) {
                    showEmpty()
                    swipeRefresh.isEnabled = false
                } else {
                    adapter = buildAdapter(shells)
                    recyclerView.adapter = adapter
                    recyclerView.visibility = View.VISIBLE
                    swipeRefresh.isEnabled = true
                }
            } else {
                // Оставляем кэш, показываем toast
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.store_cache_stale_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Загрузка с сервера.
     *
     * @param isUserRefresh true, если инициатор — swipe-to-refresh.
     *        В этом случае НЕ показываем loading_state (индикатор
     *        SwipeRefreshLayout уже виден) и НЕ прячем текущий список
     *        до завершения загрузки.
     */
    private fun loadFromServer(isUserRefresh: Boolean) {
        if (isLoading) {
            if (isUserRefresh) swipeRefresh.isRefreshing = false
            return
        }
        isLoading = true

        if (!isUserRefresh) {
            showLoading()
        }
        hideError()
        hideEmpty()

        // Свайп доступен только когда список уже показан. Во время
        // загрузки (первый раз) и в error/empty — жест не нужен.
        swipeRefresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = storeManager.fetchShells()

            isLoading = false

            if (!isUserRefresh) {
                hideLoading()
            } else {
                swipeRefresh.isRefreshing = false
            }

            if (result != null) {
                shells = result
                if (shells.isEmpty()) {
                    showEmpty()
                    swipeRefresh.isEnabled = false
                } else {
                    adapter = buildAdapter(shells)
                    recyclerView.adapter = adapter
                    recyclerView.visibility = View.VISIBLE
                    swipeRefresh.isEnabled = true
                }
            } else {
                // Ошибка. Если есть что показать — оставляем список,
                // показываем toast. Если нечего — error_state.
                if (shells.isNotEmpty()) {
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
    // ДАННЫЕ ДЛЯ АДАПТЕРА
    // ============================================================

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

    // ============================================================
    // ХЕЛПЕРЫ
    // ============================================================

    private fun getThemeColor(context: android.content.Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}