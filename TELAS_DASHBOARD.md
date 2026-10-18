# LUNA Neural Space — Tela inicial (dashboard)

Documento de design da tela inicial. Segue a especificação em [`ROTA_DOS_PARCEIROS_ESPECIFICACAO.md`](ROTA_DOS_PARCEIROS_ESPECIFICACAO.md) e descreve o que a tela mostra, como se comporta e como se adapta a cada tamanho de tela. Não detalha a implementação.

---

## 1. Princípios

- **Uma olhada basta:** no carro, o motorista precisa ver em poucos segundos quanto ganhou, quanto sobrou e quanto falta para a meta.
- **Offline primeiro:** tudo na tela funciona sem internet, exceto **notícias e clima** (pesquisa externa — spec 2.5). Sem internet, esses cards mostram o último dado salvo.
- **Dados só do celular:** a tela lê os dados locais (turno, ganhos, gastos, metas). A sincronização com o Firebase acontece em segundo plano e nunca bloqueia a tela (spec 2.6).
- **Mesma linguagem da orb:** o atalho do turno usa os mesmos estados e o mesmo contador da orb (spec 3.1).
- **Luna presente, sem atrapalhar:** o avatar animado da Luna — mulher **estilizada e adulta**, com **boné LUNA**; não é foto, robô nem criança (spec 1.3) — aparece no cabeçalho e dá voz aos alertas.

---

## 2. Paleta e papéis das cores

Só as cinco cores da spec, com variações de transparência quando preciso.

| Cor | Código | Papel na tela |
|---|---|---|
| Obsidian | `#07060E` | **Fundo** da tela. Fundo dos cards = obsidian com leve brilho violeta (ex.: violeta a 8–12% sobre obsidian). Texto sobre botões cheios em violeta, ciano ou esmeralda. |
| Branco gelo | `#F3F0FF` | **Texto principal** (valores, títulos). Texto secundário = branco gelo a 70%. Rótulos desabilitados = branco gelo a 40% (só para texto não essencial). |
| Violeta elétrico | `#8D6BFF` | **Destaques e ações:** botão principal (iniciar/pausar), ícones ativos, bordas de foco, título dos cards, card do **alerta motivacional** (borda e brilho). |
| Ciano gelo | `#49E0FF` | **Informação e tempo:** contador do turno, **custo de energia**, clima, links de notícias, avisos informativos (offline, ganho por hora caindo). |
| Esmeralda | `#3EE0A0` | **Progresso e resultado positivo:** barras de meta, valor **líquido** positivo, meta batida. |

**Regras**
- **Alertas** usam ícone + texto, nunca só cor. Como a paleta não tem vermelho/amarelo:
  - alerta **motivacional** (perto da meta / meta batida) → violeta + esmeralda;
  - aviso **informativo** (offline, ganho por hora caindo, dado desatualizado) → ciano com ícone ⓘ;
  - valor **negativo** (ex.: líquido abaixo de zero) → branco gelo com sinal "−" e ícone ▼, sem cor de "erro".
- Valores em dinheiro sempre em `R$ 0,00` (padrão brasileiro).

### Contraste (WCAG) sobre obsidian `#07060E`

| Texto | Contraste | Uso permitido |
|---|---|---|
| Branco gelo `#F3F0FF` | 17,97 : 1 | qualquer tamanho |
| Ciano gelo `#49E0FF` | 12,86 : 1 | qualquer tamanho |
| Esmeralda `#3EE0A0` | 11,88 : 1 | qualquer tamanho |
| Violeta `#8D6BFF` | 5,45 : 1 | qualquer tamanho (≥ 4,5 : 1); preferir para títulos e ícones |
| Obsidian sobre violeta | 5,45 : 1 | texto de botão violeta cheio |

---

## 3. Estrutura geral da tela

De cima para baixo (ordem de leitura e de foco no leitor de tela):

0. **Cabeçalho** — avatar da Luna (animado), saudação ("Bom dia, Marlon"), data, indicador de sincronização (ícone discreto: sincronizado / pendente / offline).
1. **Atalho do turno** (componente 4) — fica no topo porque é a ação mais usada.
2. **Resumo do dia** (componente 1).
3. **Metas** (componente 2).
4. **Ganhos por app** (componente 3).
5. **Notícias e clima** (componente 5).
6. **Dica do dia + mensagem bíblica** (componente 6).

> A numeração dos componentes segue o pedido do Marlon; a **ordem na tela** coloca o atalho do turno primeiro.

---

## 4. Componentes

### 4.1 Resumo do dia (componente 1)

- **Objetivo:** mostrar quanto o motorista ganhou e quanto realmente sobrou hoje.
- **Conteúdo:**
  - **Bruto** = soma de todas as corridas do dia (99 + Uber + InDrive).
  - **Gastos do dia** = energia + manutenção + outros gastos lançados.
  - **Líquido** = bruto − gastos do dia.
  - **Custo de energia do dia** = custo da **recarga elétrica do Geely** (não combustível). Vem do valor que o Marlon informou **ou** é calculado a partir dos **kWh lançados** (spec 2.4). Mostrar a origem em texto pequeno: "informado" ou "calculado: 18 kWh".
  - **Ganho por hora** = líquido ÷ tempo de trabalho (sem pausa e café).
- **Visual:** card largo. Bruto e líquido **lado a lado, mesmo tamanho** (spec: "bruto e líquido aparecem juntos"). Líquido em esmeralda quando positivo. Abaixo, uma linha com "⚡ Energia R$ 32,40" em ciano e "🔧 Manutenção R$ 0,00". Ganho por hora em texto secundário.
- **Interações:**
  - Tocar no card → abre o detalhamento de ganhos e gastos do dia.
  - Tocar em "Energia" → abre o lançamento de recarga (valor em R$ ou kWh).
  - Botão "+ Gasto" → lançar gasto rápido (energia, manutenção, outro).
  - **Sugestão de parar** (spec 2.4): quando o ganho por hora cair, aparece uma faixa ciano dentro do card: "ⓘ Seu ganho por hora caiu para R$ 18/h. Que tal encerrar?" com botões "Fechar turno" e "Agora não".
- **Sem dados do dia:** valores em `R$ 0,00` (não traço), líquido em branco gelo, e o texto "Nenhuma corrida hoje ainda. Inicie o turno ou lance um ganho." com botão "+ Lançar ganho". Energia mostra "Nenhuma recarga lançada" com link "Lançar recarga".

### 4.2 Metas — dia, semana e mês (componente 2)

- **Objetivo:** mostrar quanto falta para bater cada meta.
- **Conteúdo:** três linhas — **Dia**, **Semana**, **Mês**. Cada uma tem: valor atingido / valor da meta, **percentual** e quanto falta ("faltam R$ 45").
- **Base do cálculo:** o progresso usa o **líquido** (o que sobra no bolso), com o bruto exibido em texto secundário. *(Ver questões em aberto.)*
- **Visual:** barra horizontal por meta, trilho em branco gelo a 15%, preenchimento em **esmeralda**. Percentual à direita em branco gelo. Barra com cantos arredondados, altura mínima de 8dp.
- **Alerta motivacional (spec 2.4):**
  - **≥ 80%** da meta → barra ganha brilho violeta e aparece o card de alerta: "🔥 Falta pouco! Mais R$ 40 e você bate a meta do dia." A Luna pode falar a frase (se a voz estiver ligada).
  - **≥ 100%** → barra cheia em esmeralda com ícone ✓ e "Meta do dia batida! 🎉". Valor acima da meta aparece como "+R$ 25 acima".
  - Cada alerta aparece **uma vez por meta por limiar** (80% e 100%) para não repetir a cada corrida; fica visível no card até ser dispensado.
- **Interações:** tocar numa meta → editar o valor da meta. Tocar em "Dispensar" no alerta → esconde o alerta (o progresso continua visível).
- **Sem dados do dia:** barras vazias em 0% com "faltam R$ X" (valor total da meta). **Sem meta definida:** a linha mostra "Defina sua meta do dia" com botão "Definir meta", sem barra.

### 4.3 Ganhos por app (componente 3)

- **Objetivo:** comparar quanto cada aplicativo rendeu hoje.
- **Conteúdo:** três itens — **99**, **Uber**, **InDrive** — cada um com valor do dia, número de corridas e participação no bruto (%).
- **Visual:** três blocos iguais lado a lado (telefone retrato: lista vertical compacta de 3 linhas). Nome do app em texto (sem logos de terceiros), valor em branco gelo grande, barra fina de participação em violeta. O app que mais rendeu ganha borda violeta.
- **Interações:** tocar num app → lista de corridas/lançamentos daquele app no dia. Botão "+" em cada bloco → lançar ganho naquele app (aceita voz: a Luna pergunta "é o total ou um valor a mais?" — spec 2.2).
- **Sem dados do dia:** os três blocos aparecem com `R$ 0,00` e "0 corridas", em branco gelo a 70%, com o "+" visível.

### 4.4 Atalho iniciar/pausar turno (componente 4)

- **Objetivo:** controlar o turno sem sair da tela inicial.
- **Conteúdo:** **contador do tempo de trabalho** (só trabalho, sem pausa e café — igual ao contador da orb), **estado** do turno e **minutos parado** em texto secundário.
- **Estados:**

| Estado | Contador | Botão principal | Ações secundárias |
|---|---|---|---|
| **Sem turno** | `00:00:00` (branco gelo a 40%) | **▶ Iniciar turno** (violeta cheio) | Calendário (lançar dia esquecido) |
| **Trabalhando** | correndo, em ciano | **⏸ Pausar** | ☕ Café · Fechar turno |
| **Pausado** | parado, ciano a 60%; selo "Pausado" | **▶ Retomar** | ☕ Café · Fechar turno |
| **Café** | parado; selo "☕ Café" | **▶ Retomar** | Fechar turno |
| **Turno fechado** | tempo final, parado | **Concluir dia** | Corrigir (reabre a tela de correção) · Zerar |

- **Regras (spec 2.1):**
  - Pausa e café somam em **minutos parado** ("Parado: 25 min") e **não** entram no tempo de trabalho.
  - **Fechar turno** encerra o turno e sempre abre a **tela de correção** (ganhos, gastos, início e fim).
  - **Concluir** finaliza o dia e salva o resumo no histórico; a tela volta ao estado "Sem turno" com os valores do dia zerados (o histórico guarda o dia).
  - **Zerar** pede confirmação ("Os ganhos continuam no histórico") antes de recomeçar.
- **Visual:** card de destaque no topo, contador grande (fonte monoespaçada para os dígitos não "pularem"), botão principal com no mínimo 56dp de altura.
- **Interações:** toque no botão principal muda o estado. Ações secundárias como botões menores (≥ 48dp). Microfone ao lado: falar "pausar", "café", "fechar turno".
- **Sem dados do dia:** é o estado "Sem turno", com o texto "Pronto para começar?".

### 4.5 Notícias e clima de Governador Valadares (componente 5)

- **Objetivo:** informar o que afeta o dia de trabalho — tempo e trânsito/estrada.
- **Conteúdo:**
  - **Clima:** temperatura atual, ícone do tempo, mínima/máxima, chance de chuva das próximas horas.
  - **Notícias:** 3 manchetes mais recentes do **Diário do Rio Doce** e avisos da **BR-381** (interdições, acidentes, obras), cada uma com fonte e horário.
- **Visual:** card com clima à esquerda (temperatura grande em branco gelo, ícone em ciano) e lista de manchetes à direita/abaixo. Avisos da BR-381 com etiqueta "BR-381" em ciano.
- **Interações:** tocar numa manchete → abre a notícia (precisa de internet). Puxar a tela para baixo → atualizar. Tocar no clima → previsão do dia por hora.
- **Offline / sem internet:** mostra **o último dado salvo** com aviso ciano: "ⓘ Sem internet — última atualização às 07:42". Manchetes continuam visíveis; abrir a notícia completa mostra "Disponível quando houver internet".
- **Sem dados (nunca carregou e sem internet):** "Notícias e clima aparecem quando houver internet." Sem erro, sem tela em branco.

### 4.6 Dica do dia + mensagem bíblica (componente 6)

- **Objetivo:** apoio prático e espiritual para o dia.
- **Conteúdo:**
  - **Dica do dia:** uma dica curta para motorista (ex.: horários de pico, cuidados com a bateria do carro elétrico, economia).
  - **Mensagem bíblica:** um versículo com a referência (ex.: "Salmos 121:8").
- **Visual:** card com duas partes separadas por linha fina. Dica com ícone 💡 em violeta; versículo em itálico, branco gelo, referência em texto secundário.
- **Interações:** tocar em 🔊 → a Luna lê em voz alta. Botão "Compartilhar" no versículo (opcional). Muda uma vez por dia.
- **Offline:** funciona offline — dicas e versículos ficam guardados no celular.
- **Sem dados:** sempre há conteúdo (lista local); se faltar, mostra a dica/versículo do dia anterior.

---

## 5. Layout responsivo

Grade baseada em colunas com margens de 16dp (telefone) e 24dp (tablet); espaço entre cards de 12dp (telefone) e 16dp (tablet). A tela rola na vertical quando o conteúdo não cabe.

### 5.1 Telefone — retrato (1 coluna)

Ordem: Cabeçalho → Turno → Resumo → Metas → Ganhos por app → Notícias e clima → Dica + versículo.

```
┌──────────────────────────────┐
│ (Luna) Bom dia, Marlon  ⟳ ●  │
│ dom, 18 out                  │
├──────────────────────────────┤
│ TURNO          ● Trabalhando │
│        02:14:37              │
│ Parado: 25 min               │
│ [ ⏸ Pausar ] [☕] [Fechar]    │
├──────────────────────────────┤
│ RESUMO DO DIA                │
│ Bruto        │ Líquido       │
│ R$ 210,00    │ R$ 177,60     │
│ ⚡ Energia R$ 32,40 (18 kWh)  │
│ 🔧 Manutenção R$ 0,00  [+Gasto]│
│ R$ 79/h                      │
├──────────────────────────────┤
│ METAS                        │
│ Dia    ████████░░  82% R$40  │
│ Semana ████░░░░░░  41%       │
│ Mês    ██░░░░░░░░  18%       │
│ 🔥 Falta pouco! Mais R$ 40…  │
├──────────────────────────────┤
│ GANHOS POR APP               │
│ 99       R$ 90,00   6 corr.  │
│ Uber     R$ 80,00   4 corr.  │
│ InDrive  R$ 40,00   2 corr.  │
├──────────────────────────────┤
│ GV  29°  ☁ chuva 40%         │
│ • Diário do Rio Doce: …      │
│ • BR-381: obra km 3xx …      │
├──────────────────────────────┤
│ 💡 Dica do dia …             │
│ "…" — Salmos 121:8     🔊    │
└──────────────────────────────┘
```

### 5.2 Telefone — paisagem (2 colunas)

Esquerda (fixa, ~40%): Turno + Resumo do dia. Direita (rola, ~60%): Metas → Ganhos por app → Notícias e clima → Dica + versículo. Cabeçalho reduzido a uma linha (avatar menor).

```
┌───────────────────────────────────────────────────────┐
│ (Luna) Bom dia, Marlon · dom, 18 out            ⟳ ●   │
├──────────────────────┬────────────────────────────────┤
│ TURNO  ● Trabalhando │ METAS  Dia ████████░░ 82%      │
│      02:14:37        │        Sem ████░░░░░░ 41%      │
│ [⏸ Pausar][☕][Fechar]│        Mês ██░░░░░░░░ 18%      │
├──────────────────────┤ GANHOS  99 R$90 │Uber R$80│InD R$40│
│ Bruto R$210 │Líq R$177│ GV 29° ☁ · Diário do Rio Doce… │
│ ⚡ R$ 32,40  R$ 79/h  │ 💡 Dica …  "…" Sl 121:8         │
└──────────────────────┴────────────────────────────────┘
```

### 5.3 Tablet — retrato (2 colunas)

Linha 1 (largura total): Turno. Linha 2: Resumo do dia | Metas. Linha 3 (largura total): Ganhos por app em 3 blocos lado a lado. Linha 4: Notícias e clima | Dica + versículo.

```
┌──────────────────────────────────────────────┐
│ (Luna)  Bom dia, Marlon · dom, 18 out   ⟳ ●  │
├──────────────────────────────────────────────┤
│ TURNO ● Trabalhando   02:14:37   Parado 25min │
│ [ ⏸ Pausar ]  [ ☕ Café ]  [ Fechar turno ]   │
├──────────────────────┬───────────────────────┤
│ RESUMO DO DIA        │ METAS                 │
│ Bruto    │ Líquido   │ Dia    ████████░░ 82% │
│ R$210,00 │ R$177,60  │ Semana ████░░░░░░ 41% │
│ ⚡ R$32,40 🔧 R$0,00  │ Mês    ██░░░░░░░░ 18% │
├──────────┬───────────┴──┬────────────────────┤
│ 99       │ Uber         │ InDrive            │
│ R$ 90,00 │ R$ 80,00     │ R$ 40,00           │
├──────────┴───────────┬──┴────────────────────┤
│ CLIMA + NOTÍCIAS GV  │ 💡 DICA + VERSÍCULO   │
└──────────────────────┴───────────────────────┘
```

### 5.4 Tablet — paisagem (3 colunas)

Coluna 1: Turno + Resumo do dia. Coluna 2: Metas + Ganhos por app. Coluna 3: Notícias e clima + Dica + versículo. Em tablet paisagem a tela inteira deve caber sem rolar.

```
┌──────────────────────────────────────────────────────────────┐
│ (Luna)  Bom dia, Marlon · dom, 18 out                  ⟳ ●   │
├───────────────────┬────────────────────┬─────────────────────┤
│ TURNO 02:14:37    │ METAS              │ GV 29° ☁ chuva 40%  │
│ ● Trabalhando     │ Dia    ████████ 82%│ • Diário do Rio Doce│
│ [⏸][☕][Fechar]    │ Semana ████░░░ 41% │ • BR-381 …          │
├───────────────────┤ Mês    ██░░░░░ 18% │                     │
│ RESUMO DO DIA     ├────────────────────┼─────────────────────┤
│ Bruto  R$ 210,00  │ 99      R$ 90,00   │ 💡 Dica do dia      │
│ Líq.   R$ 177,60  │ Uber    R$ 80,00   │ "…" — Salmos 121:8  │
│ ⚡ R$ 32,40  79/h  │ InDrive R$ 40,00   │                 🔊  │
└───────────────────┴────────────────────┴─────────────────────┘
```

### 5.5 Regras gerais de adaptação

- Classes de largura: **compacta** (< 600dp, telefone retrato) = 1 coluna; **média** (600–839dp, telefone paisagem/tablet retrato) = 2 colunas; **expandida** (≥ 840dp, tablet paisagem) = 3 colunas.
- O **atalho do turno** é sempre o primeiro item visível, em qualquer orientação.
- Ao girar a tela, nada é perdido: estado do turno, rolagem e alertas abertos continuam.
- Textos longos (manchetes) truncam em 2 linhas com "…".

---

## 6. Relação com a orb

- Ao **minimizar** o app a partir da tela inicial, a orb aparece com o mesmo contador e estado do atalho do turno (spec 3.1).
- **Dois toques** na orb voltam para esta tela.
- Ações feitas pelo menu da orb (iniciar, pausar, café, zerar, fechar turno, concluir) aparecem atualizadas aqui ao voltar para o app.

---

## 7. Acessibilidade

- **Contraste:** todas as combinações da seção 2 passam de 4,5 : 1. Não usar texto em branco gelo abaixo de 70% de opacidade para informação essencial.
- **Toque:** todo alvo de toque tem **no mínimo 48 × 48dp**; o botão principal do turno tem no mínimo 56dp de altura. Espaço mínimo de 8dp entre alvos.
- **Não depender de cor:** estados e alertas sempre com ícone + texto (ex.: "⏸ Pausado", "ⓘ Sem internet", "▼ −R$ 12,00").
- **Leitor de tela (TalkBack):** cada card tem título; valores lidos por extenso ("líquido: cento e setenta e sete reais e sessenta centavos"); o contador anuncia mudanças de estado, não cada segundo.
- **Tamanho de fonte:** respeita a fonte do sistema até 200% sem cortar valores; em fonte grande, cards em 2 colunas viram 1 coluna.
- **Movimento:** a animação da Luna e o brilho do alerta respeitam a opção "remover animações" do Android.
- **Voz:** as ações do turno e os lançamentos podem ser feitos por voz (spec 2.2).

---

## 8. Critérios de pronto

- [ ] A tela usa só as cinco cores da paleta, com os papéis da seção 2.
- [ ] O atalho do turno é o primeiro item em todos os layouts e mostra os 5 estados da seção 4.4.
- [ ] O contador do atalho e o da orb mostram sempre o mesmo valor (só tempo de trabalho; pausa e café ficam em "minutos parado").
- [ ] Bruto e líquido aparecem lado a lado; líquido = bruto − gastos (energia, manutenção, outros).
- [ ] Custo de energia do dia aparece com a origem ("informado" ou "calculado por kWh").
- [ ] Metas de dia, semana e mês mostram % e barra; alerta motivacional aparece ao atingir 80% e ao bater 100%, uma vez por limiar.
- [ ] Ganhos por app mostram 99, Uber e InDrive com valor e corridas do dia.
- [ ] A sugestão de parar aparece quando o ganho por hora cai.
- [ ] Sem internet, tudo funciona, exceto notícias e clima, que mostram o último dado salvo com o horário da última atualização.
- [ ] Todo componente tem estado vazio definido; nenhum card fica em branco ou mostra erro sem dados do dia.
- [ ] Os 4 layouts (telefone retrato/paisagem, tablet retrato/paisagem) seguem a seção 5; girar a tela não perde estado.
- [ ] Alvos de toque ≥ 48dp; contraste ≥ 4,5 : 1; tudo navegável pelo TalkBack.
- [ ] O avatar da Luna segue a spec 1.3 (mulher estilizada adulta com boné LUNA).

---

## 9. Questões em aberto

1. **Limiar do alerta motivacional:** a spec só diz "perto da meta". Este documento usa **80%** (e um segundo alerta em 100%). O Marlon confirma ou prefere outro valor (ex.: 90%)?
2. **Meta sobre bruto ou líquido?** Este documento calcula o progresso pelo **líquido**. Confirmar.
3. **Quando o ganho por hora "cai":** a spec não define o gatilho da sugestão de parar (ex.: abaixo de um valor fixo em R$/h, ou X% abaixo da média do turno).
4. **Início da semana e do mês nas metas:** semana começa na segunda ou no domingo? A meta semanal/mensal é definida pelo Marlon ou calculada a partir da meta diária?
5. **Fonte dos avisos da BR-381:** a spec cita a BR-381, mas não diz de onde vêm os avisos (ex.: PRF, concessionária, notícias do Diário do Rio Doce).
6. **Preço do kWh:** para calcular o custo de energia pelos kWh, de onde vem o preço por kWh (valor fixo informado pelo Marlon ou por recarga)?

---

## 10. Decisões da implementação (v0.1.3)

Respostas provisórias às questões em aberto (seção 9), escolhidas pela opção mais simples. Podem ser trocadas depois.

1. **Limiar do alerta motivacional:** 80% e 100%, uma vez por meta, por período e por limiar. Ao bater 100%, o alerta de 80% daquele período é dado como visto.
2. **Meta sobre bruto ou líquido:** líquido (bruto − gastos). O bruto aparece no texto do leitor de tela.
3. **Sugestão de parar:** aparece com o turno trabalhando, depois de 2 h de trabalho, quando o ganho por hora líquido fica abaixo de **R$ 20/h**. "Agora não" esconde por 1 hora.
4. **Semana e mês:** a semana começa na **segunda-feira**; o mês é o mês do calendário. As três metas (dia, semana, mês) são definidas pelo Marlon, cada uma separada, e a última definida vale até ser trocada.
5. **Avisos da BR-381:** busca do Google Notícias por "BR-381" + Valadares (últimos 3 dias). As manchetes do Diário do Rio Doce vêm do Google Notícias filtrado pelo site drd.com.br. O clima vem da Open-Meteo (sem chave).
6. **Preço do kWh:** informado pelo Marlon na hora de lançar a recarga por kWh; o último preço fica salvo e vem preenchido na próxima vez.

Outras escolhas:
- **Concluir** e **Zerar** levam o turno para o histórico (estado CONCLUIDO) e o painel volta para "Sem turno" com o resumo zerado. Zerar pede confirmação e não passa pela correção.
- **Fechar turno** liga ao turno os lançamentos do dia feitos sem turno e sempre abre a tela de correção (início, fim, ganhos e gastos).
- O **resumo do dia** e os **ganhos por app** mostram o dia atual até o turno ser concluído; as **metas** somam todos os lançamentos do período, mesmo de turnos concluídos.
- **Puxar para atualizar** virou um botão ⟳ no card de notícias.
- **Voz:** o microfone usa o ditado do Android na tela "Conversar com a Luna" ("30 na Uber" → a Luna pergunta "é o total ou a mais?"; "pausar", "café", "retomar", "iniciar turno"). Os alertas e a dica podem ser lidos em voz alta (texto para fala do Android).
- **Orb flutuante** (spec 3) ainda não foi feita: o avatar da Luna aparece como orb animado no cabeçalho e na conversa.
- Tablet/central paisagem: cada coluna rola sozinha se o conteúdo não couber na altura da tela.
- Em telas deitadas com menos de 480dp de altura (telefone), o layout usa 2 colunas, mesmo quando a largura passa de 840dp.


---

## 11. v0.1.4 — "Bonito 3D" (substitui o que conflitar acima)

### 11.1 Paleta neon ampliada
A paleta de cinco cores ganhou cores de apoio (dados, apps e alertas):

| Cor | Código | Uso |
|---|---|---|
| Magenta | `#FF4FD8` | Gogeta, aurora, degradê do card da Luna, ganho/hora |
| Laranja | `#FF9F43` | 99, metas, contas fixas |
| Coral | `#FF5C7A` | gastos, limite estourado, variação negativa |
| Amarelo | `#FFE15A` | Particular |

Cores dos apps: 99 = laranja, Uber = ciano, InDrive = esmeralda, Gogeta = magenta, Particular = amarelo — iguais em cards, rosca, ranking (selo com sigla), barras empilhadas, tabela e histórico. O valor negativo continua com "−" e ▼ (agora também em coral).

### 11.2 Fundo e movimento
- **Galáxia animada:** nebulosas (degradês radiais), 3 faixas de aurora (curvas suaves em 3 passadas de traço, sem blur), 90 estrelas piscando; ~24 fps, pausa quando invisível; **parada** com "reduzir animações".
- Cards de **vidro** com brilho de borda e sombra colorida (3D); vários cards com **preenchimento em degradê** (KPIs, relatórios).
- Entrada em cascata dos cards, gráficos que se desenham, valores com contagem; tudo desligado em "reduzir animações".

### 11.3 Componentes novos
- **Card da Luna** (abaixo do cabeçalho): degradê animado rosa/roxo/ciano, orb, "Fale com a Luna…", microfone e atalho para a conversa. 1 toque = voz inline; 2 toques = conversa.
- **Faixa de KPIs:** Total líquido, Corridas, Ganho/hora, Meta — com pílula de variação (vs ontem) e selo **AO VIVO** pulsando no cabeçalho.
- **Turno N/4:** orb 3D da Luna, cronômetro, botões por fase (ver especificação 2.1).
- **Relatórios do mês:** carrossel automático (3,5 s; pausa 6 s ao tocar) → relatório completo.
- **Seletor de período:** Hoje / Semana / Mês / Ano.
- **Gráficos:** área com brilho (líquido 7 dias, pico marcado) e barras 3D empilhadas por app.
- **Ranking dos apps** (selo, valor, corridas, %, barra 3D) e **Divisão do período** (roscas 3D ganhos/gastos).
- **Calendário** com pontos verdes; toque abre o dia.
- **Metas** (vêm do Planejamento) e **Limites do mês** (gasto/limite, "Ainda pode gastar", vermelho ao estourar).
- **Últimos lançamentos** (tabela data/tipo/origem/valor).

### 11.4 Layouts
- **Telefone retrato:** Luna, KPIs 2×2, turno, relatórios, período, gráficos, ranking, roscas, calendário, metas, limites, resumo, lançamentos, notícias, dica.
- **Telefone paisagem:** esquerda = turno, calendário, resumo, metas, limites; direita = Luna, KPIs 2×2, relatórios, gráficos…
- **Tablet:** Luna e KPIs em 4; turno | metas+limites; relatórios; período; gráficos; ranking | roscas; calendário | resumo; lançamentos | notícias.
- **Central Geely X2 (paisagem, 3 colunas):** Luna e KPIs no topo; col. 1 = turno, calendário, metas, limites, resumo; col. 2 = relatórios, período, gráficos, ranking; col. 3 = roscas, lançamentos, notícias, dica.

### 11.5 Telas novas
- **Configurações / Planejamento** (menu ⋮): dias de trabalho 4×7, ganho/hora, contas fixas, limites, renda desejada e sugestão de metas (Recalcular / Editar / Aceitar).
- **Relatório completo** e **dia do calendário** (4 turnos editáveis, ganhos, gastos; pessoais de limite aparecem à parte).
- **Conversa** com a Luna sobre o fundo de galáxia, com câmera/galeria para nota fiscal e botões de resposta.
