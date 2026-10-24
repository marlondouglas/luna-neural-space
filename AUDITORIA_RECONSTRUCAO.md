# Auditoria e plano — 23/10/2026

Auditoria inicial anterior às alterações desta execução. Referências: MAPA.jpg, Painel Neon Rota GV com Lina.png, PARCEIROS.png e comando mestre anexado.

## Preservação

Backup de app/src, schemas Room, Gradle do app, backend e regras criado em arquivo ZIP na pasta temporária do sistema. Alterações locais anteriores, inclusive arquivos já preparados no Git, preservadas. Nenhum reset, limpeza destrutiva, publicação ou alteração de assinatura.

## Achados confirmados

| Área | Evidência | Risco e ação |
|---|---|---|
| Mapa | MapRadioActivity contém motoristas, alertas, recargas e percurso ilustrativos; fallback desenha destino arbitrário | Retirar simulações e não apresentar linha reta como rota |
| Layout | Painéis de 240/250 dp e mapa vertical de 280 dp; topo e rodapé sem rolagem | Mapa flexível, painéis recolhíveis, insets e barras com rolagem |
| Parceiros | Tela local não rastreada replica preços, contagens e conversas da imagem | Preservar estrutura, substituir conteúdo por catálogo rastreável e estados desconhecidos |
| Rádio | startTalking permite retorno silencioso sem sala; sem tratamento explícito de reconexão; envio é exibido antes do sucesso | Validar conexão, confirmar microfone pelo SDK e enviar antes de mostrar sucesso |
| Duplicações | MapActivity, MapRadioActivity e RadioActivity têm funções parcialmente sobrepostas | São entradas referenciadas; não apagar. Consolidar controles de segurança no manager compartilhado |
| Serviços | Manifest registra uma única VoiceRoomService e um FileProvider | Nenhuma duplicação confirmada |
| Luna | LunaMotor é animação, não interpretação. LunaConversa executa comandos locais; LocalCerebro consulta; RemotoCerebro usa backend | Ampliar esses contratos sem criar um segundo motor concorrente |
| Dinheiro | Entidades já usam Long em centavos; Room v3 possui migrations 1→2→3 e Flow | Preservar migrations e cálculo de envelopes por limiteId |
| Limites | Plano e LimitesCard já calculam e exibem gasto/saldo por envelope | O parser não associa despesas digitadas aos envelopes; corrigir na execução local |
| Sincronização | UUID, dirty, tombstones, transações, ouvintes e WorkManager existem | Resolução por última escrita pode descartar alterações locais; não afirmar ausência de perdas |
| Aparelhos | Aparelho gera ID; regras Firestore só isolam por UID | Limite de três aparelhos exige backend e regras novas, não apenas UI |
| Firebase | app/google-services.json ausente; arquivo Downloads tem pacote space.neural.luna | Validar configuração local; isso não comprova login, regras ou sincronização real |
| IA online | Worker já chama Gemini e reserva Workers AI; segredos definidos no ambiente do Worker | Não colocar chave no APK; modelo local generativo não instalado/verificado |
| Tokens | HttpTokenProvider não envia autenticação e aceita endpoint não HTTPS | Exigir HTTPS e enviar identidade autenticada; revisar autorização no Worker |
| GitHub | Fetch concluído; origin/fix/0.1.12-correcoes em f8e67c0 adiciona Parceiros com dados estáticos | Comparado sem substituir implementações locais; preços do remoto não constituem telemetria |
| Recursos | Ícones vetoriais e avatar local já existem; temas e UI programática | Reutilizar; não copiar logos de terceiros |

## Ordem de execução

1. Preservação, auditoria e comparação Git (acima).
2. Remover dados fictícios e sucessos falsos; corrigir segurança de PTT e mensagens.
3. Tornar mapa responsivo, busca/rota honestas e navegação interna incremental.
4. Ampliar interpretação offline, confirmação, persistência e desfazer; testar envelopes independentes.
5. Documentar fontes e integrações autorizadas. Não criar adaptadores de operador sem API documentada.
6. Executar testes existentes e novos, build consistente e validação visual quando dispositivo estiver disponível.

## Matriz visual inicial

| Referência | Diferença no código atual | Correção prevista |
|---|---|---|
| Mapa horizontal | Painéis cobrem a maior parte em telefone deitado | Recolher painéis em telas compactas |
| Mapa vertical | Mapa fixo com controles apertados | Mapa ocupa espaço restante; painéis abertos sob demanda |
| Topo e dock | Muitos controles cortados | Rolagem horizontal e alvos acessíveis |
| PTT | Cor muda com chamada que pode não transmitir | Estado baseado no manager e bloqueio desconectado |
| Parceiros | Foto contém números ilustrativos | Manter identidade neon e exibir apenas informação com fonte |
| Inicial | Dashboard existente tem KPIs reativos e avatar | Preservar e acrescentar acessos claros |

## Pendências externas

Áudio entre dois aparelhos físicos, revogação/limite backend de aparelhos, login real, sincronização real, telemetria OCPI e tarifas de operadores requerem infraestrutura/configuração e validação. Não estão confirmados por esta auditoria. Não contratar serviços nem publicar backend automaticamente.
