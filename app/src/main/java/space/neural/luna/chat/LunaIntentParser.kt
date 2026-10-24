package space.neural.luna.chat

import java.math.BigDecimal
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Intenções locais: não há SQL nem execução de texto produzido por um modelo. */
object LunaIntentParser {
    sealed interface Intent {
        data class Expense(val cents: Long?, val category: String?, val date: LocalDate, val description: String) : Intent
        data class Income(val cents: Long?, val date: LocalDate) : Intent
        data class Navigate(val destination: String) : Intent
        data object Limits : Intent
        data object Undo : Intent
        data class Clarify(val question: String) : Intent
        data class Correction(val income: Boolean, val date: LocalDate?, val category: String?, val oldCents: Long?,
            val newCents: Long?, val newCategory: String?, val delete: Boolean) : Intent
    }

    fun normalize(text: String) = Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("(.)\\1{2,}"), "$1$1")
        .replace("gasteii", "gastei").replace("mercadu", "mercado").trim()

    private val numbers = mapOf("zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2,
        "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14, "catorze" to 14,
        "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19,
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "setenta" to 70, "oitenta" to 80, "noventa" to 90, "cem" to 100, "cento" to 100,
        "duzentos" to 200, "trezentos" to 300, "quatrocentos" to 400, "quinhentos" to 500,
        "seiscentos" to 600, "setecentos" to 700, "oitocentos" to 800, "novecentos" to 900)

    fun amount(text: String): Long? {
        val q = normalize(text)
        if (Regex("centavo|\\b(talvez|aproximadamente|ou)\\b").containsMatchIn(q)) return null
        if (Regex("-\\s*\\d").containsMatchIn(q)) return null
        val numeric = Regex("(?<![\\d/])(?:\\d+\\.\\d{1,2}(?!\\d)|\\d+(?:\\.\\d{3})*(?:,\\d{1,2})?)(?![\\d/])").findAll(q).toList()
        if (numeric.size > 1) return null
        if (numeric.size == 1) {
            val raw = numeric.single().value
            val decimal = if (raw.contains(',')) raw.replace(".", "").replace(',', '.')
                else if (Regex("\\d{1,3}(\\.\\d{3})+").matches(raw)) raw.replace(".", "") else raw
            return runCatching { BigDecimal(decimal).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }
        }
        val tokens = q.split(Regex("[^a-z]+"))
        var total = 0L; var group = 0L; var found = false
        for (t in tokens) {
            val n = numbers[t]
            when {
                n != null -> { group += n; found = true }
                t == "mil" -> { total += (if (group == 0L) 1 else group) * 1000; group = 0; found = true }
                found && t !in setOf("e", "reais", "real") -> break
            }
        }
        return ((total + group) * 100).takeIf { found && it > 0 }
    }

    /** Categorias independentes de limites mensais cadastrados. */
    fun nativeCategory(text: String): String? = when {
        Regex("combustivel|gasolina|etanol|diesel").containsMatchIn(normalize(text)) -> "COMBUSTIVEL"
        Regex("energia|recarga|carregamento|eletric").containsMatchIn(normalize(text)) -> "ENERGIA"
        Regex("manutencao|pneu|oficina").containsMatchIn(normalize(text)) -> "MANUTENCAO"
        else -> null
    }

    fun parse(text: String, categories: List<String>, today: LocalDate): Intent? {
        val q = normalize(text).removePrefix("luna").trim(' ', ',')
        if (q in setOf("desfazer", "desfaz", "desfazer ultimo lancamento")) return Intent.Undo
        if (Regex("\\b(abre|abrir|abra|mostra|mostrar)\\b").containsMatchIn(q)) {
            val dest = when {
                "mapa" in q || "radio" in q -> "mapa"
                "parceiro" in q || "eletroposto" in q -> "parceiros"
                "turno" in q -> "inicio"
                "meta" in q || "planejamento" in q -> "planejamento"
                "configur" in q -> "configuracoes"
                else -> null
            }
            if (dest != null) return Intent.Navigate(dest)
        }
        if ("limite" in q || "tenho para gastar" in q || "posso gastar" in q) return Intent.Limits
        val correction = Regex("\\b(corrige|corrigir|altera|alterar|muda|mudar|exclui|excluir|apaga)\\b").containsMatchIn(q)
        val category = categories.firstOrNull { normalize(it) in q }
            ?: categories.firstOrNull { name ->
                val n = normalize(name)
                ("mercado" in n && Regex("mercado|supermercado|comida").containsMatchIn(q)) ||
                    ("aliment" in n && Regex("almoco|jantar|comida|lanche").containsMatchIn(q)) ||
                    ("combust" in n && Regex("combustivel|gasolina|etanol|diesel").containsMatchIn(q))
            }
        val spending = Regex("\\b(gastei|gasto|paguei|comprei|coloca|lanca|lancar)\\b").containsMatchIn(q)
        val income = Regex("\\b(recebi|ganhei|recebimento)\\b").containsMatchIn(q)
        if (!spending && !income && category == null && !correction) return null
        if (Regex("\\b(quanto|qual|como)\\b").containsMatchIn(q) || q.endsWith("?")) return null
        val date = when {
            "anteontem" in q -> today.minusDays(2)
            "ontem" in q -> today.minusDays(1)
            else -> {
                val days = mapOf("segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY,
                    "quarta" to DayOfWeek.WEDNESDAY, "quinta" to DayOfWeek.THURSDAY,
                    "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY)
                days.entries.firstOrNull { it.key in q }?.value?.let { today.with(TemporalAdjusters.previousOrSame(it)) } ?: today
            }
        }
        if (Regex("\\d{1,2}/\\d{1,2}|amanha|mes passado").containsMatchIn(q))
            return Intent.Clarify("Informe a data pelo formulário de lançamento para eu não registrar no dia errado.")
        if (correction) {
            val before = q.substringBefore(" para ")
            val after = q.substringAfter(" para ", "")
            val isIncome = Regex("recebimento|ganho|recebi|ganhei").containsMatchIn(q)
            val hasDate = Regex("hoje|ontem|segunda|terca|quarta|quinta|sexta|sabado|domingo").containsMatchIn(q)
            return Intent.Correction(isIncome, date.takeIf { hasDate }, categories.firstOrNull { normalize(it) in before },
                amount(before), amount(after), categories.singleOrNull { normalize(it) in after },
                Regex("\\b(exclui|excluir|apaga)\\b").containsMatchIn(q))
        }
        val cents = amount(q)
        return if (income) Intent.Income(cents, date) else Intent.Expense(cents, category, date, text.take(200))
    }
}
