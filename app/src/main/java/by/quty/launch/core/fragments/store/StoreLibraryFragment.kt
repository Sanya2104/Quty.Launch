// *** core/fragments/store/StoreLibraryFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.StoreActivity
import by.quty.launch.core.adapters.StoreLibraryAdapter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.Shell
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент «Библиотека» магазина.
 *
 * Показывает список всех установленных оболочек:
 * - встроенные (assets: Default и др.);
 * - кастомные (.qutyshell в StorageDirectory.SHELLS).
 *
 * Карточки по стилю совпадают с каталогом (превью, название,
 * автор/версия, тип/размер, статусные плашки).
 *
 * Статусы:
 * - «Активна» — реально загружена в WebView (имя + версия);
 * - «Есть обновление» — добавлено позже (кнопкой проверки);
 *   сейчас пусто.
 *
 * Тап по карточке → StoreActivity.showLibraryDetail(shell.name).
 */
class StoreLibraryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var adapter: StoreLibraryAdapter

    // Множество имён оболочек, у которых есть обновление на сервере.
    // Заполняется позже (кнопкой «Проверить обновления»), сейчас пусто.
    private val updateAvailableNames: MutableSet<String> = mutableSetOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(by.quty.launch.R.layout.fragment_store_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? StoreActivity)?.let { storeActivity ->
            configManager = storeActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)
        } ?: run {
            configManager = ConfigManager(requireContext())
            shellManager = ShellManager(requireContext(), configManager)
        }

        recyclerView = view.findViewById(by.quty.launch.R.id.recycler_library)
        emptyState = view.findViewById(by.quty.launch.R.id.library_empty_state)

        setupRecyclerView()
        loadShells()
    }

    override fun onResume() {
        super.onResume()
        // Перерисовываем — статус активности мог измениться (применили
        // оболочку в детальном экране, вернулись сюда).
        refresh()
    }

    private fun setupRecyclerView() {
        adapter = buildAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    /**
     * Создаёт адаптер с актуальными данными.
     */
    private fun buildAdapter(shells: List<Shell>): StoreLibraryAdapter {
        val activeName = configManager.getActiveShell()
        val activeVersion = configManager.getActiveShellVersion()

        return StoreLibraryAdapter(
            shells = shells,
            activeShellName = activeName,
            activeShellVersion = activeVersion,
            updateAvailableNames = updateAvailableNames,
            onItemClick = { shell ->
                (activity as? StoreActivity)?.showLibraryDetail(shell.name)
            }
        )
    }

    /**
     * Загружает список установленных оболочек (встроенные + кастомные)
     * и отображает их.
     */
    private fun loadShells() {
        val shells = shellManager.getAvailableShells()

        if (shells.isEmpty()) {
            showEmpty()
        } else {
            adapter = buildAdapter(shells)
            recyclerView.adapter = adapter
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    /**
     * Публичный метод для перерисовки списка при возврате из детального
     * экрана (или из другого таба). Вызывается из StoreActivity
     * при необходимости.
     */
    fun refresh() {
        if (!isAdded || view == null) return
        loadShells()
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
    }
}