package space.neural.luna.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Paleta da spec (só estas cinco cores, com transparência). */
object Cores {
    const val OBSIDIAN = 0xFF07060E.toInt()
    const val BRANCO = 0xFFF3F0FF.toInt()
    const val VIOLETA = 0xFF8D6BFF.toInt()
    const val CIANO = 0xFF49E0FF.toInt()
    const val ESMERALDA = 0xFF3EE0A0.toInt()

    fun alpha(c: Int, a: Float): Int = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** Violeta a 10% sobre obsidian (fundo dos cards). */
    val CARD: Int = mix(OBSIDIAN, VIOLETA, 0.10f)
    val BRANCO_70 = alpha(BRANCO, 0.70f)
    val BRANCO_40 = alpha(BRANCO, 0.40f)

    fun mix(base: Int, over: Int, a: Float): Int = Color.rgb(
        (Color.red(base) + (Color.red(over) - Color.red(base)) * a).toInt(),
        (Color.green(base) + (Color.green(over) - Color.green(base)) * a).toInt(),
        (Color.blue(base) + (Color.blue(over) - Color.blue(base)) * a).toInt(),
    )
}

object Fmt {
    private val ptBR = Locale.forLanguageTag("pt-BR")
    private val moeda = NumberFormat.getCurrencyInstance(ptBR)

    /** R$ 1.234,56 (negativo: "▼ −R$ 12,00"). */
    fun reais(centavos: Long): String {
        val s = moeda.format(Math.abs(centavos) / 100.0).replace('\u00A0', ' ').replace('\u202F', ' ')
        return if (centavos < 0) "▼ −$s" else s
    }

    /** R$ sem centavos, para textos curtos ("faltam R$ 45"). */
    fun reaisCurto(centavos: Long): String {
        val r = Math.round(centavos / 100.0)
        return "R$ " + NumberFormat.getIntegerInstance(ptBR).format(r)
    }

    fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun minutos(ms: Long): Long = ms / 60_000

    fun dataCurta(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("EEE, d MMM", ptBR)).replace(".", "")

    fun dataLonga(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ptBR))

    fun nomeApp(app: String): String = when (app) {
        AppCorrida.NOVENTA_E_NOVE -> "99"
        AppCorrida.UBER -> "Uber"
        AppCorrida.INDRIVE -> "InDrive"
        else -> "Outro"
    }

    fun nomeCategoria(c: String): String = when (c) {
        CategoriaGasto.ENERGIA -> "⚡ Energia (recarga)"
        CategoriaGasto.MANUTENCAO -> "🔧 Manutenção"
        else -> "Outro gasto"
    }

    fun kwh(v: Double): String = String.format(ptBR, if (v % 1.0 == 0.0) "%.0f" else "%.1f", v)
}

/** Texto de uma linha que diminui a fonte (até [minSp]) para caber na largura, sem cortar valores. */
class TextoAjustavel(ctx: Context, private val maxSp: Float, private val minSp: Float) : TextView(ctx) {
    private val sd = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, ctx.resources.displayMetrics)
    init { maxLines = 1; isSingleLine = true; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp) }
    override fun onMeasure(w: Int, h: Int) {
        if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED) {
            val disp = MeasureSpec.getSize(w) - paddingLeft - paddingRight
            var px = maxSp * sd
            val p = android.text.TextPaint(paint)
            p.textSize = px
            val t = text.toString()
            while (px > minSp * sd && p.measureText(t) > disp) { px -= sd; p.textSize = px }
            if (textSize != px) super.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
        }
        super.onMeasure(w, h)
    }
}

/** Construtores de views no estilo do app. */
class Ui(val ctx: Context) {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    fun dpf(v: Float) = v * d

    fun text(s: CharSequence = "", size: Float = 16f, color: Int = Cores.BRANCO, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun ajustavel(s: String, maxSp: Float, minSp: Float, color: Int = Cores.BRANCO, bold: Boolean = true) =
        TextoAjustavel(ctx, maxSp, minSp).apply {
            text = s; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun titulo(s: String) = text(s.uppercase(), 13f, Cores.VIOLETA, bold = true).apply {
        letterSpacing = 0.08f
        ViewCompat.setAccessibilityHeading(this, true)
        contentDescription = s
    }

    fun mono(s: String, size: Float, color: Int) = text(s, size, color, bold = true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun truncar(tv: TextView, linhas: Int) = tv.apply { maxLines = linhas; ellipsize = TextUtils.TruncateAt.END }

    fun vbox(padding: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding)
    }

    fun hbox() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun fundo(cor: Int, borda: Int? = null, raio: Float = 16f, bordaDp: Int = 1) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dpf(raio)
        if (borda != null) setStroke(dp(bordaDp), borda)
    }

    /** Card com gradiente sutil (violeta 14% -> 6% sobre obsidian) e borda violeta fraca. */
    fun card(destaque: Boolean = false): LinearLayout = vbox(dp(16)).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, if (destaque) 0.20f else 0.14f), Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.05f)),
        ).apply {
            cornerRadius = dpf(20f)
            setStroke(dp(1), Cores.alpha(Cores.VIOLETA, if (destaque) 0.55f else 0.25f))
        }
    }

    fun icone(res: Int, cor: Int, tamanho: Int = 20): ImageView = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(cor)
        layoutParams = LinearLayout.LayoutParams(dp(tamanho), dp(tamanho))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Título de card com ícone. */
    fun tituloIcone(res: Int, s: String, cor: Int = Cores.VIOLETA): LinearLayout = hbox().apply {
        addView(icone(res, cor, 18))
        addView(titulo(s), wc(dp(8)))
    }

    /** Botão só com ícone (48dp), com descrição para o TalkBack. */
    fun botaoIcone(res: Int, descricao: String, cor: Int = Cores.BRANCO, onClick: (View) -> Unit): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(cor)
            contentDescription = descricao
            background = RippleDrawable(ColorStateList.valueOf(Cores.alpha(Cores.BRANCO, 0.25f)), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            minimumWidth = dp(48); minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener(onClick)
        }

    /** Coloca um ícone à esquerda do texto do botão. */
    fun comIcone(b: Button, res: Int): Button = b.apply {
        val dr = ctx.getDrawable(res)?.mutate()
        dr?.setBounds(0, 0, dp(20), dp(20))
        dr?.setTint(currentTextColor)
        setCompoundDrawablesRelative(dr, null, null, null)
        compoundDrawablePadding = dp(8)
    }

    enum class Estilo { CHEIO_VIOLETA, CHEIO_ESMERALDA, CONTORNO, TEXTO }

    fun botao(s: String, estilo: Estilo = Estilo.CONTORNO, alto: Boolean = false, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = s; isAllCaps = false; textSize = if (alto) 17f else 15f
            stateListAnimator = null
            minHeight = dp(if (alto) 56 else 48); minimumHeight = minHeight
            minWidth = dp(48); minimumWidth = minWidth
            setPadding(dp(14), 0, dp(14), 0)
            val (bg, fg) = when (estilo) {
                Estilo.CHEIO_VIOLETA -> fundo(Cores.VIOLETA, raio = 14f) to Cores.OBSIDIAN
                Estilo.CHEIO_ESMERALDA -> fundo(Cores.ESMERALDA, raio = 14f) to Cores.OBSIDIAN
                Estilo.CONTORNO -> fundo(Color.TRANSPARENT, Cores.alpha(Cores.VIOLETA, 0.8f), 14f) to Cores.BRANCO
                Estilo.TEXTO -> fundo(Color.TRANSPARENT, raio = 14f) to Cores.VIOLETA
            }
            setTextColor(fg)
            if (estilo != Estilo.CONTORNO && estilo != Estilo.TEXTO) setTypeface(typeface, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Cores.alpha(Cores.BRANCO, 0.25f)), bg, null)
            setOnClickListener(onClick)
        }

    fun selo(s: String, cor: Int) = text(s, 13f, cor, bold = true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = fundo(Cores.alpha(cor, 0.12f), Cores.alpha(cor, 0.6f), 12f)
    }

    fun divisor() = View(ctx).apply {
        setBackgroundColor(Cores.alpha(Cores.BRANCO, 0.12f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        }
    }

    fun mp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = top }

    fun wc(left: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = left }

    fun peso(w: Float, left: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        .apply { leftMargin = left }

    fun espaco() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
}
