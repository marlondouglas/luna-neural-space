package space.neural.luna.licenca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixBrCodeTest {
    @Test fun crcCcittFalse() = assertEquals("29B1", PixBrCode.crc16("123456789"))

    @Test fun exemploDoManualDoBancoCentral() {
        // Exemplo do Manual de Padrões para Iniciação do Pix (BR Code estático sem valor).
        val esperado = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D"
        val p = PixBrCode.payload(chave = "123e4567-e12b-12d1-a456-426655440000", valorCentavos = null, nome = "Fulano de Tal", cidade = "BRASILIA")
        assertEquals(esperado, p.replace("010211", ""))
    }

    @Test fun assinaturaRotaGv() {
        val p = PixBrCode.payload(txid = PixBrCode.txid("K7M2QX"))
        assertTrue(p.startsWith("000201"))
        assertTrue(p.contains("0014br.gov.bcb.pix0114+5533987044866"))
        assertTrue(p.contains("54047.00"))
        assertTrue(p.contains("5802BR5907ROTA GV6013GOV VALADARES"))
        assertTrue(p.contains("62130509RGVK7M2QX"))
        assertEquals(PixBrCode.crc16(p.dropLast(4)), p.takeLast(4))
        // todos os campos TLV com tamanho correto
        var i = 0
        while (i < p.length) {
            val n = p.substring(i + 2, i + 4).toInt()
            i += 4 + n
        }
        assertEquals(p.length, i)
    }

    @Test fun txidSemCodigo() {
        assertEquals("***", PixBrCode.txid(null))
        assertEquals("RGVABC234", PixBrCode.txid("abc-234"))
    }

    @Test fun limparTiraAcento() {
        assertEquals("Joao Conceicao", PixBrCode.limpar("João Conceição!", 25))
        assertEquals("GOVERNADOR VALA", PixBrCode.limpar("GOVERNADOR VALADARES", 15))
    }
}
