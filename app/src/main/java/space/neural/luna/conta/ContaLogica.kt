package space.neural.luna.conta

/**
 * Regras do login (sem Android, testáveis). O motorista entra com e-mail OU celular + senha.
 * Celular vira um e-mail sintético no Firebase (5533XXXXXXXXX@tel.rotagv.app), sem SMS.
 */
object ContaLogica {
    const val DOMINIO_CELULAR = "tel.rotagv.app"
    const val SENHA_MIN = 6

    sealed class Identificador {
        abstract val emailFirebase: String
        data class Email(override val emailFirebase: String) : Identificador()
        /** [numero55] = 55 + DDD + 9 dígitos. */
        data class Celular(val numero55: String) : Identificador() {
            override val emailFirebase get() = "$numero55@$DOMINIO_CELULAR"
            val exibicao: String get() = numero55.drop(2).let { "(${it.take(2)}) ${it.substring(2, 7)}-${it.substring(7)}" }
        }
    }

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** "(33) 98704-4866", "+55 33 987044866", "033987044866" -> "5533987044866"; fixo/curto -> null. */
    fun celular(s: String): String? {
        var d = s.filter { it.isDigit() }
        if (d.length == 13 && d.startsWith("55")) d = d.drop(2)
        if (d.length == 12 && d.startsWith("0")) d = d.drop(1)
        if (!Regex("^[1-9][1-9]9\\d{8}$").matches(d)) return null
        return "55$d"
    }

    /** Lê o que foi digitado no campo "E-mail ou celular". */
    fun identificar(entrada: String): Identificador? {
        val t = entrada.trim()
        if (t.isEmpty()) return null
        if (t.contains('@')) {
            val e = t.lowercase()
            if (!EMAIL.matches(e) || e.endsWith("@$DOMINIO_CELULAR")) return null
            return Identificador.Email(e)
        }
        if (t.any { it.isLetter() }) return null
        return celular(t)?.let { Identificador.Celular(it) }
    }

    /** E-mail do Firebase -> como mostrar ao usuário (celular formatado ou o próprio e-mail). */
    fun exibir(emailFirebase: String?): String {
        val e = emailFirebase ?: return ""
        if (e.endsWith("@$DOMINIO_CELULAR")) {
            val n = e.substringBefore('@')
            return celular(n)?.let { Identificador.Celular(it).exibicao } ?: n
        }
        return e
    }

    /** Validação do formulário: null = ok, senão a mensagem para o usuário. */
    fun validar(criar: Boolean, nome: String, entrada: String, senha: String, confirmar: String): String? {
        if (criar && nome.trim().length < 2) return "Digite seu nome."
        if (entrada.isBlank()) return "Digite seu e-mail ou celular."
        if (identificar(entrada) == null) {
            return if (entrada.contains('@')) "E-mail inválido." else "Celular inválido. Use DDD + número, ex.: (33) 98765-4321."
        }
        if (senha.length < SENHA_MIN) return "A senha precisa ter pelo menos $SENHA_MIN caracteres."
        if (criar && senha != confirmar) return "As senhas não são iguais."
        return null
    }
}
