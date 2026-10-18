package space.neural.luna.conta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.conta.ContaLogica.Identificador

class ContaLogicaTest {
    @Test fun celularViraEmailSintetico() {
        val c = ContaLogica.identificar("(33) 98704-4866") as Identificador.Celular
        assertEquals("5533987044866", c.numero55)
        assertEquals("5533987044866@tel.rotagv.app", c.emailFirebase)
        assertEquals("(33) 98704-4866", c.exibicao)
        assertEquals(c, ContaLogica.identificar("+55 33 98704 4866"))
        assertEquals(c, ContaLogica.identificar("033987044866"))
        assertEquals(c, ContaLogica.identificar("33987044866"))
    }

    @Test fun recusaFixoECurto() {
        assertNull(ContaLogica.identificar("(33) 3271-4000"))
        assertNull(ContaLogica.identificar("98704-4866"))
        assertNull(ContaLogica.identificar("00987044866"))
    }

    @Test fun email() {
        assertEquals(Identificador.Email("motorista@gmail.com"), ContaLogica.identificar("  Motorista@Gmail.com "))
        assertNull(ContaLogica.identificar("motorista@"))
        assertNull(ContaLogica.identificar("abc"))
        // ninguém cria e-mail no domínio dos celulares
        assertNull(ContaLogica.identificar("5533987044866@tel.rotagv.app"))
    }

    @Test fun exibir() {
        assertEquals("(33) 98704-4866", ContaLogica.exibir("5533987044866@tel.rotagv.app"))
        assertEquals("a@b.com", ContaLogica.exibir("a@b.com"))
        assertEquals("", ContaLogica.exibir(null))
    }

    @Test fun validarFormulario() {
        assertEquals("Digite seu nome.", ContaLogica.validar(true, "", "a@b.com", "123456", "123456"))
        assertNull(ContaLogica.validar(false, "", "a@b.com", "123456", ""))
        assertEquals("Digite seu e-mail ou celular.", ContaLogica.validar(false, "", " ", "123456", ""))
        assertEquals("E-mail inválido.", ContaLogica.validar(false, "", "a@b", "123456", ""))
        assertTrue(ContaLogica.validar(false, "", "3398", "123456", "")!!.startsWith("Celular inválido"))
        assertEquals("A senha precisa ter pelo menos 6 caracteres.", ContaLogica.validar(false, "", "a@b.com", "123", ""))
        assertEquals("As senhas não são iguais.", ContaLogica.validar(true, "Ana", "(33) 98704-4866", "123456", "123457"))
        assertNull(ContaLogica.validar(true, "Ana", "(33) 98704-4866", "123456", "123456"))
    }
}
