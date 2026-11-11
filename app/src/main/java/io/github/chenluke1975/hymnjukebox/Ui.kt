package io.github.chenluke1975.hymnjukebox

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers to build big, simple views in code (no AndroidX). */
class Ui(private val ctx: Context) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun text(s: CharSequence, sp: Float = 20f, bold: Boolean = false, color: Int = R.color.ink): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ctx.getColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun button(label: CharSequence, primary: Boolean = true, sp: Float = 21f, height: Int = 68, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.getColor(if (primary) R.color.on_primary else R.color.ink))
            setBackgroundResource(if (primary) R.drawable.bg_primary else R.drawable.bg_secondary)
            stateListAnimator = null
            minHeight = dp(height)
            minimumHeight = dp(height)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = Gravity.CENTER
            setOnClickListener(onClick)
        }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    fun column(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun weightLp(w: Float = 1f, height: Int = -2): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, height, w).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }

    fun fullLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(5), dp(10), dp(5)) }
}

/** Recently opened hymn numbers. */
object History {
    fun add(ctx: Context, book: Book, n: Int) {
        val p = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
        val key = "${book.name}:$n"
        val list = (listOf(key) + (p.getString("list", "") ?: "").split(',').filter { it.isNotEmpty() && it != key }).take(20)
        p.edit().putString("list", list.joinToString(",")).apply()
    }

    fun all(ctx: Context): List<Pair<Book, Int>> =
        (ctx.getSharedPreferences("history", Context.MODE_PRIVATE).getString("list", "") ?: "")
            .split(',').mapNotNull {
                val parts = it.split(':')
                if (parts.size != 2) null else runCatching { Book.valueOf(parts[0]) to parts[1].toInt() }.getOrNull()
            }
}
