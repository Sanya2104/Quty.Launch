// *** core/fragments/settings/StorageFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фрагмент "Память" для Настроек
 *
 * Показывает занятое место по категориям в виде полосы-индикатора
 * и списка с возможностью очистки.
 *
 * Категории:
 * - Кэш (CACHE + context.cacheDir)
 * - Логи (LOGS)
 * - Временные (TEMP)
 * - Обновления (UPDATES)
 * - Оболочки (SHELLS) — не очищаются здесь
 * - Экспорты (EXPORTS)
 * - Резервные копии (BACKUPS)
 *
 * Клик по категории — только выделение (полоса + строка).
 * Очистка — только через кнопку «Очистка» (диалог dialog_storage_clear).
 *
 * После очистки помечаем SettingsActivity как «требуется перезапуск»
 * (markRestartRequired) — при выходе появится диалог «Применить параметры».
 *
 * Разметка строки категории вынесена в item_storage_category.xml,
 * диалог очистки — в dialog_storage_clear.xml.
 */
class StorageFragment : Fragment() {

    // Менеджеры
    private lateinit var storageManager: StorageManager
    private lateinit var configManager: ConfigManager

    // UI
    private lateinit var barContainer: LinearLayout
    private lateinit var categoriesContainer: LinearLayout
    private lateinit var clearButton: Button
    private lateinit var emptyText: TextView
    private lateinit var totalValue: TextView

    // Список категорий
    private val categories = mutableListOf<StorageCategoryItem>()

    // Текущая выбранная категория (null = ничего не выбрано)
    private var selectedCategoryId: String? = null

    // Идёт ли сейчас очистка (блокируем повторные клики)
    private var isClearing = false

    // Флаг: была ли очистка (для показа диалога перезапуска при выходе)
    private var needsRestart = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_storage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
        } ?: run {
            configManager = ConfigManager(requireContext())
        }

        storageManager = StorageManager(requireContext())

        barContainer = view.findViewById(R.id.storage_bar)
        categoriesContainer = view.findViewById(R.id.storage_categories)
        clearButton = view.findViewById(R.id.btn_clear)
        emptyText = view.findViewById(R.id.storage_empty)
        totalValue = view.findViewById(R.id.storage_total_value)

        // Скругление полосы-индикатора (clipToOutline доступен с API 21)
        barContainer.clipToOutline = true

        clearButton.setOnClickListener {
            showMassClearDialog()
        }
    }

    override fun onResume() {
        super.onResume()

        // Синхронизируем флаг необходимости перезапуска с активностью
        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }

        // Пересчитываем размеры при каждом входе — данные меняются вне раздела
        refreshSizes()
    }

    // ============================================================
    // ПОДСЧЁТ РАЗМЕРОВ
    // ============================================================

    /**
     * Пересчитывает размеры всех категорий и обновляет UI
     */
    private fun refreshSizes() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                buildCategoriesWithSizes()
            }

            categories.clear()
            categories.addAll(sizes)

            // Если ранее выбранная категория исчезла — сбрасываем выделение
            if (selectedCategoryId != null && categories.none { it.id == selectedCategoryId }) {
                selectedCategoryId = null
            }

            // Итого по всем категориям
            val total = categories.sumOf { it.size }
            totalValue.text = storageManager.formatSize(total)

            rebuildBar()
            rebuildCategoryList()

            // Применяем сохранённое выделение (если есть)
            applyBarSelection(selectedCategoryId)
            applyListSelection(selectedCategoryId)

            updateEmptyState()
        }
    }

    /**
     * Формирует список категорий с актуальными размерами
     */
    private fun buildCategoriesWithSizes(): List<StorageCategoryItem> {
        val cacheAppSize = requireContext().cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        val cacheDirSize = storageManager.getDirectorySize(StorageDirectory.CACHE)
        val cacheTotal = cacheDirSize + cacheAppSize

        return listOf(
            StorageCategoryItem(
                id = "cache",
                titleRes = R.string.storage_category_cache,
                colorRes = R.color.scheme_orange_primary,
                directory = StorageDirectory.CACHE,
                size = cacheTotal,
                canClear = true,
                extraCacheDir = true
            ),
            StorageCategoryItem(
                id = "logs",
                titleRes = R.string.storage_category_logs,
                colorRes = R.color.scheme_green_primary,
                directory = StorageDirectory.LOGS,
                size = storageManager.getDirectorySize(StorageDirectory.LOGS),
                canClear = true
            ),
            StorageCategoryItem(
                id = "temp",
                titleRes = R.string.storage_category_temp,
                colorRes = R.color.scheme_cyan_primary,
                directory = StorageDirectory.TEMP,
                size = storageManager.getDirectorySize(StorageDirectory.TEMP),
                canClear = true
            ),
            StorageCategoryItem(
                id = "updates",
                titleRes = R.string.storage_category_updates,
                colorRes = R.color.scheme_blue_primary,
                directory = StorageDirectory.UPDATES,
                size = storageManager.getDirectorySize(StorageDirectory.UPDATES),
                canClear = true
            ),
            StorageCategoryItem(
                id = "shells",
                titleRes = R.string.storage_category_shells,
                colorRes = R.color.scheme_teal_primary,
                directory = StorageDirectory.SHELLS,
                size = storageManager.getDirectorySize(StorageDirectory.SHELLS),
                canClear = false
            ),
            StorageCategoryItem(
                id = "exports",
                titleRes = R.string.storage_category_exports,
                colorRes = R.color.scheme_purple_primary,
                directory = StorageDirectory.EXPORTS,
                size = storageManager.getDirectorySize(StorageDirectory.EXPORTS),
                canClear = true
            ),
            StorageCategoryItem(
                id = "backups",
                titleRes = R.string.storage_category_backups,
                colorRes = R.color.scheme_pink_primary,
                directory = StorageDirectory.BACKUPS,
                size = storageManager.getDirectorySize(StorageDirectory.BACKUPS),
                canClear = true
            )
        )
    }

    // ============================================================
    // ПОЛОСА-ИНДИКАТОР
    // ============================================================

    /**
     * Перестраивает полосу-индикатор из сегментов
     * Сегменты пропорциональны размеру категорий
     */
    private fun rebuildBar() {
        barContainer.removeAllViews()

        val totalSize = categories.sumOf { it.size }
        if (totalSize <= 0) {
            return
        }

        // Минимальный вес, чтобы совсем маленькие сегменты были видны
        val minWeight = 0.02f

        categories.forEach { category ->
            if (category.size <= 0) return@forEach

            val weight = (category.size.toFloat() / totalSize).coerceAtLeast(minWeight)

            val segment = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    weight
                ).apply {
                    marginEnd = 1
                }
                setBackgroundColor(
                    ContextCompat.getColor(requireContext(), category.colorRes)
                )
                tag = category.id
            }

            barContainer.addView(segment)
        }
    }

    /**
     * Применяет анимацию выделения к сегментам полосы
     * @param selectedId id выбранной категории или null для сброса
     */
    private fun applyBarSelection(selectedId: String?) {
        for (i in 0 until barContainer.childCount) {
            val segment = barContainer.getChildAt(i)
            val segmentId = segment.tag as? String ?: continue
            val isSelected = segmentId == selectedId

            // Сбрасываем предыдущие анимации и фон
            segment.animate().cancel()
            segment.scaleY = 1f
            segment.alpha = 1f

            val color = ContextCompat.getColor(
                requireContext(),
                categories.first { it.id == segmentId }.colorRes
            )
            segment.setBackgroundColor(color)

            if (selectedId == null) {
                // Ничего не выбрано — обычное состояние
                continue
            }

            if (isSelected) {
                // Выделение: scaleY + обводка
                val drawable = ContextCompat.getDrawable(
                    requireContext(),
                    R.drawable.bg_storage_segment_selected
                )?.mutate()
                (drawable as? GradientDrawable)?.setStroke(
                    resources.getDimensionPixelSize(R.dimen.spacing_xxs),
                    color
                )
                segment.background = drawable
                segment.setBackgroundColor(color)
                segment.pivotY = segment.height.toFloat()

                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(segment, "scaleY", 1f, 1.15f)
                    )
                    duration = 150
                    interpolator = DecelerateInterpolator()
                    start()
                }
            } else {
                // Остальные — приглушаем
                segment.animate()
                    .alpha(0.4f)
                    .setDuration(150)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    // ============================================================
    // СПИСОК КАТЕГОРИЙ
    // ============================================================

    /**
     * Перестраивает список категорий (с разделителями между строками)
     * Стиль как в разделе «О системе»
     */
    private fun rebuildCategoryList() {
        categoriesContainer.removeAllViews()

        val dividerHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            resources.displayMetrics
        ).toInt()

        val dividerMargin = resources.getDimensionPixelSize(R.dimen.spacing_l)

        categories.forEachIndexed { index, category ->
            val row = createCategoryRow(category)
            categoriesContainer.addView(row)

            // Разделитель между строками (кроме последней)
            if (index < categories.lastIndex) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dividerHeight
                    ).apply {
                        marginStart = dividerMargin
                        marginEnd = dividerMargin
                    }
                    setBackgroundColor(getThemeColor(R.attr.dividerColor))
                }
                categoriesContainer.addView(divider)
            }
        }
    }

    /**
     * Заполняет строку категории данными из item_storage_category.xml
     * Разметка — в XML, здесь только привязка данных и клик.
     */
    private fun createCategoryRow(category: StorageCategoryItem): View {
        val view = layoutInflater.inflate(
            R.layout.item_storage_category,
            categoriesContainer,
            false
        )

        val dot = view.findViewById<View>(R.id.category_dot)
        val title = view.findViewById<TextView>(R.id.category_title)
        val size = view.findViewById<TextView>(R.id.category_size)

        // Цвет кружка
        (dot.background?.mutate() as? GradientDrawable)?.setColor(
            ContextCompat.getColor(requireContext(), category.colorRes)
        )

        // Название
        title.text = getString(category.titleRes)

        // Размер
        size.text = storageManager.formatSize(category.size)

        // Прозрачность для пустых категорий
        view.alpha = if (category.size > 0) 1f else 0.5f

        // Tag для последующего поиска при выделении
        view.tag = category.id

        // Клик
        view.setOnClickListener {
            onCategoryClicked(category)
        }

        return view
    }

    // ============================================================
    // КЛИК ПО КАТЕГОРИИ
    // ============================================================

    /**
     * Клик по категории — только выделение.
     * Диалог очистки НЕ показываем (очистка только через кнопку «Очистка»).
     */
    private fun onCategoryClicked(category: StorageCategoryItem) {
        if (isClearing) return

        // Если уже выбрана — снимаем выделение
        if (selectedCategoryId == category.id) {
            selectedCategoryId = null
            applyBarSelection(null)
            applyListSelection(null)
            return
        }

        // Иначе — выделяем
        selectedCategoryId = category.id
        applyBarSelection(category.id)
        applyListSelection(category.id)
    }

    /**
     * Применяет подсветку к строке списка
     */
    private fun applyListSelection(selectedId: String?) {
        for (i in 0 until categoriesContainer.childCount) {
            val row = categoriesContainer.getChildAt(i)
            val rowId = row.tag as? String ?: continue

            if (rowId == selectedId) {
                row.setBackgroundResource(R.drawable.bg_item_rounded_selected)
            } else {
                row.setBackgroundResource(R.drawable.bg_item_rounded)
            }
        }
    }

    // ============================================================
    // ДИАЛОГ МАССОВОЙ ОЧИСТКИ
    // ============================================================

    /**
     * Диалог массовой очистки (кнопка «Очистка»)
     * Разметка — в dialog_storage_clear.xml, строки — в item_storage_clear_category.xml
     */
    private fun showMassClearDialog() {
        if (isClearing) return

        // Собираем только те категории, что можно чистить
        val clearableCategories = categories.filter { it.canClear }

        if (clearableCategories.isEmpty()) {
            Toast.makeText(requireContext(), R.string.storage_nothing_to_clear, Toast.LENGTH_SHORT).show()
            return
        }

        // Инфлейтим разметку диалога
        val dialogView = layoutInflater.inflate(R.layout.dialog_storage_clear, null)

        val checkboxesContainer = dialogView.findViewById<LinearLayout>(R.id.storage_clear_checkboxes)
        val closeButton = dialogView.findViewById<ImageButton>(R.id.storage_clear_close_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.storage_clear_cancel_button)
        val confirmButton = dialogView.findViewById<Button>(R.id.storage_clear_confirm_button)

        val checkboxes = mutableMapOf<String, CheckBox>()

        clearableCategories.forEach { category ->
            val row = layoutInflater.inflate(
                R.layout.item_storage_clear_category,
                checkboxesContainer,
                false
            )

            val cb = row.findViewById<CheckBox>(R.id.clear_category_checkbox)
            val title = row.findViewById<TextView>(R.id.clear_category_title)
            val size = row.findViewById<TextView>(R.id.clear_category_size)

            val isEmpty = category.size <= 0

            cb.isChecked = false
            cb.isEnabled = !isEmpty
            cb.buttonTintList = ColorStateList.valueOf(
                getThemeColor(R.attr.buttonPrimaryColor)
            )

            title.text = getString(category.titleRes)
            size.text = storageManager.formatSize(category.size)

            row.alpha = if (isEmpty) 0.5f else 1f

            checkboxesContainer.addView(row)
            checkboxes[category.id] = cb
        }

        // Создаём диалог с прозрачной темой
        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Прозрачный фон окна — чтобы был виден CardView со скруглением
        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        // Кнопка закрытия (крестик)
        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        // Кнопка «Отмена»
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // Кнопка «Очистить выбранное»
        confirmButton.setOnClickListener {
            val selected = checkboxes.filterValues { it.isChecked }.keys
            dialog.dismiss()
            if (selected.isNotEmpty()) {
                clearSelectedCategories(selected)
            }
        }

        dialog.show()
    }

    /**
     * Очищает выбранные категории
     */
    private fun clearSelectedCategories(ids: Set<String>) {
        if (isClearing) return
        isClearing = true

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { id ->
                    val category = categories.firstOrNull { it.id == id } ?: return@forEach
                    when (id) {
                        "cache" -> {
                            storageManager.removeAll(StorageDirectory.CACHE)
                            requireContext().cacheDir.deleteRecursively()
                            requireContext().cacheDir.mkdirs()
                            CacheManager.clearCache(requireContext())
                        }
                        "logs" -> {
                            LoggerManager.clear()
                        }
                        else -> {
                            storageManager.removeAll(category.directory)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isClearing = false

                // Сообщаем SettingsActivity, что требуется перезапуск
                (activity as? SettingsActivity)?.markRestartRequired()
                needsRestart = true

                Toast.makeText(
                    requireContext(),
                    R.string.storage_cleared_multiple_toast,
                    Toast.LENGTH_SHORT
                ).show()

                selectedCategoryId = null
                refreshSizes()
            }
        }
    }

    // ============================================================
    // ПУСТОЕ СОСТОЯНИЕ
    // ============================================================

    private fun updateEmptyState() {
        val totalSize = categories.sumOf { it.size }
        if (totalSize <= 0) {
            emptyText.visibility = View.VISIBLE
            barContainer.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            barContainer.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /**
     * Модель категории
     */
    data class StorageCategoryItem(
        val id: String,
        val titleRes: Int,
        val colorRes: Int,
        val directory: StorageDirectory,
        val size: Long,
        val canClear: Boolean,
        val extraCacheDir: Boolean = false
    )
}