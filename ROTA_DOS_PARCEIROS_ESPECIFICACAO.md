# LUNA Neural Space — Especificação da Rota dos Parceiros

Documento de decisões do produto. Registra o que o app deve fazer, sem detalhar a implementação.

---

## 1. Visão geral

**LUNA Neural Space** é um aplicativo Android nativo (Kotlin/Gradle) feito para motoristas de aplicativo de Governador Valadares (MG).

### 1.1 O que o app oferece
- **Turno:** controle da jornada de trabalho do dia.
- **Ganhos e gastos por aplicativo:** 99, Uber e InDrive.
- **Metas:** acompanhamento do quanto falta para bater a meta.
- **Custo de energia:** gasto com a recarga elétrica do carro (Geely), não combustível.
- **Notícias e clima locais:** fontes como o Diário do Rio Doce e informações da BR-381.
- **Dicas** para o dia a dia do motorista.
- **Mensagens bíblicas.**
- **Voz:** o motorista pode falar com o app.
- **Avatar da Luna animado.**

### 1.2 Identidade visual

| Cor        | Código    |
|------------|-----------|
| Obsidian   | `#07060E` |
| Violeta    | `#8D6BFF` |
| Ciano      | `#49E0FF` |
| Esmeralda  | `#3EE0A0` |
| Branco     | `#F3F0FF` |

### 1.3 A Luna
- Mulher **estilizada e adulta**, usando um **boné LUNA**.
- **Não** é foto, **não** é robô e **não** é criança.

**Critérios de aceite**
- [ ] Todas as telas usam apenas a paleta acima (com variações de transparência, se preciso).
- [ ] O avatar da Luna aparece animado e segue a descrição da seção 1.3.

---

## 2. Jornada

### 2.1 Controles do turno (v0.1.4 — substitui a regra anterior)
- O dia tem **exatamente 4 turnos**, mostrados como **"Turno N/4"**.
- Botões: **Iniciar**, **Pausar**, **Café**, **Retomar**, **Fechar turno**, e sempre visíveis **Concluir dia** e **Zerar tempo** (os dois pedem confirmação).
- **Pausa / Café:** o tempo parado conta como **minutos parado**, separado do **tempo de trabalho**.
- **Fechar turno N (1–3):** encerra o turno **sem abrir nenhuma tela** e o próximo fica **"Turno N+1/4 — pronto para iniciar"**. Correções de horário ficam no Calendário (dia) ou em "Corrigir turno".
- **Depois de fechar o turno 4:** só existem **Retomar** (continua/estende o turno 4) ou **Concluir dia**. Não existe 5º turno.
- **Zerar tempo:** zera o tempo do turno atual (trabalho e parado) e o deixa **pronto para iniciar**; **os ganhos continuam no histórico**.
- **Concluir dia:** encerra o dia, salva o resumo no histórico; ganhos soltos são ligados ao último turno trabalhado e turnos que só estavam "prontos" são descartados. Pode ser reaberto no mesmo dia.
- O cronômetro é **baseado no relógio** (início/último evento salvos no Room): matar o app ou reiniciar o celular não perde tempo.

### 2.1.1 Fontes de ganho (5)
| Fonte | Cor | Selo |
|---|---|---|
| 99 | Laranja `#FF9F43` | "99" |
| Uber | Ciano `#49E0FF` | "U" |
| InDrive | Esmeralda `#3EE0A0` | "iD" |
| Gogeta | Magenta `#FF4FD8` | "G" |
| Particular | Amarelo `#FFE15A` | "P" |

As cinco aparecem em todo lugar: cards, rosca, ranking, gráficos, lançamento manual, detalhe/histórico, sincronização, chat ("30 na Gogeta", "50 particular", "corrida particular de 40") e contexto da IA.

### 2.1.2 Planejamento (Configurações → Planejamento)
As metas **não são mais digitadas no dashboard**; elas vêm do Planejamento:
- **Dias de trabalho** em cada uma das 4 semanas do mês (semana 1 = dias 1–7, 2 = 8–14, 3 = 15–21, 4 = dia 22 até o fim), seg a dom.
- **Ganho por hora típico** (líquido).
- **Contas fixas** (nome, valor, dia de vencimento).
- **Limites / envelopes** (ex.: Supermercado R$ 1.000, Lanche R$ 200).
- **Renda desejada** (opcional).

**Cálculo da sugestão**
- `total do mês = contas fixas + limites + renda desejada`
- `meta do dia = total ÷ dias de trabalho do mês` (arredonda para cima, ao centavo)
- `meta da semana = meta do dia × dias de trabalho da semana atual`
- `horas por dia = meta do dia ÷ ganho por hora`
- **Recalcular** (depois de estourar um envelope): `necessário = total + excesso dos envelopes`; `falta = necessário − líquido do mês`; `nova meta do dia = falta ÷ dias de trabalho restantes (de hoje até o fim do mês)`.
- O motorista **aceita** ou **edita** a sugestão; isso vira as metas Dia/Semana/Mês.

### 2.1.3 Limites (envelopes) e foto da nota
- Card **LIMITES DO MÊS**: gasto / limite, "Ainda pode gastar R$ X", barra; fica vermelho ao estourar.
- Gastos de envelope (`gasto.limiteId`) são **pessoais**: **não** entram no líquido do trabalho (o total do plano já inclui os limites — evita contar duas vezes).
- **Foto da nota no chat:** câmera ou galeria → reduzida para no máx. 1600 px JPEG → Worker `POST /receipt` (Gemini multimodal, resposta JSON: é compra?, loja, data, total, itens). A Luna resume e pergunta com botões **em qual limite** lançar (ou "gasto comum" / "não é compra"), confirma com **"Sim"**, salva e diz quanto sobrou.
- Se o envelope estourar: "você gastou muito" e oferece **"Recalcular meu plano"** ou **"Deixar para o mês que vem"**. **Nada muda sem confirmação.**

### 2.2 Lançamentos por voz
- Quando o motorista fala um valor, a Luna **pergunta se o valor é o total ou um valor a mais** (somado ao que já existe).

### 2.3 Calendário
- Grade do mês com navegação ‹ ›; **ponto verde** nos dias com lançamentos; hoje destacado.
- Tocar num dia abre o dia: os **4 turnos** (início, término, pausas — ver e editar), horas, ganhos por app e gastos.
- Dá para **lançar dias passados** (dias esquecidos). Corrigir o horário de início **recalcula as horas**.
- **Dias futuros:** só visualização.

### 2.3.1 Relatórios
- Carrossel de blocos que **passa sozinho** (e pode ser arrastado): dias trabalhados no mês, horas dia/semana/mês, média R$/hora, média por dia, melhor dia, melhor app, gastos totais, corridas.
- Tocar abre o **relatório completo** (destaques + tabela Hoje/Semana/Mês/Ano + apps no mês).

### 2.3.2 Luna na tela inicial e histórico
- Card da Luna logo abaixo de "Bom dia, Marlon" (no lugar do ícone de chat): **um toque** = fala por voz ali mesmo (resposta escrita e falada no card); **dois toques** ou "abrir conversa" = conversa completa.
- O **histórico da conversa** (inclusive o que foi falado por voz, transcrito) fica salvo no Room do celular (não é sincronizado).

### 2.4 Acompanhamento e alertas
- **Alerta motivacional** quando o motorista estiver **perto da meta**.
- **Custo de energia do dia:** custo da **recarga elétrica do Geely** (não é combustível). Pode ser lançado de dois jeitos:
  - o Marlon **informa o valor gasto**; ou
  - o app **calcula a partir dos kWh lançados**.
- **Sugestão de parar** quando o **ganho por hora cair**.
- Mostrar os valores **bruto e líquido**.

### 2.5 Funcionamento offline e Luna
- **Tudo funciona offline**, exceto pesquisa externa (notícias, clima e outras buscas fora do app).
- A Luna é **híbrida e local**: roda no celular e **aprende sozinha** com o uso.

### 2.6 Dados
- **Sem backup:** os dados — **turno, ganhos, gastos, metas, planejamento, contas fixas, limites e anotações** — ficam **só no celular**.
- **Sincronização com o Firebase (plano gratuito Spark):** quando houver internet, esses **mesmos dados** são enviados para a nuvem.
- **Regras de segurança:** cada usuário **só vê os próprios dados**.

**Critérios de aceite**
- [ ] Depois de zerar, os ganhos anteriores continuam visíveis no histórico.
- [ ] Pausa e café somam em "minutos parado" e não entram no tempo de trabalho.
- [ ] Fechar turno encerra o turno e sempre abre a tela de correção antes de salvar.
- [ ] Concluir finaliza o dia e salva o resumo no histórico.
- [ ] O custo de energia aceita o valor gasto na recarga ou é calculado pelos kWh lançados.
- [ ] Na tela de correção, é possível editar ganhos, gastos, início e fim.
- [ ] Ao falar um valor, a Luna pergunta "total ou a mais?" antes de salvar.
- [ ] É possível lançar ganhos e gastos de um dia anterior pelo calendário.
- [ ] Os valores bruto e líquido aparecem juntos.
- [ ] Sem internet, turno, ganhos, gastos, metas, anotações e calendário funcionam normalmente.
- [ ] Com internet, turno, ganhos, gastos, metas e anotações sincronizam com o Firebase.
- [ ] Um usuário nunca consegue ler nem alterar os dados de outro usuário no Firebase.

---

## 3. Orb flutuante

### 3.1 Comportamento
- **Minimizar o app mostra a orb.**
- **Fechar o app** faz a orb **sumir**. Para trazer a orb de volta, basta **minimizar o app**.
- **Contador:** mostra o **tempo do turno atual**, contando **só o tempo de trabalho** (sem pausa e sem café).
- **Dois toques** na orb **abrem o app**.
- **Um toque** abre o **menu da orb**.
- **Arrastar** a orb **move** a orb pela tela. Não existe gesto separado de "arrastar pra esconder".
- **Xizinho:** fica **na própria orb** e fecha **só a orb**.
- **Botão fechar do app:** fica **fora da orb, no sistema Android**. **Encerra o app de vez** e **derruba a ligação**, se houver uma ativa.
- A orb mostra o **turno** e o **estado** (pausado ou café).

### 3.2 Menu da orb
- **7 botões**, com a **Luna no meio**:
  1. Iniciar
  2. Pausar
  3. Zerar
  4. Café
  5. Fechar turno — encerra o turno e abre a tela de correção
  6. Concluir — finaliza o dia e salva o resumo no histórico
  7. Microfone
- O menu **só fecha quando o motorista clica para fechar**.

### 3.3 Resumo de gestos

| Ação do motorista            | Resultado                                   |
|------------------------------|---------------------------------------------|
| Um toque na orb              | Abre o menu                                 |
| Dois toques na orb           | Abre o app                                  |
| Arrastar a orb               | Move a orb pela tela                        |
| Tocar no xizinho (na orb)    | Fecha só a orb                              |
| Minimizar o app              | Mostra a orb                                |
| Fechar o app (sistema Android) | Encerra o app de vez, a orb some e a ligação ativa cai |
| Tocar para fechar o menu     | Fecha o menu (é a única forma de fechá-lo)  |

**Critérios de aceite**
- [ ] A orb aparece com o contador quando o app é minimizado.
- [ ] O contador mostra só o tempo de trabalho do turno atual e para durante pausa e café.
- [ ] Um toque abre o menu; dois toques abrem o app; os dois nunca se confundem.
- [ ] O menu tem os 7 botões listados, com a Luna no centro.
- [ ] O menu não fecha sozinho depois de tocar em um botão.
- [ ] A orb mostra o turno atual e indica quando está pausado ou no café.
- [ ] Arrastar a orb só muda a posição dela; nunca esconde, fecha ou aciona botões.
- [ ] O xizinho fica na orb e fecha só a orb, sem parar o turno nem o app.
- [ ] Fechar o app pelo Android encerra o app, faz a orb sumir e derruba a ligação ativa.
- [ ] Depois de fechada, a orb volta quando o app é minimizado.

---

## Decisões tomadas

1. **Quando a orb aparece:** a orb aparece ao **minimizar** o app; o botão fechar encerra o app de vez e derruba a ligação ativa (seção 3.1).
2. **Dados e nuvem:** sem backup, os dados ficam só no celular; com internet, os mesmos dados sincronizam no Firebase Spark, e cada usuário só vê os próprios dados (seção 2.6).
3. **Arrastar a orb:** arrastar só move a orb; ela some ao fechar o app e volta ao minimizar (seção 3.1).
4. **Contador da orb:** tempo do turno atual, só o tempo de trabalho, sem pausa e café (seção 3.1).
5. **Fechar turno × Concluir:** fechar turno encerra o turno e abre a tela de correção; concluir finaliza o dia e salva o resumo no histórico (seções 2.1 e 3.2).
6. **Xizinho × botão fechar:** o xizinho fica na orb e fecha só a orb; o botão fechar do app fica fora da orb, no sistema Android (seção 3.1).
7. **Custo de energia:** custo da recarga elétrica do Geely, não combustível; valor informado pelo Marlon ou calculado pelos kWh lançados (seções 1.1 e 2.4).

> Observação: numa resposta anterior, "arrastar" também foi descrito como forma de esconder a orb; a regra que vale é que arrastar só move, e a orb é escondida ou mostrada ao fechar ou minimizar o app.
