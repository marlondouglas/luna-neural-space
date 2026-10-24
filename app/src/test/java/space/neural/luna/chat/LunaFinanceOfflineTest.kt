package space.neural.luna.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.neural.luna.data.local.*
import space.neural.luna.data.repo.LunaRepositories
import space.neural.luna.dashboard.Plano
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LunaFinanceOfflineTest {
    private lateinit var db: LunaDatabase
    private lateinit var repo: LunaRepositories
    private lateinit var conversation: LunaConversa
    private val market = Limite(id = "market", nome = "Mercado", limiteCentavos = 80000)
    private val leisure = Limite(id = "leisure", nome = "Lazer", limiteCentavos = 10000)
    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db = Room.inMemoryDatabaseBuilder(app, LunaDatabase::class.java).allowMainThreadQueries().build()
        repo = LunaRepositories(app, db); conversation = LunaConversa(app, repo)
        db.limiteDao().upsert(market); db.limiteDao().upsert(leisure)
    }
    @After fun close() { db.close() }
    @Test fun expenseConfirmationIsIdempotentAndLimitsStayIndependent() = runBlocking {
        val proposal = conversation.enviar("Gasteii cem no mercadu")
        assertTrue(repo.observeGastos().first().isEmpty())
        val confirm = proposal.botoes.first().comando
        conversation.enviar(confirm); conversation.enviar(confirm)
        val expenses = repo.observeGastos().first()
        assertEquals(1, expenses.size)
        val envelopes = Plano.envelopes(listOf(market, leisure), expenses, LocalDate.now())
        assertEquals(70000L, envelopes.first { it.limite.id == market.id }.restanteCentavos)
        assertEquals(10000L, envelopes.first { it.limite.id == leisure.id }.restanteCentavos)
        conversation.enviar("desfazer")
        assertTrue(repo.observeGastos().first().isEmpty())
        assertEquals(listOf("CRIACAO", "EXCLUSAO"), db.auditoriaFinanceiraDao().historico(expenses.single().id).map { it.acao })
    }
    @Test fun categoryClarificationAndHistoricalCorrection() = runBlocking {
        val clarify = conversation.enviar("Gastei 100")
        val proposal = conversation.enviar(clarify.botoes.first { it.rotulo == "Mercado" }.comando)
        conversation.enviar(proposal.botoes.first().comando)
        val choice = conversation.enviar("Altera o gasto de hoje de 100 para 80")
        val correction = conversation.enviar(choice.botoes.first().comando)
        conversation.enviar(correction.botoes.first().comando)
        assertEquals(8000L, repo.observeGastos().first().single().valorCentavos)
        conversation.enviar("desfazer")
        assertEquals(10000L, repo.observeGastos().first().single().valorCentavos)
    }
    @Test fun incomeDateAndReconstructedConversationReadPersistedData() = runBlocking {
        val proposal = conversation.enviar("Ganhei 250 ontem")
        conversation.enviar(proposal.botoes.first().comando)
        val income = repo.observeGanhos().first().single()
        assertEquals(LocalDate.now().minusDays(1).toString(), income.data)
        assertEquals(25000L, income.valorCentavos)
        val recreated = LunaConversa(ApplicationProvider.getApplicationContext<Application>(), repo)
        assertTrue(recreated.historico().isNotEmpty())
        assertTrue(recreated.enviar("Quanto ainda tenho para gastar?").texto.contains("Mercado"))
    }
    @Test fun correctingPendingFuelExpensePreservesConfirmationAndCreatesOneRecord() = runBlocking {
        val proposal = conversation.enviar("Gastei vinte reais com combustível")
        val confirm = proposal.botoes.first { it.rotulo == "Confirmar" }.comando
        conversation.enviar(proposal.botoes.first { it.rotulo == "Corrigir" }.comando)
        conversation.enviar(confirm)
        assertTrue(repo.observeGastos().first().isEmpty())
        val corrected = conversation.enviar("25 reais")
        assertEquals(confirm, corrected.botoes.first { it.rotulo == "Confirmar" }.comando)
        conversation.enviar(confirm); conversation.enviar(confirm)
        assertEquals(2500L, repo.observeGastos().first().single().valorCentavos)
    }
}
