package space.neural.luna.licenca

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Confere a assinatura ECDSA P-256/SHA-256 das respostas do /license/check.
 * A chave pública fica embutida (não é segredo); a privada só existe como segredo do Worker.
 */
object AssinaturaLicenca {
    /** SPKI (base64) da chave pública de licença do Rota GV. */
    const val CHAVE_PUBLICA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEY4dnmddT2hcWvFv6jDimcShzM/eRKJhSajgDST5sDT2lGWkU+tUWSClOg4/ByT+3Q1Dxm3r4zgg5yQbbFT+1cA=="

    private val chave: PublicKey by lazy { carregar(CHAVE_PUBLICA) }

    fun carregar(spkiB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))

    fun confere(payload: String, sigB64: String, chavePublica: PublicKey = chave): Boolean = runCatching {
        val raw = Base64.getDecoder().decode(sigB64)
        if (raw.size != 64) return false
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(chavePublica)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(derDeRaw(raw))
        }
    }.getOrDefault(false)

    /** WebCrypto devolve r||s (64 bytes); o Java espera DER: SEQUENCE { INTEGER r, INTEGER s }. */
    fun derDeRaw(raw: ByteArray): ByteArray {
        fun inteiro(b: ByteArray): ByteArray {
            val v = BigInteger(1, b).toByteArray() // com 0x00 na frente se o bit alto estiver ligado
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val r = inteiro(raw.copyOfRange(0, 32))
        val s = inteiro(raw.copyOfRange(32, 64))
        val out = ByteArrayOutputStream()
        out.write(0x30); out.write(r.size + s.size); out.write(r); out.write(s)
        return out.toByteArray()
    }
}
