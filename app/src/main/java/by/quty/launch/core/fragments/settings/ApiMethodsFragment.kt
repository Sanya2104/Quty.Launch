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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "API методы" для Настроек.
 *
 * Показывает список всех API методов ядра.
 *
 * Разделение на секции:
 * - «Используется этой оболочкой» — методы, вызванные хотя бы раз
 *   за текущую сессию (через JsBridge → ApiRouter.execute).
 * - «Остальные методы» — все прочие.
 *
 * Метки:
 * - Зелёная галочка — метод активен (вызывался).
 * - Без галочки — метод пока не вызывался.
 *
 * Позже планируется добавить декларацию методов в manifest.json оболочки
 * (серая галочка — «заявлен, но не вызван»).
 */
class ApiMethodsFragment : Fragment() {

    // Менеджеры
    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // UI
    private lateinit var activeSectionHeader: TextView
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

        activeSectionHeader = view.findViewById(R.id.api_methods_active_header)
        activeContainer = view.findViewById(R.id.api_methods_active_container)
        otherSectionHeader = view.findViewById(R.id.api_methods_other_header)
        otherContainer = view.findViewById(R.id.api_methods_other_container)
        emptyText = view.findViewById(R.id.api_methods_empty)

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Активные методы могли измениться, пока фрагмент был на паузе
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

        if (allMethods.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            activeSectionHeader.visibility = View.GONE
            activeContainer.visibility = View.GONE
            otherSectionHeader.visibility = View.GONE
            otherContainer.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE

        // Делим методы на две группы
        val activeList = allMethods.filter { it.name in activeNames }
        val otherList = allMethods.filter { it.name !in activeNames }

        // Секция 1: Активные
        if (activeList.isNotEmpty()) {
            activeSectionHeader.visibility = View.VISIBLE
            activeContainer.visibility = View.VISIBLE
            activeSectionHeader.text = getString(
                R.string.api_methods_active_header,
                activeList.size,
                allMethods.size
            )

            activeList.forEachIndexed { index, method ->
                val row = createMethodRow(method, isActive = true)
                activeContainer.addView(row)

                if (index < activeList.lastIndex) {
                    activeContainer.addView(createDivider())
                }
            }
        } else {
            activeSectionHeader.visibility = View.GONE
            activeContainer.visibility = View.GONE
        }

        // Секция 2: Остальные
        if (otherList.isNotEmpty()) {
            otherSectionHeader.visibility = View.VISIBLE
            otherContainer.visibility = View.VISIBLE

            otherList.forEachIndexed { index, method ->
                val row = createMethodRow(method, isActive = false)
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

    /**
     * Заполняет строку метода данными из item_api_method.xml.
     * Разметка — в XML, здесь только привязка данных.
     */
    private fun createMethodRow(
        method: BaseApiMethod<*>,
        isActive: Boolean
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

        // Иконка метода — индивидуальная для каждого метода
        icon.setImageResource(method.iconRes)
        icon.imageTintList = ColorStateList.valueOf(
            getThemeColor(R.attr.textDimColor)
        )

        // Название
        name.text = method.name

        // Описание
        description.text = getString(method.descriptionRes)

        // Галочка для активных
        if (isActive) {
            check.visibility = View.VISIBLE
            check.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_granted)
            )
        } else {
            check.visibility = View.GONE
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
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}