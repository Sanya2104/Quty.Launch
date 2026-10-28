// *** core/fragments/settings/ApiMethodsFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Список API-методов с разбивкой на «используется оболочкой» и «остальные».
 */
class ApiMethodsFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    private lateinit var infoIcon: ImageView
    private lateinit var activeSectionHeader: TextView
    private lateinit var activeSectionCounter: TextView
    private lateinit var activeContainer: LinearLayout
    private lateinit var otherSectionHeader: TextView
    private lateinit var otherContainer: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_api_methods, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
        } ?: run {
            configManager = ConfigManager(requireContext())
        }
        shellManager = ShellManager(requireContext(), configManager)

        infoIcon = view.findViewById(R.id.api_methods_info_icon)
        activeSectionHeader = view.findViewById(R.id.api_methods_active_header)
        activeSectionCounter = view.findViewById(R.id.api_methods_active_counter)
        activeContainer = view.findViewById(R.id.api_methods_active_container)
        otherSectionHeader = view.findViewById(R.id.api_methods_other_header)
        otherContainer = view.findViewById(R.id.api_methods_other_container)
        emptyText = view.findViewById(R.id.api_methods_empty)

        infoIcon.setOnClickListener {
            showLegendDialog()
        }

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // ============================================================
    // ПОСТРОЕНИЕ СПИСКА
    // ============================================================

    private fun rebuild() {
        activeContainer.removeAllViews()
        otherContainer.removeAllViews()

        val allMethods = ApiRouter.getRegisteredMethods()
        val activeNames = ApiRouter.getActiveMethods()

        val activeShell = shellManager.getActiveShell()
        val declaredNames = activeShell?.apiMethods?.toSet() ?: emptySet()

        if (allMethods.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            infoIcon.visibility = View.GONE
            activeSectionHeader.visibility = View.GONE
            activeSectionCounter.visibility = View.GONE
            activeContainer.visibility = View.GONE
            otherSectionHeader.visibility = View.GONE
            otherContainer.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE

        val activeList = allMethods.filter {
            it.name in declaredNames || it.name in activeNames
        }
        val otherList = allMethods.filter {
            it.name !in declaredNames && it.name !in activeNames
        }

        // «Аномалия» — метод, у которого цвет галочки не зелёный.
        val hasAnomalies = activeList.any { method ->
            val isDeclared = method.name in declaredNames
            val isCalled = method.name in activeNames
            (isCalled && !isDeclared) || (!isCalled && isDeclared)
        }

        if (activeList.isNotEmpty()) {
            infoIcon.visibility = if (hasAnomalies) View.VISIBLE else View.GONE
            activeSectionHeader.visibility = View.VISIBLE
            activeSectionCounter.visibility = View.VISIBLE
            activeContainer.visibility = View.VISIBLE

            val shellName = activeShell?.displayName
                ?: activeShell?.name
                ?: getString(R.string.unknown)
            activeSectionHeader.text = getString(
                R.string.api_methods_active_header,
                shellName
            )

            activeSectionCounter.text = getString(
                R.string.api_methods_active_counter,
                activeList.size,
                allMethods.size
            )

            activeList.forEachIndexed { index, method ->
                val row = createMethodRow(
                    method = method,
                    isDeclared = method.name in declaredNames,
                    isCalled = method.name in activeNames
                )
                activeContainer.addView(row)

                if (index < activeList.lastIndex) {
                    activeContainer.addView(createDivider())
                }
            }
        } else {
            infoIcon.visibility = View.GONE
            activeSectionHeader.visibility = View.GONE
            activeSectionCounter.visibility = View.GONE
            activeContainer.visibility = View.GONE
        }

        if (otherList.isNotEmpty()) {
            otherSectionHeader.visibility = View.VISIBLE
            otherContainer.visibility = View.VISIBLE

            otherList.forEachIndexed { index, method ->
                val row = createMethodRow(
                    method = method,
                    isDeclared = false,
                    isCalled = false
                )
                otherContainer.addView(row)

                if (index < otherList.lastIndex) {
                    otherContainer.addView(createDivider())
                }
            }
        } else {
            otherSectionHeader.visibility = View.GONE
            otherContainer.visibility = View.GONE
        }
    }

    // ============================================================
    // СОЗДАНИЕ СТРОК
    // ============================================================

    /** Строка метода. Галочка: зелёная / оранжевая / серая / нет. */
    private fun createMethodRow(
        method: BaseApiMethod<*>,
        isDeclared: Boolean,
        isCalled: Boolean
    ): View {
        val view = layoutInflater.inflate(
            R.layout.item_api_method,
            activeContainer,
            false
        )

        val icon = view.findViewById<ImageView>(R.id.api_method_icon)
        val name = view.findViewById<TextView>(R.id.api_method_name)
        val description = view.findViewById<TextView>(R.id.api_method_description)
        val check = view.findViewById<ImageView>(R.id.api_method_check)

        icon.setImageResource(method.iconRes)
        icon.imageTintList = ColorStateList.valueOf(
            getThemeColor(R.attr.textDimColor)
        )

        name.text = method.name

        description.text = getString(method.descriptionRes)

        when {
            isCalled && isDeclared -> {
                check.visibility = View.VISIBLE
                check.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.status_granted)
                )
            }
            isCalled && !isDeclared -> {
                check.visibility = View.VISIBLE
                check.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.status_warning)
                )
            }
            !isCalled && isDeclared -> {
                check.visibility = View.VISIBLE
                check.imageTintList = ColorStateList.valueOf(
                    getThemeColor(R.attr.textDimColor)
                )
            }
            else -> {
                check.visibility = View.GONE
            }
        }

        return view
    }

    private fun createDivider(): View {
        val height = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            resources.displayMetrics
        ).toInt()
        val margin = resources.getDimensionPixelSize(R.dimen.spacing_l)

        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            ).apply {
                marginStart = margin
                marginEnd = margin
            }
            setBackgroundColor(getThemeColor(R.attr.dividerColor))
        }
    }

    // ============================================================
    // ДИАЛОГ-ЛЕГЕНДА
    // ============================================================

    private fun showLegendDialog() {
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_api_methods_legend,
            null
        )

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        dialogView.findViewById<View>(R.id.legend_close_button).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.legend_ok_button).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}