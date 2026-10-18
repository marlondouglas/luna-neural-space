package space.neural.luna.dashboard

import java.time.LocalDate

/** Dicas e versículos guardados no aparelho (funcionam offline). Mudam uma vez por dia. */
object Conteudo {
    data class Versiculo(val texto: String, val referencia: String)

    val dicas = listOf(
        "Horários de pico em Valadares: de 6h30 às 8h30 e de 17h às 19h30. Esteja rodando nesses horários.",
        "Bateria do Geely: rodar entre 20% e 80% de carga ajuda a bateria a durar mais.",
        "Recarregue de preferência fora do horário de pico de energia, quando o kWh costuma ser mais barato.",
        "Antes de aceitar uma corrida longa, confira se a carga dá para voltar sem aperto.",
        "Ar-condicionado no automático e janelas fechadas na estrada gastam menos energia.",
        "Use a frenagem regenerativa: aliviar o pé com antecedência devolve energia para a bateria.",
        "Calibre os pneus toda semana. Pneu murcho rouba autonomia e desgasta mais rápido.",
        "Lance cada corrida na hora. No fim do dia fica fácil esquecer valores.",
        "Anote também gorjetas e pedágios: o líquido fica mais certo.",
        "Faça uma pausa de 10 minutos a cada 2 horas. Descansar também é trabalho bem feito.",
        "Mantenha água no carro e beba ao longo do turno. Cansaço aumenta com desidratação.",
        "Em dia de chuva a procura sobe. Saia mais cedo e dirija com mais distância do carro da frente.",
        "Compare o ganho por hora de cada app na semana e priorize o que rende mais no seu horário.",
        "Dias de pagamento e começo de mês costumam ter mais corridas no centro.",
        "Fim de semana à noite, a região de bares e eventos costuma ter boa demanda.",
        "Carro limpo e cheiroso aumenta a nota e as gorjetas.",
        "Tenha carregador de celular para o passageiro: é um detalhe que ele lembra.",
        "Na BR-381, redobre a atenção em curvas e trechos em obra. Corrida nenhuma vale um acidente.",
        "Defina sua meta do dia antes de sair. Fica mais fácil saber a hora de parar.",
        "Separe todo dia um valor para manutenção do carro. Quando a revisão chegar, o dinheiro já está lá.",
        "Evite aceitar corridas com embarque muito longe: o deslocamento vazio não é pago.",
    )

    val versiculos = listOf(
        Versiculo("O Senhor guardará a tua entrada e a tua saída, desde agora e para sempre.", "Salmos 121:8"),
        Versiculo("Tudo posso naquele que me fortalece.", "Filipenses 4:13"),
        Versiculo("Entrega o teu caminho ao Senhor; confia nele, e ele tudo fará.", "Salmos 37:5"),
        Versiculo("O Senhor é o meu pastor; nada me faltará.", "Salmos 23:1"),
        Versiculo("Confia no Senhor de todo o teu coração e não te estribes no teu próprio entendimento.", "Provérbios 3:5"),
        Versiculo("Vinde a mim, todos os que estais cansados e oprimidos, e eu vos aliviarei.", "Mateus 11:28"),
        Versiculo("Lança o teu cuidado sobre o Senhor, e ele te susterá.", "Salmos 55:22"),
        Versiculo("O choro pode durar uma noite, mas a alegria vem pela manhã.", "Salmos 30:5"),
        Versiculo("Consagra ao Senhor as tuas obras, e os teus pensamentos serão estabelecidos.", "Provérbios 16:3"),
        Versiculo("Esforça-te e tem bom ânimo; não temas, nem te espantes, porque o Senhor, teu Deus, é contigo por onde quer que andares.", "Josué 1:9"),
        Versiculo("Deus é o nosso refúgio e fortaleza, socorro bem presente na angústia.", "Salmos 46:1"),
        Versiculo("E tudo quanto fizerdes, fazei-o de todo o coração, como ao Senhor.", "Colossenses 3:23"),
        Versiculo("Lâmpada para os meus pés é tua palavra e luz para o meu caminho.", "Salmos 119:105"),
        Versiculo("Este é o dia que fez o Senhor; regozijemo-nos e alegremo-nos nele.", "Salmos 118:24"),
        Versiculo("As suas misericórdias não têm fim; novas são cada manhã; grande é a tua fidelidade.", "Lamentações 3:22-23"),
        Versiculo("Os que esperam no Senhor renovarão as suas forças.", "Isaías 40:31"),
        Versiculo("O Senhor é a minha luz e a minha salvação; a quem temerei?", "Salmos 27:1"),
        Versiculo("Buscai primeiro o Reino de Deus, e a sua justiça, e todas estas coisas vos serão acrescentadas.", "Mateus 6:33"),
        Versiculo("Não temas, porque eu sou contigo.", "Isaías 41:10"),
        Versiculo("Bem-aventurados os pacificadores, porque eles serão chamados filhos de Deus.", "Mateus 5:9"),
    )

    private fun indice(d: LocalDate, n: Int) = ((d.toEpochDay() % n + n) % n).toInt()
    fun dica(d: LocalDate) = dicas[indice(d, dicas.size)]
    fun versiculo(d: LocalDate) = versiculos[indice(d, versiculos.size)]
}
