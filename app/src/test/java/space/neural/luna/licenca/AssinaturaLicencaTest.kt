package space.neural.luna.licenca

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AssinaturaLicencaTest {
    // Assinatura gerada pelo WebCrypto (mesmo código do Worker) com uma chave SÓ DE TESTE.
    private val pubTeste = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKwzN32Rr2NKKqntFMjyqqySuQS97aYpjHLi8POZZqm0HWgzKpSOEQp4LM+hNxKtv0jE4vZ+JEvInlOqU443KWw=="
    private val payload = "{\"v\":1,\"status\":\"trial\",\"uid\":\"u1\",\"device\":\"abc\",\"code\":\"K7M2QX\",\"trialStart\":1,\"trialEndsAt\":2,\"paidUntil\":0,\"serverTime\":1,\"graceMs\":3,\"minVersionCode\":106,\"downloadUrl\":\"https://x\",\"nonce\":\"n1234567\"}"
    private val sig = "aLCSGD16VIDyz9nrW4otk7YptEVjoGBR2RZz7Klw30WogmnUmiIjmEv6wCDiV75CrWuo+v9BLLWRCBCh02gsyQ=="

    @Test fun confereAssinaturaDoWebCrypto() {
        val k = AssinaturaLicenca.carregar(pubTeste)
        assertTrue(AssinaturaLicenca.confere(payload, sig, k))
    }

    @Test fun payloadAlteradoFalha() {
        val k = AssinaturaLicenca.carregar(pubTeste)
        assertFalse(AssinaturaLicenca.confere(payload.replace("\"trial\"", "\"active\""), sig, k))
        assertFalse(AssinaturaLicenca.confere(payload.replace("\"paidUntil\":0", "\"paidUntil\":9999999999999"), sig, k))
    }

    @Test fun assinaturaInvalidaOuDeOutraChaveFalha() {
        val k = AssinaturaLicenca.carregar(pubTeste)
        assertFalse(AssinaturaLicenca.confere(payload, "AAAA", k))
        assertFalse(AssinaturaLicenca.confere(payload, java.util.Base64.getEncoder().encodeToString(ByteArray(64)), k))
        // a chave de produção embutida não aceita a assinatura de teste
        assertFalse(AssinaturaLicenca.confere(payload, sig))
    }

    @Test fun chaveDeProducaoEmbutidaCarrega() {
        assertEquals("EC", AssinaturaLicenca.carregar(AssinaturaLicenca.CHAVE_PUBLICA).algorithm)
    }

    @Test fun derDeRawIdaEVolta() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(20) {
            val kp = g.generateKeyPair()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update("x".toByteArray()); sign() }
            val raw = rawDeDer(der)
            assertArrayEquals(der, AssinaturaLicenca.derDeRaw(raw))
            assertTrue(AssinaturaLicenca.confere("x", java.util.Base64.getEncoder().encodeToString(raw), kp.public))
        }
    }

    private fun rawDeDer(der: ByteArray): ByteArray {
        var i = 2
        fun int(): ByteArray { val n = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + n); i += 2 + n
            val t = v.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - t.size) + t }
        return int() + int()
    }
}
