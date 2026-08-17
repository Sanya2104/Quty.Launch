// *** StoreActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import by.quty.launch.core.fragments.ShellDetailFragment
import by.quty.launch.core.fragments.ShellListFragment
import by.quty.launch.core.managers.StoreManager
import kotlinx.coroutines.launch
import androidx.core.view.isVisible

/**
 * Активность магазина оболочек Quty.Launch
 * Позволяет просматривать и устанавливать оболочки из репозитория
 */
class StoreActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var closeButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private lateinit var storeManager: StoreManager
    private var isLoading = false

    companion object {
        private const val TAB_SHELLS = 0
        private const val TAB_MY = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_store)

        applyOrientation()

        storeManager = StoreManager(this)

        initViews()
        setupTabs()

        // Загружаем оболочки
        loadShells()

        // Включаем иммерсивный режим
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        // ===== ОБРАБОТКА СИСТЕМНОЙ КНОПКИ "НАЗАД" =====
        // Перехватываем нажатие "Назад" и корректно закрываем детальный вид
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Если открыт детальный вид — закрываем его
                if (isDetailVisible()) {
                    hideDetail()
                } else {
                    // Если детальный вид не открыт — закрываем активность
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun initViews() {
        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)
        closeButton = findViewById(R.id.btn_close)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun setupTabs() {
        val adapter = StorePagerAdapter(this, storeManager)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                TAB_SHELLS -> tab.text = getString(R.string.store_tab_shells)
                TAB_MY -> tab.text = getString(R.string.store_tab_my)
            }
        }.attach()
    }

    private fun loadShells() {
        if (isLoading) return
        isLoading = true

        showLoading(true)

        lifecycleScope.launch {
            val shells = storeManager.fetchShells()
            isLoading = false
            showLoading(false)

            if (shells == null) {
                Toast.makeText(
                    this@StoreActivity,
                    getString(R.string.store_load_error),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Фрагменты уже получили StoreManager через адаптер
                // Просто уведомляем их об обновлении
                viewPager.post {
                    val fragments = listOf(
                        supportFragmentManager.findFragmentByTag("f0"),
                        supportFragmentManager.findFragmentByTag("f1")
                    )
                    fragments.forEach { fragment ->
                        (fragment as? ShellListFragment)?.notifyDataChanged()
                    }
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE
            progressText.text = getString(R.string.store_loading)
        } else {
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE
        }
    }

    /**
     * Проверяет, открыт ли детальный вид
     */
    private fun isDetailVisible(): Boolean {
        val fragmentContainer = findViewById<View>(R.id.fragment_container)
        return fragmentContainer.isVisible
    }

    /**
     * Открывает детальный вид оболочки
     */
    fun showDetail(shellId: String) {
        val fragment = ShellDetailFragment.newInstance(shellId)
        fragment.setStoreManager(storeManager)

        // Добавляем фрагмент в backStack для обработки системной кнопки "Назад"
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)  // ← ВАЖНО: добавляем в backStack
            .commit()

        // Показываем контейнер, скрываем ViewPager и TabLayout
        findViewById<View>(R.id.fragment_container).visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        tabLayout.visibility = View.GONE
    }

    /**
     * Закрывает детальный вид
     */
    fun hideDetail() {
        // Убираем фрагмент из backStack
        supportFragmentManager.popBackStack()

        // Показываем ViewPager и TabLayout, скрываем контейнер
        findViewById<View>(R.id.fragment_container).visibility = View.GONE
        viewPager.visibility = View.VISIBLE
        tabLayout.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        storeManager.clearCache()
    }

    // ============================================================
    // ВНУТРЕННИЙ АДАПТЕР
    // ============================================================

    class StorePagerAdapter(
        activity: StoreActivity,
        private val storeManager: StoreManager
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                TAB_SHELLS -> ShellListFragment.newInstance(false).apply {
                    setStoreManager(storeManager)
                }
                TAB_MY -> ShellListFragment.newInstance(true).apply {
                    setStoreManager(storeManager)
                }
                else -> ShellListFragment.newInstance(false)
            }
        }
    }
}