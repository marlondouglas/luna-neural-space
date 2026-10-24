package space.neural.luna.chat

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LunaIntentParserTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val categories = listOf("Mercado", "Lazer", "Combustível", "Alimentação")
    @Test fun nativeCategoriesDoNotRequireLimits() {
        assertEquals("COMBUSTIVEL", LunaIntentParser.nativeCategory("Gastei vinte reais com gasolina"))
        assertEquals("ENERGIA", LunaIntentParser.nativeCategory("Paguei 35,50 de recarga elétrica"))
        assertEquals("MANUTENCAO", LunaIntentParser.nativeCategory("Paguei 80 na oficina"))
        assertNull(LunaIntentParser.nativeCategory("paguei no mercado"))
        val expense = LunaIntentParser.parse("Gastei vinte reais com combustível", emptyList(), today) as LunaIntentParser.Intent.Expense
        assertEquals(2000L, expense.cents)
    }
    @Test fun expensesWithVariations() {
        listOf("Luna, gastei cem no mercado.", "Mercado 100.", "Comprei 100 reais de comida.", "Gasteii 100 no mercadu.").forEach {
            val parsed = LunaIntentParser.parse(it, categories, today) as LunaIntentParser.Intent.Expense
            assertEquals(it, 10000L, parsed.cents)
            assertEquals("Mercado", parsed.category)
        }
    }
    @Test fun preciseMoneyAndAmbiguity() {
        assertEquals(3550L, LunaIntentParser.amount("35,50"))
        assertEquals(3550L, LunaIntentParser.amount("35.50"))
        assertEquals(123456L, LunaIntentParser.amount("1.234,56"))
        assertEquals(5000L, LunaIntentParser.amount("cinquenta de combustível"))
        assertNull(LunaIntentParser.amount("100 ou 80"))
        assertNull(LunaIntentParser.amount("-20"))
        assertNull(LunaIntentParser.amount("35 reais e cinquenta centavos"))
        assertNull(LunaIntentParser.amount("aproximadamente 100"))
    }
    @Test fun datesAndMissingCategory() {
        val gain = LunaIntentParser.parse("Ganhei 250 ontem", categories, today) as LunaIntentParser.Intent.Income
        assertEquals(today.minusDays(1), gain.date)
        val expense = LunaIntentParser.parse("Gastei 100", categories, today) as LunaIntentParser.Intent.Expense
        assertNull(expense.category)
        assertTrue(LunaIntentParser.parse("Corrige o gasto de segunda para 80", categories, today) is LunaIntentParser.Intent.Correction)
    }
    @Test fun navigationAndQuestionsDoNotCreateTransactions() {
        assertEquals(LunaIntentParser.Intent.Navigate("mapa"), LunaIntentParser.parse("Luna, abre o rádio", categories, today))
        assertNull(LunaIntentParser.parse("Quanto gastei no mercado?", categories, today))
        assertEquals(LunaIntentParser.Intent.Limits, LunaIntentParser.parse("Quanto ainda tenho para gastar?", categories, today))
    }
}
