// *** core/fragments/logger/LoggerFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.adapters.LoggerAdapter
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerEntryModel
import by.quty.launch.core.model.LoggerLevelModel
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Логи: лента записей, фильтр по уровням/тегам, сохранение/копирование/очистка.
 */
class LoggerFragment : Fragment(), LoggerManager.LoggerListener {

    private val levelsOrder = listOf(
        LoggerLevelModel.ERROR,
        LoggerLevelModel.WARN,
        LoggerLevelModel.INFO,
        LoggerLevelModel.DEBUG
    )

    private lateinit var cardView: View
    private lateinit var recyclerLog: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var counterView: TextView
    private lateinit var btnFilter: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnCopy: ImageButton

    private lateinit var adapter: LoggerAdapter

    /** Новые (индекс 0) → старые. */
    private val entries = mutableListOf<LoggerEntryModel>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logger, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardView = view.findViewById(R.id.logger_card)
        recyclerLog = view.findViewById(R.id.logger_list)
        emptyText = view.findViewById(R.id.logger_empty)
        counterView = view.findViewById(R.id.logger_counter)
        btnFilter = view.findViewById(R.id.logger_action_filter)
        btnSave = view.findViewById(R.id.logger_action_save)
        btnClear = view.findViewById(R.id.logger_action_clear)
        btnCopy = view.findViewById(R.id.logger_action_copy)

        cardView.clipToOutline = true

        setupRecycler()
        setupActions()
        updateFilterIcon()
        syncWithBuffer()
    }

    override fun onStart() {
        super.onStart()
        syncWithBuffer()
        LoggerManager.subscribe(this)
    }

    override fun onStop() {
        super.onStop()
        LoggerManager.unsubscribe(this)
    }

    private fun setupRecycler() {
        adapter = LoggerAdapter(
            entries = entries,
            onItemClick = { entry -> showDetailsDialog(entry) }
        )

        recyclerLog.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = false
        }
        recyclerLog.adapter = adapter
    }

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================

    private fun setupActions() {
        btnFilter.setOnClickListener { showFilterDialog() }
        btnSave.setOnClickListener { saveCurrentLog() }
        btnClear.setOnClickListener { clearLog() }
        btnCopy.setOnClickListener { copyAllToClipboard() }
    }

    // ============================================================
    // ФИЛЬТР
    // ============================================================

    /** ic_filter / ic_filter_x + подсветка, если фильтр не дефолтный. */
    private fun updateFilterIcon() {
        val isDefault = LoggerManager.isFilterDefault(requireContext())

        val iconRes = if (isDefault) {
            R.drawable.ic_filter
        } else {
            R.drawable.ic_filter_clear
        }
        btnFilter.setImageResource(iconRes)

        val tintAttr = if (isDefault) {
            R.attr.textDimColor
        } else {
            R.attr.buttonPrimaryColor
        }
        val tintColor = getThemeColor(requireContext(), tintAttr)
        btnFilter.imageTintList = ColorStateList.valueOf(tintColor)
    }

    /** Диалог фильтра: уровни + теги. */
    private fun showFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logger_filter, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val levelsContainer =
            dialogView.findViewById<LinearLayout>(R.id.logger_filter_levels_container)
        val tagsContainer =
            dialogView.findViewById<LinearLayout>(R.id.logger_filter_tags_container)
        val closeButton =
            dialogView.findViewById<ImageButton>(R.id.logger_filter_close_button)
        val resetButton =
            dialogView.findViewById<Button>(R.id.logger_filter_reset_button)
        val applyButton =
            dialogView.findViewById<Button>(R.id.logger_filter_apply_button)

        val currentLevels = LoggerManager.getFilterLevels(requireContext())
        val currentTags = LoggerManager.getFilterTags(requireContext())

        val levelCheckboxes = mutableMapOf<LoggerLevelModel, CheckBox>()
        val tagCheckboxes = mutableMapOf<LoggerTagModel, CheckBox>()

        levelsOrder.forEach { level ->
            val row = layoutInflater.inflate(
                R.layout.item_logger_filter,
                levelsContainer,
                false
            )

            val bar = row.findViewById<View>(R.id.filter_level_bar)
            val name = row.findViewById<TextView>(R.id.filter_level_name)
            val cb = row.findViewById<CheckBox>(R.id.filter_level_checkbox)

            val levelColor = getLevelColor(requireContext(), level)
            ViewCompat.setBackgroundTintList(
                bar,
                ColorStateList.valueOf(levelColor)
            )

            name.text = level.name

            cb.isChecked = level in currentLevels
            cb.buttonTintList = ColorStateList.valueOf(
                getThemeColor(requireContext(), R.attr.buttonPrimaryColor)
            )

            row.setOnClickListener { cb.isChecked = !cb.isChecked }

            levelsContainer.addView(row)
            levelCheckboxes[level] = cb
        }

        LoggerTagModel.entries.forEach { tag ->
            val row = layoutInflater.inflate(
                R.layout.item_logger_filter,
                tagsContainer,
                false
            )

            val bar = row.findViewById<View>(R.id.filter_level_bar)
            val name = row.findViewById<TextView>(R.id.filter_level_name)
            val cb = row.findViewById<CheckBox>(R.id.filter_level_checkbox)

            val tagColor = getThemeColor(requireContext(), R.attr.textDimColor)
            ViewCompat.setBackgroundTintList(
                bar,
                ColorStateList.valueOf(tagColor)
            )

            name.text = tag.displayName

            cb.isChecked = tag in currentTags
            cb.buttonTintList = ColorStateList.valueOf(
                getThemeColor(requireContext(), R.attr.buttonPrimaryColor)
            )

            row.setOnClickListener { cb.isChecked = !cb.isChecked }

            tagsContainer.addView(row)
            tagCheckboxes[tag] = cb
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        resetButton.setOnClickListener {
            levelCheckboxes.forEach { (_, cb) -> cb.isChecked = true }
            tagCheckboxes.forEach { (_, cb) -> cb.isChecked = true }
        }

        applyButton.setOnClickListener {
            val selectedLevels = levelCheckboxes
                .filterValues { it.isChecked }
                .keys
                .toSet()

            val selectedTags = tagCheckboxes
                .filterValues { it.isChecked }
                .keys
                .toSet()

            LoggerManager.setFilterLevels(requireContext(), selectedLevels)
            LoggerManager.setFilterTags(requireContext(), selectedTags)

            dialog.dismiss()

            updateFilterIcon()
            applyFilter()

            val message = when {
                selectedLevels.isEmpty() && selectedTags.isEmpty() ->
                    getString(R.string.logger_filter_applied_none)

                selectedLevels.isEmpty() ->
                    getString(R.string.logger_filter_applied_tags_only, selectedTags.size)

                selectedTags.isEmpty() ->
                    getString(R.string.logger_filter_applied_levels_only, selectedLevels.size)

                else ->
                    getString(
                        R.string.logger_filter_applied,
                        selectedLevels.size,
                        selectedTags.size
                    )
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        val snapshot = LoggerManager.getFilteredEntries(requireContext())
        entries.clear()
        entries.addAll(snapshot)
        adapter.submitEntries(snapshot)

        updateCounter()
        updateEmptyState()
        scrollToTop()
    }

    // ============================================================
    // СОХРАНЕНИЕ / ОЧИСТКА / КОПИРОВАНИЕ
    // ============================================================

    private fun saveCurrentLog() {
        if (LoggerManager.size() == 0) {
            Toast.makeText(
                requireContext(),
                R.string.logger_settings_save_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val fileName = LoggerManager.writeToFile(requireContext())
            if (fileName != null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.logger_settings_save_success, fileName),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.logger_settings_save_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun clearLog() {
        if (entries.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.logger_action_clear_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        LoggerManager.clear()
    }

    private fun copyAllToClipboard() {
        if (entries.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.logger_copy_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val text = formatAllEntries()
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("logger", text)
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            requireContext(),
            getString(R.string.logger_copy_success, entries.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun formatAllEntries(): String {
        return buildString {
            entries.forEach { entry ->
                append("[")
                append(timeFormat.format(Date(entry.timestamp)))
                append("] ")
                append(entry.tag.displayName)
                append("  ")
                appendLine(entry.message)

                entry.details?.let { details ->
                    details.lines().forEach { line ->
                        append("    ")
                        appendLine(line)
                    }
                }
            }
        }
    }

    // ============================================================
    // ДИАЛОГ DETAILS
    // ============================================================

    private fun showDetailsDialog(entry: LoggerEntryModel) {
        val details = entry.details ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_logger_details, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val subtitleView: TextView = dialogView.findViewById(R.id.logger_details_subtitle)
        val time = timeFormat.format(Date(entry.timestamp))
        subtitleView.text = "$time  ${entry.tag.displayName}"

        val messageView: TextView = dialogView.findViewById(R.id.logger_details_message)
        messageView.text = entry.message

        val contentView: TextView = dialogView.findViewById(R.id.logger_details_content)
        contentView.text = details

        dialogView.findViewById<ImageButton>(R.id.logger_details_close_button).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.logger_details_close_action).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.logger_details_copy_button).setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("logger_details", details)
            @Suppress("UsePropertyAccessSyntax")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                R.string.logger_files_copied,
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.show()
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ
    // ============================================================

    @SuppressLint("NotifyDataSetChanged")
    private fun syncWithBuffer() {
        if (!isAdded) return

        val snapshot = LoggerManager.getFilteredEntries(requireContext())
        if (entries.size == snapshot.size) {
            updateCounter()
            updateEmptyState()
            return
        }

        val oldSize = entries.size
        entries.clear()
        entries.addAll(snapshot)

        if (oldSize == 0) {
            adapter.notifyItemRangeInserted(0, entries.size)
        } else {
            adapter.notifyDataSetChanged()
        }

        updateCounter()
        updateEmptyState()
        scrollToTop()
    }

    private fun updateCounter() {
        counterView.text = getString(R.string.logger_counter_format, entries.size, 500)
    }

    private fun updateEmptyState() {
        if (entries.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerLog.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerLog.visibility = View.VISIBLE
        }
    }

    private fun scrollToTop() {
        recyclerLog.post {
            if (entries.isNotEmpty()) {
                recyclerLog.scrollToPosition(0)
            }
        }
    }

    // ============================================================
    // LoggerManager.LoggerListener
    // ============================================================

    override fun onEntryAdded(entry: LoggerEntryModel) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded || view == null) return@launch

            val enabledLevels = LoggerManager.getFilterLevels(requireContext())
            val enabledTags = LoggerManager.getFilterTags(requireContext())

            if (entry.level !in enabledLevels || entry.tag !in enabledTags) {
                return@launch
            }

            adapter.addEntry(entry)
            updateCounter()
            updateEmptyState()
            scrollToTop()
        }
    }

    override fun onCleared() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded || view == null) return@launch

            adapter.clearAll()
            updateCounter()
            updateEmptyState()
        }
    }

    // ============================================================
    // ХЕЛПЕРЫ
    // ============================================================

    private fun getThemeColor(context: Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun getLevelColor(context: Context, level: LoggerLevelModel): Int {
        return when (level) {
            LoggerLevelModel.DEBUG ->
                ContextCompat.getColor(context, R.color.text_dim)
            LoggerLevelModel.INFO ->
                ContextCompat.getColor(context, R.color.scheme_blue_primary)
            LoggerLevelModel.WARN ->
                ContextCompat.getColor(context, R.color.status_warning)
            LoggerLevelModel.ERROR ->
                ContextCompat.getColor(context, R.color.text_error)
        }
    }
}