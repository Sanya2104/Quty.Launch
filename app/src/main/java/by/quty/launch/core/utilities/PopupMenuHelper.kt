// *** core/utilities/PopupMenuHelper.kt *** //
package by.quty.launch.core.utilities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import by.quty.launch.R

/**
 * Универсальный хелпер для показа кастомного выпадающего меню (PopupWindow).
 *
 * Использование:
 * ```
 * PopupMenuHelper.show(
 *     anchor = anchorView,
 *     items = listOf(
 *         PopupMenuHelper.Item(text = "Информация") { showInfoDialog() },
 *         PopupMenuHelper.Item(text = "Удалить") { confirmDelete() }
 *     )
 * )
 * ```
 *
 * Ripple-эффект пункта: используется готовый drawable bg_item_rounded
 * (ripple + скругление radius_medium) — тот же, что в настройках,
 * логгере и других экранах.
 *
 * ВАЖНО: кастомный ripple через ?android:attr/colorControlHighlight
 * в PopupWindow НЕ работает — атрибут темы в контексте PopupWindow
 * резолвится в прозрачный цвет, и ripple не виден. bg_item_rounded —
 * проверенный drawable, работает в любом контексте.
 *
 * Пункты разделены небольшим вертикальным отступом (spacing_xxs),
 * чтобы скруглённые ripple'ы не слипались.
 */
object PopupMenuHelper {

    /**
     * Один пункт меню.
     *
     * @param text Текст пункта (уже локализованный — берётся через `context.getString(...)`)
     * @param onClick Колбэк, вызываемый при клике. Popup закрывается **до** вызова колбэка.
     */
    data class Item(
        val text: String,
        val onClick: () -> Unit
    )

    /**
     * Показывает popup под anchor'ом.
     * Popup автоматически позиционируется так, чтобы не выходить за границы экрана.
     *
     * @param anchor View, к которому привязывается меню
     * @param items Список пунктов
     * @param gravity Выравнивание (`Gravity.END` — по правому краю, `Gravity.START` — по левому)
     * @return Созданный PopupWindow (можно сохранить и закрыть вручную)
     */
    @SuppressLint("InflateParams")
    fun show(
        anchor: View,
        items: List<Item>,
        gravity: Int = Gravity.END
    ): PopupWindow {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)

        val popupView = inflater.inflate(R.layout.popup_menu, null)
        val content = popupView.findViewById<LinearLayout>(R.id.popup_content)

        // Создаём PopupWindow
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable
        )

        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        // Добавляем пункты
        items.forEach { item ->
            content.addView(createMenuItem(context, item, popup))
        }

        // Измеряем popup, чтобы знать его размеры
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        // Координаты anchor'а на экране
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        // Позиция по умолчанию — под anchor'ом
        var x = when (gravity) {
            Gravity.END -> anchorLocation[0] + anchor.width - popupWidth
            Gravity.START -> anchorLocation[0]
            else -> anchorLocation[0]
        }
        var y = anchorLocation[1] + anchor.height

        // Корректировка по ширине экрана
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        if (x + popupWidth > screenWidth) {
            x = screenWidth - popupWidth
        }
        if (x < 0) {
            x = 0
        }

        // Корректировка по высоте экрана
        if (y + popupHeight > screenHeight) {
            // Не помещается снизу — показываем над anchor'ом
            y = anchorLocation[1] - popupHeight
        }
        if (y < 0) {
            // Не помещается сверху — прижимаем к верхнему краю
            y = 0
        }

        // Показываем popup в вычисленной позиции
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)

        return popup
    }

    /**
     * Создаёт один пункт меню.
     *
     * Ripple: bg_item_rounded — существующий drawable с ripple и
     * скруглением radius_medium (тот же, что в настройках, логгере
     * и других экранах).
     *
     * Между пунктами добавляется вертикальный отступ (spacing_xxs),
     * чтобы скруглённые ripple'ы соседних пунктов не сливались.
     */
    private fun createMenuItem(
        context: Context,
        item: Item,
        popup: PopupWindow
    ): View {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Отступ между пунктами — чтобы ripple-скругления
                // не сливались в сплошную полосу.
                bottomMargin = resources.getDimensionPixelSize(R.dimen.spacing_xxs)
            }

            text = item.text
            setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

            val padH = resources.getDimensionPixelSize(R.dimen.spacing_l)
            val padV = resources.getDimensionPixelSize(R.dimen.spacing_m)
            setPadding(padH, padV, padH, padV)

            isClickable = true
            isFocusable = true

            // Ripple + скругление. bg_item_rounded уже используется
            // в других местах проекта — проверенный drawable.
            setBackgroundResource(R.drawable.bg_item_rounded)

            setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
        }
    }

    /**
     * Получает цвет из кастомного атрибута темы.
     */
    private fun getThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}