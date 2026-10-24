package space.neural.luna.pages

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.dashboard.*
import space.neural.luna.partners.*

/** Real catalog rendered with the reference hierarchy; commercial state is never inferred from artwork. */
class ParceirosActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var controls: DrivingControls
    private lateinit var cards: LinearLayout
    private lateinit var favorites: PartnerFavorites
    private var query = ""
    private var category: PartnerCategory? = null
    private var onlyFavorites = false
    private val filters = mutableMapOf<PartnerCategory?, TextView>()
    private var nearbyPosition: PartnerPosition? = null
    private val wide get() = resources.configuration.screenWidthDp >= 840 && resources.configuration.fontScale < 1.6f
    private val grid get() = resources.configuration.screenWidthDp >= 1120 && resources.configuration.fontScale < 1.4f
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); favorites = PartnerFavorites(this)
        controls = DrivingControls(this, "parceiros", { }, ::navigate, { }, { }) { }
        val root = ui.vbox().apply { background = RotaDesign.panel(ui) }
        root.addView(controls.header, ui.mp())
        val body = ui.hbox().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8)); gravity = Gravity.TOP }
        cards = ui.vbox()
        val catalog = ScrollView(this).apply { addView(cards); isVerticalScrollBarEnabled = true }
        if (wide) {
            val left = (resources.configuration.screenWidthDp * .235f).toInt().coerceIn(210, 330)
            val right = (resources.configuration.screenWidthDp * .255f).toInt().coerceIn(225, 350)
            body.addView(buildFilters(), LinearLayout.LayoutParams(ui.dp(left), -1))
            body.addView(catalog, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(12) })
            body.addView(buildHighlights(), LinearLayout.LayoutParams(ui.dp(right), -1))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            catalog.removeView(cards)
            val content = ui.vbox(ui.dp(10))
            val filterView = buildFilters().apply { visibility = View.GONE }
            content.addView(ui.botao("Buscar e filtrar parceiros ▾", Ui.Estilo.CONTORNO) {
                filterView.visibility = if (filterView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }, ui.mp())
            content.addView(filterView, ui.mp())
            content.addView(cards, ui.mp(ui.dp(12)))
            root.addView(ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(controls.dock, ui.mp(ui.dp(4)))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        render()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val latest = lastAuthorizedPosition()
                if (latest != nearbyPosition) { nearbyPosition = latest; render() }
                delay(15_000)
            }
        } }
    }
    /** Read an already-authorized recent fix. This screen neither requests permission nor starts GPS. */
    private fun lastAuthorizedPosition(): PartnerPosition? {
        val allowed = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!allowed) return null
        val manager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return runCatching { manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.hasAccuracy() && System.currentTimeMillis() - it.time in 0..60_000 && it.accuracy in 0f..100f }
            .maxByOrNull { it.time }?.let { PartnerPosition(it.latitude, it.longitude, it.time, it.accuracy) } }.getOrNull()
    }
    private fun buildFilters(): View {
        val box = RotaDesign.panelBox(ui)
        box.addView(ui.hbox().apply { addView(ui.icone(R.drawable.ic_partners, Cores.MAGENTA)); addView(ui.text("Buscar parceiros", 17f, Cores.BRANCO, true), ui.wc(ui.dp(8))) })
        box.addView(EditText(this).apply {
            hint = "Nome ou categoria…"; textSize = 15f; setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = RotaDesign.panel(ui)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); render() }
            })
        }, ui.mp(ui.dp(12)))
        val list = ui.vbox()
        list.addView(ui.text("Categorias", 15f, Cores.BRANCO_70, true), ui.mp(ui.dp(12)))
        val all = listOf<Pair<PartnerCategory?, String>>(null to "Todos os parceiros") + PartnerCategory.entries.map { it to it.label }
        val row = if (wide) ui.vbox() else ui.hbox()
        all.forEach { (value, label) ->
            val item = ui.text(label, 15f, Cores.BRANCO).apply {
                minimumHeight = ui.dp(48); gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.fundo(Cores.ELEVADA, raio = 12f)
                setOnClickListener { category = value; onlyFavorites = false; render() }
            }
            filters[value] = item
            row.addView(item, if (wide) ui.mp(ui.dp(5)) else ui.wc(ui.dp(5)))
        }
        list.addView(if (wide) row else RotaDesign.scrollRow(ui, row))
        list.addView(ui.botao("♡ Meus favoritos", Ui.Estilo.CONTORNO) { onlyFavorites = !onlyFavorites; render() }, ui.mp(ui.dp(12)))
        if (wide) box.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f)) else box.addView(list)
        box.addView(ui.comIcone(ui.botao("Ver parceiros no mapa", Ui.Estilo.CHEIO_VIOLETA) {
            startActivity(Intent(this, MapRadioActivity::class.java).putExtra("LAYER", "PARTNERS"))
        }, R.drawable.ic_send), ui.mp(ui.dp(12)))
        return box
    }
    private fun buildHighlights(): View {
        val right = ui.vbox()
        val highlights = RotaDesign.panelBox(ui)
        highlights.addView(ui.text("EM DESTAQUE", 15f, Cores.BRANCO_70, true))
        PartnerCatalog.partners.take(3).forEach { item ->
            highlights.addView(ui.hbox().apply {
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                addView(ui.icone(if (item.category == PartnerCategory.CHARGING) R.drawable.ic_bolt else R.drawable.ic_partners, Cores.CIANO, 28))
                addView(ui.vbox().apply { addView(ui.text(item.name, 14f, Cores.BRANCO, true)); addView(ui.text("Consultar detalhes", 12f, Cores.CIANO)) }, ui.peso(1f, ui.dp(10)))
                setOnClickListener { details(item) }
            }, ui.mp(ui.dp(3)))
        }
        right.addView(highlights, ui.mp())
        val offers = RotaDesign.panelBox(ui)
        offers.addView(ui.text("OFERTAS CONFIRMADAS", 15f, Cores.CIANO, true))
        val activeOffers = PartnerCatalog.partners.filter { it.offer?.usable == true }
        if (activeOffers.isEmpty()) {
            offers.addView(ui.text("Ainda não há cupons confirmados pelos estabelecimentos. Ofertas pendentes não são anunciadas como disponíveis.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        } else activeOffers.forEach { partner ->
            offers.addView(ui.botao("${partner.name} · ${partner.offer?.description}", Ui.Estilo.CONTORNO) { details(partner) }, ui.mp(ui.dp(6)))
        }
        right.addView(offers, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(10) })
        return right
    }
    private fun render() {
        if (!::cards.isInitialized) return
        filters.forEach { (value, view) -> view.background = if (value == category && !onlyFavorites) RotaDesign.panel(ui, Cores.CIANO) else ui.fundo(Cores.ELEVADA, raio = 12f) }
        val selected = PartnerCatalog.search(query, category).filter { !onlyFavorites || favorites.contains(it.id) }
        cards.removeAllViews()
        val badges = ui.hbox()
        badges.addView(RotaDesign.badge(ui, "Parceiros ${selected.size}", Cores.CIANO), ui.wc())
        badges.addView(RotaDesign.badge(ui, "Eletropostos ${selected.count { it.category == PartnerCategory.CHARGING }}", Cores.MAGENTA), ui.wc(ui.dp(7)))
        cards.addView(RotaDesign.scrollRow(ui, badges), ui.mp(ui.dp(2)))
        if (selected.isEmpty()) cards.addView(RotaDesign.panelBox(ui).apply { addView(ui.text("Nenhum parceiro corresponde ao filtro.", 16f, Cores.BRANCO)); addView(ui.botao("Limpar filtro", Ui.Estilo.CONTORNO) { query = ""; category = null; onlyFavorites = false; render() }, ui.mp(ui.dp(10))) }, ui.mp(ui.dp(12)))
        selected.firstOrNull()?.let { cards.addView(partnerCard(it, wide), ui.mp(ui.dp(10))) }
        val remaining = selected.drop(1)
        if (grid) remaining.chunked(2).forEach { pair ->
            val row = ui.hbox().apply { gravity = Gravity.TOP }
            pair.forEachIndexed { index, item -> row.addView(partnerCard(item, false), LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = ui.dp(10) }) }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            cards.addView(row, ui.mp(ui.dp(10)))
        } else remaining.forEach { cards.addView(partnerCard(it, false), ui.mp(ui.dp(10))) }
        cards.addView(ui.text("Dados públicos, sem disponibilidade ao vivo. Abra os detalhes para fonte e condições.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
    }
    private fun partnerCard(partner: Partner, hero: Boolean): View {
        val card = RotaDesign.panelBox(ui, accent = if (hero) Cores.VIOLETA else Cores.mix(Cores.VIOLETA, Cores.CIANO, .2f))
        val title = ui.hbox().apply { gravity = Gravity.TOP }
        title.addView(ui.text(partner.name, if (hero) 18f else 16f, Cores.BRANCO, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.peso(1f))
        val heart = ui.text(if (favorites.contains(partner.id)) "♥" else "♡", 25f, Cores.MAGENTA).apply {
            minimumWidth = ui.dp(48); minimumHeight = ui.dp(48); gravity = Gravity.CENTER
            contentDescription = "Favoritar ${partner.name}"; setOnClickListener { favorites.toggle(partner.id); render() }
        }
        title.addView(heart, ui.wc())
        val imageWidth = if (hero && wide) 130 else if (wide) 76 else 80
        val row = ui.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(partnerImage(partner, imageWidth), LinearLayout.LayoutParams(ui.dp(imageWidth), ui.dp(if (hero && wide) 120 else 80)))
        row.addView(ui.vbox().apply { addView(title); addView(partnerInfo(partner, compact = !hero)) }, ui.peso(1f, ui.dp(10)))
        card.addView(row)
        card.addView(cardActions(partner), ui.mp(ui.dp(7)))
        return card
    }
    private fun partnerImage(partner: Partner, targetDp: Int): View {
        val asset = PartnerLinks.safeImageAsset(partner.imageAsset)
        if (asset != null) {
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(asset).use { BitmapFactory.decodeStream(it, null, options) }
                options.inJustDecodeBounds = false
                val target = ui.dp(targetDp).coerceAtLeast(1)
                var sample = 1
                while (options.outWidth / (sample * 2) >= target && options.outHeight / (sample * 2) >= target) sample *= 2
                options.inSampleSize = sample
                assets.open(asset).use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            if (bitmap != null) return ImageView(this).apply {
                setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
                contentDescription = "Imagem autorizada de ${partner.name}"
                background = RotaDesign.panel(ui)
            }
        }
        return PartnerArt(this, partner.category)
    }
    private fun partnerInfo(partner: Partner, compact: Boolean): View = ui.vbox().apply {
        addView(ui.text(partner.address ?: "Endereço a confirmar", 12f, Cores.BRANCO_70).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(ui.text(if (partner.category == PartnerCategory.CHARGING) "Tarifa e conectores: ver detalhes" else partner.category.label, 12f, Cores.CIANO), ui.mp(ui.dp(4)))
        PartnerDistance.meters(partner.latitude, partner.longitude, nearbyPosition, System.currentTimeMillis())?.let { meters ->
            addView(ui.text("≈ ${"%.1f".format(java.util.Locale.forLanguageTag("pt-BR"), meters / 1000)} km em linha reta", 12f, Cores.CIANO), ui.mp(ui.dp(3)))
        }
        partner.offer?.takeIf { it.usable }?.let { offer ->
            addView(ui.text(offer.priceCents?.let { "${money(it)} / ${offer.unit}" } ?: offer.description, 13f, Cores.ESMERALDA, true).apply { maxLines = 2 }, ui.mp(ui.dp(4)))
            offer.validUntil?.let { addView(ui.text("Até ${formatTime(it)}", 11f, Cores.BRANCO_70)) }
        }
        if (!compact) addView(ui.text(partner.statusLabel, 12f, Cores.BRANCO_70), ui.mp(ui.dp(4)))
    }
    private fun cardActions(partner: Partner): View = ui.hbox().apply {
        addView(ui.botao("Detalhes →", Ui.Estilo.CHEIO_VIOLETA) { details(partner) }.apply { textSize = 13f }, ui.peso(1f))
        addView(RotaDesign.roundIcon(ui, R.drawable.ic_route, "Rota até ${partner.name}") { openRoute(partner) }, ui.wc(ui.dp(7)))
    }
    private fun details(partner: Partner) {
        val message = buildString {
            append(partner.address ?: "Endereço ainda não confirmado"); append("\n\n"); append(partner.statusLabel)
            if (partner.category == PartnerCategory.CHARGING) {
                val now = System.currentTimeMillis()
                val tariff = partner.tariff?.takeIf { it.usableAt(now) }
                if (tariff == null) append("\n\nTarifa: não confirmada; consulte a operadora") else {
                    val evidence = when (tariff.state) { PartnerDataState.LIVE -> "confirmada ao vivo"; PartnerDataState.RECENT -> "confirmada recentemente"; else -> "informada pelo estabelecimento" }
                    append("\n\nTarifa $evidence: ${money(tariff.cents)} / ${tariff.unit}")
                    append("\nFonte da tarifa: ${tariff.source}\nAtualização da tarifa: ${formatTime(tariff.updatedAt)}")
                }
                if (partner.connectorReadings.isEmpty()) append("\nConectores, potência e ocupação: sem fonte autorizada")
                partner.connectorReadings.forEach { connector ->
                    val status = connector.statusAt(now)
                    append("\n\nConector ${connector.id}: ${connector.type ?: "tipo não confirmado"}")
                    if (connector.maxPowerKw?.let { it.isFinite() && it > 0 } == true) append(" · ${connector.maxPowerKw} kW informados")
                    append("\nEstado: ${connectorStatus(status)}\nFonte do conector: ${connector.source}\nAtualização do conector: ${formatTime(connector.updatedAt)}")
                }
            }
            partner.openingHours?.let { append("\n\nHorário informado: ${it.description}\nFonte do horário: ${it.source}\nAtualização do horário: ${formatTime(it.updatedAt)}\nHorário de abertura não indica conector livre.") }
            partner.offer?.let { offer ->
                append("\n\n"); append(offer.description)
                append(if (offer.usable) "\nOferta confirmada" else if (offer.state == OfferState.EXPIRED || offer.validUntil?.let { it <= System.currentTimeMillis() } == true) "\nOferta expirada" else "\nAguardando confirmação comercial")
                if (offer.usable && offer.code != null) append("\nCupom: ${offer.code}")
                if (offer.usable && offer.priceCents != null) append("\nPreço: ${money(offer.priceCents)} / ${offer.unit}")
                offer.validUntil?.let { append("\nValidade: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))}") }
                offer.source?.let { append("\nFonte da oferta: $it") }
            }
            append("\n\nFonte: "); append(partner.source)
        }
        val content = ui.vbox(ui.dp(16)).apply {
            if (PartnerLinks.safeImageAsset(partner.imageAsset) != null) addView(partnerImage(partner, 240), LinearLayout.LayoutParams(-1, ui.dp(170)))
            addView(ui.text(message, 15f, Cores.BRANCO))
            PartnerLinks.officialChargingApp(partner.id)?.let { app ->
                addView(ui.botao("Abrir aplicativo oficial da rede", Ui.Estilo.CONTORNO) { openChargingApp(app) }, ui.mp(ui.dp(8)))
            }
            listOf("WhatsApp" to PartnerLinks.whatsapp(partner.whatsapp), "Ligar" to PartnerLinks.phone(partner.phone), "Instagram" to PartnerLinks.instagram(partner.instagram)).forEach { (label, link) ->
                if (link != null) addView(ui.botao(label, Ui.Estilo.CONTORNO) { openExternal(link) }, ui.mp(ui.dp(8)))
            }
        }
        val builder = AlertDialog.Builder(this).setTitle(partner.name).setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Rota interna") { _, _ -> openRoute(partner) }.setNegativeButton("Fechar", null)
        PartnerLinks.safeSource(partner.source)?.let { source -> builder.setNeutralButton("Consultar fonte") { _, _ -> openExternal(source) } }
        builder.show()
    }
    private fun openExternal(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) }.onFailure {
            Toast.makeText(this, "Nenhum aplicativo disponível para abrir este contato ou fonte.", Toast.LENGTH_LONG).show()
        }
    }
    private fun openChargingApp(app: PartnerLinks.OfficialApp) {
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch != null) runCatching { startActivity(launch) }.onFailure { openExternal(app.playUrl) }
        else openExternal(app.playUrl)
    }
    private fun money(cents: Long) = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("pt-BR")).format(java.math.BigDecimal.valueOf(cents, 2))
    private fun formatTime(time: Long) = if (time > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(time)) else "não informada"
    private fun connectorStatus(state: space.neural.luna.charging.ConnectorState): String = when (state) {
        space.neural.luna.charging.ConnectorState.AVAILABLE -> "Livre"
        space.neural.luna.charging.ConnectorState.CHARGING -> "Carregando"
        space.neural.luna.charging.ConnectorState.OCCUPIED -> "Ocupado"
        space.neural.luna.charging.ConnectorState.RESERVED -> "Reservado"
        space.neural.luna.charging.ConnectorState.OUT_OF_ORDER -> "Fora de operação"
        space.neural.luna.charging.ConnectorState.INOPERATIVE -> "Inoperante"
        else -> "Não confirmado"
    }
    private fun openRoute(partner: Partner) {
        if (!partner.hasCoordinates && partner.address == null) { detailsWithoutRoute(partner); return }
        val intent = Intent(this, MapRadioActivity::class.java).putExtra("DESTINO", partner.address ?: partner.name)
        if (partner.hasCoordinates) intent.putExtra("LATITUDE", partner.latitude!!).putExtra("LONGITUDE", partner.longitude!!)
        startActivity(intent)
    }
    private fun detailsWithoutRoute(partner: Partner) { AlertDialog.Builder(this).setTitle(partner.name).setMessage("O estabelecimento ainda precisa confirmar seu endereço. Não vou traçar uma rota para um local presumido.").setPositiveButton("Voltar", null).show() }
    private fun mapAction(action: String) { startActivity(Intent(this, MapRadioActivity::class.java).putExtra("ACTION", action)) }
    private fun navigate(page: String) { when (page) {
        "inicio" -> startActivity(Intent(this, space.neural.luna.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        "parceiros" -> Unit
        "configuracoes" -> startActivity(Intent(this, space.neural.luna.config.ConfiguracoesActivity::class.java))
        "eletropostos" -> { category = PartnerCategory.CHARGING; render() }
        "alertas" -> mapAction("REPORT")
        "motoristas", "mapa", "radio" -> startActivity(Intent(this, MapRadioActivity::class.java))
        else -> startActivity(Intent(this, MapRadioActivity::class.java))
    } }
}

/** Neutral, code-drawn artwork. Not a photograph or an official business logo. */
class PartnerArt(context: android.content.Context, private val category: PartnerCategory) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "Ilustração de categoria, não fotografia do estabelecimento"; clipToOutline = true }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF063C59.toInt(), Cores.SUPERFICIE, 0xFF321060.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, 14f, 14f, paint); paint.shader = null
        paint.color = Cores.alpha(Cores.CIANO, .15f)
        repeat(6) { val x = w * it / 5; canvas.drawLine(x, h * .7f, w * .5f + (x - w * .5f) * 1.8f, h, paint) }
        paint.color = Cores.ELEVADA; canvas.drawRoundRect(w * .1f, h * .35f, w * .9f, h * .78f, 8f, 8f, paint)
        paint.color = Cores.CIANO; canvas.drawRoundRect(w * .07f, h * .29f, w * .93f, h * .4f, 6f, 6f, paint)
        if (category == PartnerCategory.CHARGING) {
            paint.color = Cores.OBSIDIAN; canvas.drawRoundRect(w * .35f, h * .42f, w * .6f, h * .83f, 7f, 7f, paint)
            paint.color = Cores.ESMERALDA; canvas.drawRoundRect(w * .39f, h * .46f, w * .56f, h * .62f, 3f, 3f, paint)
            paint.color = Cores.BRANCO; paint.textSize = h * .17f; paint.textAlign = Paint.Align.CENTER; canvas.drawText("ϟ", w * .475f, h * .6f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Cores.CIANO
            canvas.drawPath(Path().apply { moveTo(w * .6f, h * .57f); cubicTo(w * .83f, h * .53f, w * .83f, h * .86f, w * .68f, h * .79f) }, paint); paint.style = Paint.Style.FILL
        } else {
            paint.color = Cores.VIOLETA; canvas.drawRoundRect(w * .24f, h * .55f, w * .76f, h * .78f, 12f, 12f, paint)
            paint.color = Cores.CIANO; canvas.drawRoundRect(w * .34f, h * .47f, w * .65f, h * .6f, 6f, 6f, paint)
            paint.color = Cores.OBSIDIAN; canvas.drawCircle(w * .32f, h * .78f, h * .07f, paint); canvas.drawCircle(w * .68f, h * .78f, h * .07f, paint)
        }
        paint.color = Cores.BRANCO_70; paint.textSize = (resources.displayMetrics.scaledDensity * 10); paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("ARTE", w - 8f, h - 6f, paint)
    }
}
