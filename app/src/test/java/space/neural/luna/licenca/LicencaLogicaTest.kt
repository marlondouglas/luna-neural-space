package space.neural.luna.licenca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.licenca.LicencaLogica.Ancora
import space.neural.luna.licenca.LicencaLogica.Cache
import space.neural.luna.licenca.LicencaLogica.Decisao
import space.neural.luna.licenca.LicencaLogica.Payload
import space.neural.luna.licenca.LicencaLogica.Relogio

/** Regras da licença no aparelho: dias de teste, vencido, pago, offline (48 h) e data alterada. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class) // org.json real
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class LicencaLogicaTest {
    private val H = 3_600_000L
    private val D = LicencaLogica.DIA_MS
    private val T0 = 1_792_324_800_000L // horário do servidor na resposta
    private val BOOT_E = 5_000_000L // elapsedRealtime na resposta

    private fun payload(status: String = "trial", trialStart: Long = T0, paidUntil: Long = 0, minVer: Int = 106, uid: String = "u1", device: String = "dev") =
        Payload(status, uid, device, "K7M2QX", trialStart, trialStart + 7 * D, paidUntil, T0, 48 * H, minVer, "https://dl", "nonce")

    private fun cache(p: Payload = payload()) = Cache(p, Ancora(T0, BOOT_E, 3))
    /** Mesmo boot, [passou] ms depois da resposta, relógio de parede = [parede]. */
    private fun r(passou: Long, parede: Long = T0 + passou, boot: Int = 3, desdeBoot: Long = BOOT_E + passou) = Relogio(parede, desdeBoot, boot)
    private fun decidir(c: Cache?, rel: Relogio, marca: Ancora? = null, ver: Int = 106, uid: String = "u1", device: String = "dev") =
        LicencaLogica.decidir(c, marca, rel, uid, device, ver)

    @Test fun testeMostraDiasRestantes() {
        assertEquals(Decisao.Teste(7, T0 + 7 * D, "K7M2QX"), decidir(cache(), r(0)))
        assertEquals(7, (decidir(cache(), r(1)) as Decisao.Teste).diasRestantes)
        // resposta recebida no dia 5 do teste: faltam 2
        val c = cache(payload(trialStart = T0 - 5 * D))
        assertEquals(2, (decidir(c, r(H)) as Decisao.Teste).diasRestantes)
        // falta menos de 1 dia -> "1 dia"
        val c2 = cache(payload(trialStart = T0 - 6 * D - 20 * H))
        assertEquals(1, (decidir(c2, r(0)) as Decisao.Teste).diasRestantes)
    }

    @Test fun testeVencidoBloqueiaMesmoOffline() {
        val c = cache(payload(trialStart = T0 - 6 * D - 23 * H))
        assertTrue(decidir(c, r(30 * 60_000)) is Decisao.Teste)
        assertEquals(Decisao.Expirado("K7M2QX"), decidir(c, r(2 * H)))
        // muito tempo depois (offline): continua bloqueado como vencido, não como "sem conexão"
        assertEquals(Decisao.Expirado("K7M2QX"), decidir(c, r(10 * D)))
        // servidor já disse expirado
        assertTrue(decidir(cache(payload(status = "expired", trialStart = T0 - 8 * D)), r(0)) is Decisao.Expirado)
    }

    @Test fun pago() {
        val c = cache(payload(status = "active", trialStart = T0 - 20 * D, paidUntil = T0 + 30 * D))
        val d = decidir(c, r(H)) as Decisao.Pago
        assertEquals(30, d.diasRestantes)
        assertTrue(d.liberado)
        // assinatura vence (offline não importa: vencido bloqueia)
        assertEquals(Decisao.Expirado("K7M2QX"), decidir(c, r(31 * D)))
    }

    @Test fun pagoDuranteTesteVaiAtePagoAte() {
        val c = cache(payload(status = "active", trialStart = T0 - D, paidUntil = T0 + 31 * D))
        assertTrue(decidir(c, r(H)) is Decisao.Pago)
    }

    @Test fun graceOfflineDe48Horas() {
        val c = cache(payload(status = "active", trialStart = T0 - 20 * D, paidUntil = T0 + 30 * D))
        assertTrue(decidir(c, r(47 * H)) is Decisao.Pago)
        assertEquals(Decisao.SemConexao(false), decidir(c, r(49 * H)))
        // em teste também
        assertTrue(decidir(cache(), r(47 * H)) is Decisao.Teste)
        assertEquals(Decisao.SemConexao(false), decidir(cache(), r(48 * H + 1)))
    }

    @Test fun atrasarAData_mesmoBoot_naoAjuda() {
        // 3 dias de teste usados offline? Não: depois de 48 h exige internet, e a data atrasada é detectada.
        val c = cache(payload(trialStart = T0 - 6 * D))
        // usuário volta a data 5 dias, mas o elapsedRealtime continua contando: venceu
        assertEquals(Decisao.Expirado("K7M2QX"), decidir(c, r(passou = 25 * H, parede = T0 - 5 * D)))
        // dentro do teste, data atrasada -> "confira a data" (exige internet)
        assertEquals(Decisao.SemConexao(true), decidir(cache(), r(passou = H, parede = T0 - 2 * D)))
    }

    @Test fun adiantarADataNaoEstendeNada() {
        // relógio adiantado não muda nada no mesmo boot (usa o tempo do servidor + elapsed)
        val d = decidir(cache(), r(passou = H, parede = T0 + 100 * D)) as Decisao.Teste
        assertEquals(7, d.diasRestantes)
    }

    @Test fun reiniciarECelularComDataAtrasada() {
        val c = cache(payload(trialStart = T0 - 3 * D))
        // usou 30 h (marca salva), reiniciou, voltou a data para antes da resposta
        val marca = Ancora(T0 + 30 * H, BOOT_E + 30 * H, 3)
        val depoisDoBoot = Relogio(parede = T0 - 3 * D, desdeBoot = 60_000, boot = 4)
        assertEquals(Decisao.SemConexao(true), decidir(c, depoisDoBoot, marca))
        // reiniciou com a data certa: conta pela parede, nunca antes da marca
        val ok = Relogio(parede = T0 + 31 * H, desdeBoot = 60_000, boot = 4)
        assertTrue(decidir(c, ok, marca) is Decisao.Teste)
        // e o grace continua valendo depois do boot
        val tarde = Relogio(parede = T0 + 50 * H, desdeBoot = 60_000, boot = 4)
        assertEquals(Decisao.SemConexao(false), decidir(c, tarde, marca))
    }

    @Test fun marcaAvancaComOBootNovo() {
        val c = cache()
        val m1 = LicencaLogica.novaMarca(c, null, Relogio(T0 + 10 * H, 1_000, 4)) // boot novo: usa a parede
        assertEquals(T0 + 10 * H, m1.tempo)
        // no mesmo boot novo, avança pelo elapsed mesmo que a parede volte
        val (agora, alterado) = LicencaLogica.agoraConfiavel(c, m1, Relogio(T0, 1_000 + 5 * H, 4))
        assertEquals(T0 + 15 * H, agora)
        assertTrue(alterado)
    }

    @Test fun pequenaDiferencaDeRelogioNaoEhFraude() {
        assertTrue(decidir(cache(), r(passou = H, parede = T0 + H - 5 * 60_000)) is Decisao.Teste)
    }

    @Test fun semRespostaDoServidorPedeConexao() {
        assertEquals(Decisao.SemConexao(false), decidir(null, r(0)))
    }

    @Test fun licencaDeOutraContaOuAparelhoNaoVale() {
        assertEquals(Decisao.SemConexao(false), decidir(cache(), r(0), uid = "outra"))
        assertEquals(Decisao.SemConexao(false), decidir(cache(), r(0), device = "outro"))
    }

    @Test fun versaoAntigaPrecisaAtualizar() {
        assertEquals(Decisao.Atualizar("https://dl"), decidir(cache(payload(minVer = 107)), r(0), ver = 106))
        assertTrue(decidir(cache(payload(minVer = 106)), r(0), ver = 106) is Decisao.Teste)
    }

    @Test fun limiteDeAparelhos() {
        assertEquals(Decisao.LimiteAparelhos, decidir(cache(payload(status = "device_limit")), r(0)))
        assertFalse(Decisao.LimiteAparelhos.liberado)
    }

    @Test fun respostaDoServidorConfereNonceContaEAparelho() {
        val p = payload()
        assertTrue(LicencaLogica.respostaValida(p, "nonce", "u1", "dev"))
        assertFalse(LicencaLogica.respostaValida(p, "outro", "u1", "dev")) // resposta antiga reaproveitada
        assertFalse(LicencaLogica.respostaValida(p, "nonce", "u2", "dev"))
        assertFalse(LicencaLogica.respostaValida(p, "nonce", "u1", "dev2"))
    }

    @Test fun lePayloadDoServidor() {
        val json = """{"v":1,"status":"trial","uid":"u1","device":"dev","code":"K7M2QX","trialStart":1,"trialEndsAt":2,"paidUntil":0,"serverTime":1,"graceMs":3,"minVersionCode":106,"downloadUrl":"https://x","nonce":"n"}"""
        val p = Payload.ler(json)!!
        assertEquals("K7M2QX", p.code); assertEquals(106, p.minVersionCode)
        assertNull(Payload.ler(json.replace("\"v\":1", "\"v\":2")))
        assertNull(Payload.ler("lixo"))
    }

    @Test fun hashDoAparelhoIgualAoDoWorker() {
        // mesmo valor do teste do Worker (deviceHash("9774d56d682e549c"))
        assertEquals(Licenca.deviceHash("9774D56D682E549C "), Licenca.deviceHash("9774d56d682e549c"))
        assertEquals("e3dc8b138541224265d48963eaf8cee0", Licenca.deviceHash("9774d56d682e549c"))
        assertEquals(32, Licenca.deviceHash("abc12345").length)
    }
}
