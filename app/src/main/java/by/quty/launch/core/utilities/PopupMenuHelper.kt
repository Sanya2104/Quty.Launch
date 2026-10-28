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
 * Кастомное PopupWindow-меню с ripple; позиционируется под anchor.
 */
object PopupMenuHelper {

    /** Пункт меню. */
    data class Item(
        val text: String,
        val onClick: () -> Unit
    )

    /** Показывает меню под anchor. */
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

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        items.forEach { item ->
            content.addView(createMenuItem(context, item, popup))
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        var x = when (gravity) {
            Gravity.END -> anchorLocation[0] + anchor.width - popupWidth
            Gravity.START -> anchorLocation[0]
            else -> anchorLocation[0]
        }
        var y = anchorLocation[1] + anchor.height

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        if (x + popupWidth > screenWidth) {
            x = screenWidth - popupWidth
        }
        if (x < 0) {
            x = 0
        }

        if (y + popupHeight > screenHeight) {
            y = anchorLocation[1] - popupHeight
        }
        if (y < 0) {
            y = 0
        }

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)

        return popup
    }

    /** Создаёт один пункт меню. */
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

            setBackgroundResource(R.drawable.bg_item_rounded)

            setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
        }
    }

    /** Резолвит цвет темы по атрибуту. */
    private fun getThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}