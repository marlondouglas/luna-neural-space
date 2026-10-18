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
    // Acentos neon (v0.1.4)
    const val MAGENTA = 0xFFFF4FD8.toInt()
    const val LARANJA = 0xFFFF9F43.toInt()
    /** Só para variação negativa (pílula ▼) e valores abaixo de zero, sempre com ícone/sinal. */
    const val CORAL = 0xFFFF5C7A.toInt()
    /** Amarelo neon (corridas particulares). */
    const val AMARELO = 0xFFFFE15A.toInt()

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
        AppCorrida.GOGETA -> "Gogeta"
        AppCorrida.PARTICULAR -> "Particular"
        else -> "Outro"
    }

    /** Sigla do selo de cada fonte de ganho. */
    fun siglaApp(app: String): String = when (app) {
        AppCorrida.NOVENTA_E_NOVE -> "99"
        AppCorrida.UBER -> "U"
        AppCorrida.INDRIVE -> "iD"
        AppCorrida.GOGETA -> "G"
        AppCorrida.PARTICULAR -> "P"
        else -> "?"
    }

    fun nomeCategoria(c: String): String = when (c) {
        CategoriaGasto.ENERGIA -> "⚡ Energia (recarga)"
        CategoriaGasto.MANUTENCAO -> "🔧 Manutenção"
        else -> "Outro gasto"
    }

    /** 12.4 -> "12,4%" */
    fun pct(v: Double): String = String.format(ptBR, if (v >= 100) "%.0f%%" else "%.1f%%", v)

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

    /** Card de vidro com profundidade (brilho interno, bisel, borda neon e sombra colorida). */
    fun card(destaque: Boolean = false, acento: Int = Cores.VIOLETA, colorido: Boolean = false): LinearLayout = vbox(dp(16)).apply {
        background = VidroDrawable(d, acento, destaque, colorido)
        elevation = dpf(if (destaque) 14f else 8f)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = acento; outlineAmbientShadowColor = acento
        }
    }

    /** Pílula de variação: ▲ +12,4% (esmeralda) / ▼ −5,0% (coral) / neutra. */
    fun pilula(variacao: Double?, sufixo: String): TextView {
        val (txt, cor) = when {
            variacao == null -> "● novo $sufixo" to Cores.BRANCO_70
            variacao >= 0.05 -> "▲ +${Fmt.pct(variacao)} $sufixo" to Cores.ESMERALDA
            variacao <= -0.05 -> "▼ −${Fmt.pct(-variacao)} $sufixo" to Cores.CORAL
            else -> "● 0% $sufixo" to Cores.BRANCO_70
        }
        return text(txt, 11f, cor, bold = true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 1
            background = fundo(Cores.alpha(cor, 0.14f), Cores.alpha(cor, 0.55f), 10f)
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
                Estilo.CHEIO_VIOLETA -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Cores.mix(Cores.VIOLETA, Cores.BRANCO, 0.15f), Cores.mix(Cores.VIOLETA, Cores.MAGENTA, 0.45f))).apply {
                    cornerRadius = dpf(14f); setStroke(dp(1), Cores.alpha(Cores.BRANCO, 0.35f))
                } to Cores.OBSIDIAN
                Estilo.CHEIO_ESMERALDA -> fundo(Cores.ESMERALDA, raio = 14f) to Cores.OBSIDIAN
                Estilo.CONTORNO -> fundo(Color.TRANSPARENT, Cores.alpha(Cores.VIOLETA, 0.8f), 14f) to Cores.BRANCO
                Estilo.TEXTO -> fundo(Color.TRANSPARENT, raio = 14f) to Cores.VIOLETA
            }
            setTextColor(fg)
            if (estilo != Estilo.CONTORNO && estilo != Estilo.TEXTO) setTypeface(typeface, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Cores.alpha(Cores.BRANCO, 0.25f)), bg, null)
            if (estilo == Estilo.CHEIO_VIOLETA || estilo == Estilo.CHEIO_ESMERALDA) {
                elevation = dpf(8f)
                if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = if (estilo == Estilo.CHEIO_VIOLETA) Cores.MAGENTA else Cores.ESMERALDA; outlineAmbientShadowColor = outlineSpotShadowColor }
            }
            maxLines = 1
            setOnClickListener(onClick)
        }

    /** Selo redondo com a sigla e a cor neon da fonte de ganho (99, Uber, InDrive, Gogeta, Particular). */
    fun seloApp(app: String, tamanho: Int = 26): TextView = text(Fmt.siglaApp(app), if (tamanho >= 26) 11f else 9f, Cores.OBSIDIAN, bold = true).apply {
        gravity = Gravity.CENTER
        val cor = CoresSerie.app(app)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Cores.mix(cor, Cores.BRANCO, 0.35f), cor)).apply { shape = GradientDrawable.OVAL }
        layoutParams = LinearLayout.LayoutParams(dp(tamanho), dp(tamanho))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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

/**
 * Fundo de card em "vidro": gradiente vertical translúcido, brilho interno no topo,
 * bisel escuro embaixo e borda neon em degradê. Shaders refeitos só quando o tamanho muda.
 */
class VidroDrawable(
    private val d: Float, private val acento: Int, private val destaque: Boolean,
    /** Card "colorido": corpo preenchido com degradê do acento (não só a borda). */
    private val colorido: Boolean = false,
) : android.graphics.drawable.Drawable() {
    private val raio = 20 * d
    private val r = android.graphics.RectF()
    private val corpo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val brilho = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val borda = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f * d }
    private val bisel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d }
    private val reflexo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val clip = android.graphics.Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val t = android.graphics.Shader.TileMode.CLAMP
        corpo.shader = if (colorido) android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Cores.alpha(Cores.mix(Cores.OBSIDIAN, acento, 0.62f), 0.95f), Cores.alpha(Cores.mix(Cores.OBSIDIAN, Cores.mix(acento, Cores.VIOLETA, 0.6f), 0.38f), 0.95f),
                Cores.alpha(Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.22f), 0.95f)), floatArrayOf(0f, 0.55f, 1f), t)
        else android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(Cores.alpha(Cores.mix(Cores.OBSIDIAN, acento, if (destaque) 0.26f else 0.18f), 0.92f),
                Cores.alpha(Cores.mix(Cores.OBSIDIAN, acento, 0.06f), 0.92f)), null, t)
        brilho.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.top + h * 0.45f,
            intArrayOf(Cores.alpha(Cores.BRANCO, 0.30f), Cores.alpha(Cores.BRANCO, 0f)), null, t)
        bisel.shader = android.graphics.LinearGradient(0f, b.bottom - h * 0.25f, 0f, b.bottom.toFloat(),
            intArrayOf(0, Cores.alpha(Cores.OBSIDIAN, 0.85f)), null, t)
        borda.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.left + w, b.bottom.toFloat(),
            intArrayOf(Cores.alpha(acento, if (destaque) 0.9f else 0.55f), Cores.alpha(Cores.CIANO, 0.15f), Cores.alpha(Cores.MAGENTA, if (destaque) 0.5f else 0.25f)), null, t)
        reflexo.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.left + w * 0.6f, b.top + h * 0.6f,
            intArrayOf(Cores.alpha(Cores.BRANCO, 0.07f), Cores.alpha(Cores.BRANCO, 0f)), null, t)
        path.reset(); path.moveTo(b.left.toFloat(), b.top.toFloat()); path.lineTo(b.left + w * 0.55f, b.top.toFloat())
        path.lineTo(b.left.toFloat(), b.top + h * 0.55f); path.close()
        clip.reset(); clip.addRoundRect(b.left + d, b.top + d, b.right - d, b.bottom - d, raio, raio, android.graphics.Path.Direction.CW)
    }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        r.set(b.left + d, b.top + d, b.right - d, b.bottom - d)
        c.drawRoundRect(r, raio, raio, corpo)
        c.save(); c.clipPath(clip)
        c.drawPath(path, reflexo)
        c.restore()
        c.drawRoundRect(r, raio, raio, bisel)
        c.drawRoundRect(r, raio, raio, brilho)
        c.drawRoundRect(r, raio, raio, borda)
    }

    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, raio); outline.alpha = 0.6f }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Fundo da tela: obsidian com brilhos neon suaves (violeta, ciano, magenta). Estático. */
class FundoNeon : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var g1: android.graphics.Shader? = null
    private var g2: android.graphics.Shader? = null
    private var g3: android.graphics.Shader? = null
    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat(); val t = android.graphics.Shader.TileMode.CLAMP
        g1 = android.graphics.RadialGradient(w * 0.1f, 0f, maxOf(w, h) * 0.6f, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.22f), 0), null, t)
        g2 = android.graphics.RadialGradient(w, h * 0.55f, maxOf(w, h) * 0.5f, intArrayOf(Cores.alpha(Cores.CIANO, 0.10f), 0), null, t)
        g3 = android.graphics.RadialGradient(w * 0.4f, h, maxOf(w, h) * 0.5f, intArrayOf(Cores.alpha(Cores.MAGENTA, 0.09f), 0), null, t)
    }
    override fun draw(c: android.graphics.Canvas) {
        c.drawColor(Cores.OBSIDIAN)
        for (g in listOf(g1, g2, g3)) { p.shader = g; c.drawRect(bounds, p) }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
