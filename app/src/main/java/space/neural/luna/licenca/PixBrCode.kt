package space.neural.luna.licenca

import java.text.Normalizer

/**
 * "Pix copia e cola" (BR Code estático, padrão EMV QRCPS do Banco Central) para a assinatura.
 * A chave 33987044866 é tratada como CELULAR (DDD 33): no BR Code vai no formato +5533987044866.
 */
object PixBrCode {
    /** Chave Pix do Rota GV (celular). */
    const val CHAVE = "+5533987044866"
    const val CHAVE_EXIBICAO = "(33) 98704-4866"
    const val VALOR_CENTAVOS = 700L
    /** Nome/cidade do recebedor no QR (o banco do pagador mostra o nome real do titular da chave). */
    const val NOME = "ROTA GV"
    const val CIDADE = "GOV VALADARES"

    private fun campo(id: String, valor: String): String {
        require(valor.length in 1..99) { "Campo $id com tamanho inválido" }
        return id + valor.length.toString().padStart(2, '0') + valor
    }

    /** Só letras, números e espaço, sem acento (exigência de vários bancos). */
    fun limpar(s: String, max: Int): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), "").trim().take(max)

    /** txid do QR estático: até 25 caracteres alfanuméricos ("***" = sem identificador). */
    fun txid(codigoConta: String?): String {
        val c = codigoConta?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()
        return if (c.isEmpty()) "***" else "RGV$c".take(25)
    }

    fun payload(
        chave: String = CHAVE,
        valorCentavos: Long? = VALOR_CENTAVOS,
        nome: String = NOME,
        cidade: String = CIDADE,
        txid: String = "***",
    ): String {
        val conta = campo("00", "br.gov.bcb.pix") + campo("01", chave)
        val sb = StringBuilder()
            .append(campo("00", "01"))
            .append(campo("26", conta))
            .append(campo("52", "0000"))
            .append(campo("53", "986"))
        if (valorCentavos != null && valorCentavos > 0) {
            sb.append(campo("54", "%d.%02d".format(valorCentavos / 100, valorCentavos % 100)))
        }
        sb.append(campo("58", "BR"))
            .append(campo("59", limpar(nome, 25)))
            .append(campo("60", limpar(cidade, 15)))
            .append(campo("62", campo("05", txid)))
            .append("6304")
        return sb.toString() + crc16(sb.toString())
    }

    /** CRC16-CCITT (polinômio 0x1021, inicial 0xFFFF), 4 dígitos hexadecimais maiúsculos. */
    fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return "%04X".format(crc)
    }
}
