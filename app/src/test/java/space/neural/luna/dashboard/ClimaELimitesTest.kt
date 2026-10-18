package space.neural.luna.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import space.neural.luna.data.local.Limite
import space.neural.luna.dashboard.ClimaTema.Tipo

class ClimaELimitesTest {
    @Test fun temaDoClima() {
        assertEquals(Tipo.SOL, ClimaTema.tipo(0, 12))
        assertEquals(Tipo.SOL, ClimaTema.tipo(1, 9))
        assertEquals(Tipo.NUBLADO, ClimaTema.tipo(3, 15))
        assertEquals(Tipo.NUBLADO, ClimaTema.tipo(45, 15))
        assertEquals(Tipo.CHUVA, ClimaTema.tipo(61, 15))
        assertEquals(Tipo.CHUVA, ClimaTema.tipo(81, 22)) // chuva ganha da noite
        assertEquals(Tipo.CHUVA, ClimaTema.tipo(95, 3))
        assertEquals(Tipo.NOITE, ClimaTema.tipo(0, 21))
        assertEquals(Tipo.NOITE, ClimaTema.tipo(3, 5))
    }

    @Test fun resumoDosLimites() {
        val sup = Plano.Envelope(Limite(nome = "Supermercado", limiteCentavos = 100_000), 72_000)
        val lan = Plano.Envelope(Limite(nome = "Lanche", limiteCentavos = 20_000), 25_000)
        val r = ResumoLimites.de(listOf(sup, lan))
        assertEquals(120_000, r.limiteCentavos)
        assertEquals(97_000, r.gastoCentavos)
        assertEquals(28_000, r.restanteCentavos) // o que passou num limite não "come" o restante do outro
        assertEquals(1, r.excedidos)
        assertEquals(0, ResumoLimites.de(listOf(sup)).excedidos)
    }
}
