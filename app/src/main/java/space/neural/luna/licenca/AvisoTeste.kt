package space.neural.luna.licenca

import android.Manifest
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import space.neural.luna.R
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Avisos de fim do teste grátis: faltando 2 dias, amanhã e hoje (dias do calendário no fuso do aparelho,
 * contados a partir do "agora confiável" do servidor). Uma janela no app e uma notificação por limiar.
 */
object AvisoTeste {
    /** Limiares: 2 = acaba depois de amanhã, 1 = amanhã, 0 = hoje. */
    val LIMIARES = listOf(2, 1, 0)
    /** Hora local da notificação de cada limiar. */
    val HORA_NOTIFICACAO: LocalTime = LocalTime.of(9, 0)

    private const val PREFS = "avisos_teste"
    const val CANAL = "assinatura"

    /** Dias de calendário até o fim (0 = hoje); null se já acabou ou faltam mais de 2 dias. */
    fun limiar(agora: Long, terminaEm: Long, zona: ZoneId): Int? {
        if (terminaEm <= agora) return null
        val hoje = Instant.ofEpochMilli(agora).atZone(zona).toLocalDate()
        val fim = Instant.ofEpochMilli(terminaEm).atZone(zona).toLocalDate()
        val d = ChronoUnit.DAYS.between(hoje, fim).toInt()
        return d.takeIf { it in 0..2 }
    }

    fun hora(terminaEm: Long, zona: ZoneId): String = Instant.ofEpochMilli(terminaEm).atZone(zona).format(DateTimeFormatter.ofPattern("HH:mm"))

    fun titulo(l: Int): String = when (l) {
        0 -> "Seu teste grátis acaba hoje"
        1 -> "Seu teste grátis acaba amanhã"
        else -> "Seu teste grátis acaba em 2 dias"
    }

    fun texto(l: Int, terminaEm: Long, zona: ZoneId): String {
        val h = hora(terminaEm, zona)
        val quando = when (l) { 0 -> "hoje às $h"; 1 -> "amanhã às $h"; else -> "em 2 dias, às $h" }
        return "O teste termina $quando. Assine por R$ 7/mês para continuar com seus ganhos, metas e a Luna sem interrupção."
    }

    /** Texto curto do aviso verde da tela inicial nos últimos dias. */
    fun textoBanner(l: Int, terminaEm: Long, zona: ZoneId): String = when (l) {
        0 -> "Teste grátis acaba hoje às ${hora(terminaEm, zona)}  ·  Assinar R$ 7/mês ›"
        1 -> "Teste grátis acaba amanhã  ·  Assinar R$ 7/mês ›"
        else -> "Teste grátis acaba em 2 dias  ·  Assinar R$ 7/mês ›"
    }

    /**
     * Quando notificar cada limiar: 9h do dia (fim − L), nunca depois de 1 h antes do fim.
     * Só os momentos ainda no futuro em relação a [agora].
     */
    fun momentos(agora: Long, terminaEm: Long, zona: ZoneId): Map<Int, Long> {
        val fim = Instant.ofEpochMilli(terminaEm).atZone(zona).toLocalDate()
        return LIMIARES.associateWith { l ->
            val m = fim.minusDays(l.toLong()).atTime(HORA_NOTIFICACAO).atZone(zona).toInstant().toEpochMilli()
            minOf(m, terminaEm - 3_600_000L)
        }.filterValues { it > agora }
    }

    private fun chave(tipo: String, terminaEm: Long, l: Int) = "${tipo}_${terminaEm}_$l"

    /** Janela do limiar atual ainda não vista? Marca como vista. */
    fun janelaPendente(ctx: Context, agora: Long, terminaEm: Long, zona: ZoneId = ZoneId.systemDefault()): Int? {
        val l = limiar(agora, terminaEm, zona) ?: return null
        val p = ctx.getSharedPreferences(PREFS, 0)
        val k = chave("janela", terminaEm, l)
        if (p.getBoolean(k, false)) return null
        p.edit().putBoolean(k, true).apply()
        return l
    }

    // ---------------- janela no app ----------------
    fun mostrarJanela(ctx: Context, l: Int, terminaEm: Long, zona: ZoneId = ZoneId.systemDefault()): Dialog {
        val ui = Ui(ctx)
        val d = Dialog(ctx)
        val card = ui.card(destaque = true, acento = if (l == 0) Cores.LARANJA else Cores.ESMERALDA)
        card.addView(ui.text(if (l == 0) "ÚLTIMO DIA" else "TESTE GRÁTIS", 12f, if (l == 0) Cores.LARANJA else Cores.ESMERALDA, bold = true).apply { letterSpacing = 0.08f })
        card.addView(ui.text(titulo(l), 22f, Cores.BRANCO, bold = true), ui.mp(ui.dp(6)))
        card.addView(ui.text(texto(l, terminaEm, zona), 16f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        card.addView(ui.botao("Assinar R$ 7/mês", Ui.Estilo.CHEIO_ESMERALDA, alto = true) { d.dismiss(); assinar(ctx) }, ui.mp(ui.dp(20)))
        card.addView(ui.botao("Depois", Ui.Estilo.TEXTO) { d.dismiss() }, ui.mp(ui.dp(6)))
        val moldura = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20)); addView(card, LinearLayout.LayoutParams(minOf(ui.dp(440), ctx.resources.displayMetrics.widthPixels - ui.dp(40)), -2)) }
        d.setContentView(moldura)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
        return d
    }

    /** Abre o link de pagamento (ou a tela de assinatura, se não houver link). */
    fun assinar(ctx: Context) {
        val url = Licenca.checkoutUrl(ctx)
        if (url.isNotEmpty() && runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess) return
        LicencaActivity.assinar(ctx)
    }

    // ---------------- notificações ----------------
    /** Agenda (substitui) as notificações dos limiares a partir do fim do teste informado pelo servidor. */
    fun agendar(ctx: Context, agora: Long, terminaEm: Long, zona: ZoneId = ZoneId.systemDefault()) {
        val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
        val m = momentos(agora, terminaEm, zona)
        LIMIARES.forEach { l ->
            val nome = "aviso_teste_$l"
            val quando = m[l]
            if (quando == null) wm.cancelUniqueWork(nome)
            else wm.enqueueUniqueWork(nome, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NotificacaoWorker>()
                    .setInitialDelay(quando - agora, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("limiar" to l, "fim" to terminaEm))
                    .build())
        }
    }

    fun cancelar(ctx: Context) {
        val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
        LIMIARES.forEach { wm.cancelUniqueWork("aviso_teste_$it") }
    }

    fun podeNotificar(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificar(ctx: Context, l: Int, terminaEm: Long, zona: ZoneId = ZoneId.systemDefault()) {
        if (!podeNotificar(ctx)) return
        val p = ctx.getSharedPreferences(PREFS, 0)
        val k = chave("notif", terminaEm, l)
        if (p.getBoolean(k, false)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Teste grátis e assinatura", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val abrir = PendingIntent.getActivity(ctx, 700 + l,
            Intent(ctx, LicencaActivity::class.java).putExtra(LicencaActivity.EXTRA_ASSINAR, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(titulo(l))
            .setContentText("Toque para assinar por R$ 7/mês e continuar sem interrupção.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto(l, terminaEm, zona)))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(7100 + l, n) }
        p.edit().putBoolean(k, true).apply()
    }

    /** Notificação de um limiar: só se o teste ainda for o mesmo e não houver assinatura. */
    class NotificacaoWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val l = inputData.getInt("limiar", -1)
            val fim = inputData.getLong("fim", 0)
            if (l < 0 || fim <= 0) return Result.success()
            val d = runCatching { Licenca.decidir(applicationContext) }.getOrNull()
            if (d is LicencaLogica.Decisao.Teste && d.terminaEm == fim) notificar(applicationContext, l, fim)
            return Result.success()
        }
    }

    // ---------------- permissão (Android 13+) ----------------
    /** Pergunta amigável antes do pedido do sistema; uma vez só. */
    fun deveExplicarPermissao(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || podeNotificar(ctx)) return false
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (p.getBoolean("perm_perguntada", false)) return false
        p.edit().putBoolean("perm_perguntada", true).apply()
        return true
    }

}
