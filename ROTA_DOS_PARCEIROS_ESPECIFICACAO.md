# LUNA Neural Space — Especificação da Rota dos Parceiros

Documento de decisões do produto. Registra o que o app deve fazer, sem detalhar a implementação.

---

## 1. Visão geral

**LUNA Neural Space** é um aplicativo Android nativo (Kotlin/Gradle) feito para motoristas de aplicativo de Governador Valadares (MG).

### 1.1 O que o app oferece
- **Turno:** controle da jornada de trabalho do dia.
- **Ganhos e gastos por aplicativo:** 99, Uber e InDrive.
- **Metas:** acompanhamento do quanto falta para bater a meta.
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

### 2.1 Controles do turno
- **Zerar:** recomeça o turno, mas **os ganhos continuam no histórico**.
- **Pausa:** o tempo pausado conta como **minutos parado**, separado do **tempo de trabalho**.
- **Café:** funciona **igual à pausa**.
- **Fechar turno:** abre uma **tela de correção** com:
  - ganhos;
  - gastos;
  - horário de início e de fim.
  - Exemplo: o turno marcou início ao meio-dia, mas o motorista começou às 6h — ele corrige o início para 6h.

### 2.2 Lançamentos por voz
- Quando o motorista fala um valor, a Luna **pergunta se o valor é o total ou um valor a mais** (somado ao que já existe).

### 2.3 Calendário
- Um calendário permite **lançar dias esquecidos**.

### 2.4 Acompanhamento e alertas
- **Alerta motivacional** quando o motorista estiver **perto da meta**.
- **Custo de energia do dia.**
- **Sugestão de parar** quando o **ganho por hora cair**.
- Mostrar os valores **bruto e líquido**.

### 2.5 Funcionamento offline e Luna
- **Tudo funciona offline**, exceto pesquisa externa (notícias, clima e outras buscas fora do app).
- A Luna é **híbrida e local**: roda no celular e **aprende sozinha** com o uso.

### 2.6 Dados
- **Sem backup:** os dados ficam **só no celular**.
- **Firebase (plano gratuito Spark)** para **sincronizar quando houver internet**.
- Ver a seção "Questões em aberto" sobre como essas duas decisões convivem.

**Critérios de aceite**
- [ ] Depois de zerar, os ganhos anteriores continuam visíveis no histórico.
- [ ] Pausa e café somam em "minutos parado" e não entram no tempo de trabalho.
- [ ] Fechar turno sempre abre a tela de correção antes de salvar.
- [ ] Na tela de correção, é possível editar ganhos, gastos, início e fim.
- [ ] Ao falar um valor, a Luna pergunta "total ou a mais?" antes de salvar.
- [ ] É possível lançar ganhos e gastos de um dia anterior pelo calendário.
- [ ] Os valores bruto e líquido aparecem juntos.
- [ ] Sem internet, turno, ganhos, gastos, metas e calendário funcionam normalmente.

---

## 3. Orb flutuante

### 3.1 Comportamento
- **Fechar o app mostra a orb**, com um **contador**.
- **Dois toques** na orb **abrem o app**.
- **Um toque** abre o **menu da orb**.
- A orb é **arrastável** pela tela.
- **Arrastar** a orb (para fora da tela) **esconde** a orb, mas **o app continua rodando**.
- **Xizinho:** fecha **só a orb**.
- **Botão fechar:** **encerra o app de vez** e **derruba a ligação**.
- A orb mostra o **turno** e o **estado** (pausado ou café).

### 3.2 Menu da orb
- **7 botões**, com a **Luna no meio**:
  1. Iniciar
  2. Pausar
  3. Zerar
  4. Café
  5. Fechar turno
  6. Concluir
  7. Microfone
- O menu **só fecha quando o motorista clica para fechar**.

### 3.3 Resumo de gestos

| Ação do motorista            | Resultado                                   |
|------------------------------|---------------------------------------------|
| Um toque na orb              | Abre o menu                                 |
| Dois toques na orb           | Abre o app                                  |
| Arrastar a orb               | Move a orb pela tela                        |
| Arrastar a orb para fora     | Esconde a orb; o app continua rodando       |
| Tocar no xizinho             | Fecha só a orb                              |
| Tocar no botão fechar        | Encerra o app de vez e derruba a ligação    |
| Tocar para fechar o menu     | Fecha o menu (é a única forma de fechá-lo)  |

**Critérios de aceite**
- [ ] A orb aparece com o contador quando o app sai da tela.
- [ ] Um toque abre o menu; dois toques abrem o app; os dois nunca se confundem.
- [ ] O menu tem os 7 botões listados, com a Luna no centro.
- [ ] O menu não fecha sozinho depois de tocar em um botão.
- [ ] A orb mostra o turno atual e indica quando está pausado ou no café.
- [ ] Esconder a orb não para o turno nem o app.
- [ ] O xizinho fecha só a orb; o botão fechar encerra o app e a ligação.

---

## Questões em aberto

1. **Fechar o app × botão fechar:** "fechar o app mostra a orb" e "o botão fechar encerra o app de vez". A orb deve aparecer ao **minimizar** (sair do app sem encerrar) e o botão fechar encerra tudo sem orb?
2. **Sem backup × Firebase:** "sem backup, dados só no celular" e "Firebase Spark para sincronizar". A sincronização com o Firebase é só para os dados compartilhados com o grupo, ou os dados pessoais também vão para a nuvem (o que, na prática, funciona como backup)?
3. **Arrastar a orb:** como diferenciar "arrastar para mover" de "arrastar para esconder"? E como trazer de volta uma orb escondida?
4. **Contador da orb:** o contador mostra o tempo de trabalho, o tempo total do turno ou os ganhos?
5. **"Fechar turno" × "Concluir":** qual é a diferença entre esses dois botões do menu?
6. **Onde ficam o xizinho e o botão fechar:** os dois ficam na orb, no menu ou em lugares diferentes?
7. **Custo de energia do dia:** é o custo de combustível/recarga do veículo, informado pelo motorista ou calculado pelo app?
