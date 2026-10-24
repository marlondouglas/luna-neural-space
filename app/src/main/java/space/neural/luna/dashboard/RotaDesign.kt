package space.neural.luna.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import space.neural.luna.R

/** Shared design tokens for the existing Android View stack; no second UI framework. */
object RotaDesign {
    const val Radius = 18f
    const val Touch = 48
    const val Gap = 12
    fun panel(ui: Ui, accent: Int = Cores.VIOLETA) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Cores.mix(Cores.SUPERFICIE, accent, .10f), Cores.SUPERFICIE, Cores.OBSIDIAN)
    ).apply { cornerRadius = ui.dpf(Radius); setStroke(ui.dp(1), Cores.alpha(accent, .75f)) }
    fun gradient(ui: Ui, a: Int = Cores.VIOLETA, b: Int = Cores.MAGENTA, radius: Float = Radius) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply {
            cornerRadius = ui.dpf(radius); setStroke(ui.dp(1), Cores.alpha(Cores.BRANCO, .5f))
        }
    fun panelBox(ui: Ui, title: String? = null, accent: Int = Cores.VIOLETA): LinearLayout = ui.vbox(ui.dp(14)).apply {
        background = panel(ui, accent); clipToOutline = true
        if (title != null) addView(ui.text(title, 16f, Cores.BRANCO, true), ui.mp(ui.dp(2)))
    }
    fun icon(ui: Ui, resource: Int, color: Int, size: Int = 24): ImageView = ui.icone(resource, color, size)
    fun action(ui: Ui, resource: Int, label: String, color: Int = Cores.CIANO, onClick: () -> Unit): View =
        ui.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(Touch)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.fundo(Cores.ELEVADA, Cores.alpha(color, .4f), 12f)
            addView(icon(ui, resource, color)); addView(ui.text(label, 14f, Cores.BRANCO, true), ui.wc(ui.dp(8)))
            contentDescription = label; isFocusable = true; setOnClickListener { onClick() }
        }
    fun badge(ui: Ui, label: String, color: Int = Cores.CIANO): TextView = ui.text(label, 13f, color, true).apply {
        setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        background = ui.fundo(Cores.mix(Cores.OBSIDIAN, color, .12f), Cores.alpha(color, .6f), 16f)
        maxLines = 2
    }
    fun scrollRow(ui: Ui, row: View) = HorizontalScrollView(ui.ctx).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true; isScrollbarFadingEnabled = false
        contentDescription = "Deslize horizontalmente para ver todas as opções"
        addView(row)
    }
    fun roundIcon(ui: Ui, resource: Int, label: String, color: Int = Cores.CIANO, onClick: () -> Unit): View =
        ui.botaoIcone(resource, label, color) { onClick() }.apply { background = panel(ui, color) }
    fun brand(ui: Ui, subtitle: String? = null): View = ui.hbox().apply {
        addView(BrandMark(ui.ctx), LinearLayout.LayoutParams(ui.dp(38), ui.dp(44)))
        addView(ui.vbox().apply {
            addView(ui.text("ROTA GV", 25f, Cores.CIANO, true).apply { letterSpacing = .06f })
            if (subtitle != null) addView(ui.text(subtitle, 10f, Cores.BRANCO_70))
        }, ui.wc(ui.dp(8)))
    }
}

class BrandMark(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val sx = width / 40f; val sy = height / 48f
        c.save(); c.scale(sx, sy)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.8f; p.color = Cores.CIANO
        val outline = Path().apply { moveTo(20f, 44f); cubicTo(10f, 32f, 4f, 24f, 5f, 16f); cubicTo(7f, -1f, 34f, -1f, 35f, 16f); cubicTo(36f, 24f, 29f, 35f, 20f, 44f) }
        c.drawPath(outline, p)
        p.style = Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(23f, 8f); lineTo(13f, 23f); lineTo(20f, 23f); lineTo(17f, 34f); lineTo(29f, 18f); lineTo(22f, 18f); close() }, p)
        c.restore()
    }
}
