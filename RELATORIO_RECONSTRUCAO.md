# Relatório de validação — Rota GV 0.1.14

Trabalho iniciado em 23/10/2026 a partir do comando mestre e das três referências. Esta é uma versão para validação, **não a conclusão integral de todas as integrações solicitadas**.

## Resultado por funcionalidade

| Funcionalidade | Classificação | Evidência / limite |
|---|---|---|
| Despesas e recebimentos por texto, offline | FUNCIONANDO E TESTADA | Testes locais de números, variações de escrita, data, confirmação e gravação Room |
| Confirmação sem duplicar | FUNCIONANDO E TESTADA | Repetição do mesmo botão mantém um registro; UUID preservado no upsert |
| Categoria ausente, consulta de limites e desfazer | FUNCIONANDO E TESTADA | Seleção de categoria cadastrada; Mercado e Lazer independentes; exclusão lógica auditada |
| Correção histórica pela Luna | FUNCIONANDO E TESTADA | Busca registros reais, exige seleção e confirmação; teste altera 100 para 80 e desfaz |
| Histórico da conversa após reconstrução da sessão | FUNCIONANDO E TESTADA | Mensagens lidas do Room por nova instância; não é teste de reinstalação |
| Auditoria financeira local | FUNCIONANDO E TESTADA | Registra antes/depois atomicamente com a gravação; cobre criação, alteração e exclusão |
| Migração Room 3→4 | FUNCIONANDO E TESTADA | Banco gerado a partir do schema v3 abriu no Room v4 preservando a despesa original |
| Layout mapa e parceiros | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Testes de layout em retrato, paisagem e central 1280×720; capturas Robolectric não são capturas de aparelho |
| Busca de endereço | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Usa Geocoder do Android; disponibilidade depende do aparelho |
| Rota, manobras, distância, ETA e recálculo | PENDENTE DE API OU AUTORIZAÇÃO | Cliente OSRM implementado; exige ROUTING_BASE_URL de infraestrutura autorizada. Sem teste real de percurso |
| Rádio PTT e chat LiveKit | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Conexão validada antes de transmitir; gesto cancelado/solto invalida ativação pendente; não houve áudio entre dois aparelhos |
| Segurança do token LiveKit | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Cliente usa HTTPS e cabeçalhos; Worker local deriva UID do Firebase. Implantação do Worker pendente |
| Firebase local | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Arquivo fornecido validado para o pacote e incluído localmente; autenticação e Firestore reais não comprovados |
| Sincronização | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Suíte existente usa nuvem em memória; não comprova servidor Firebase real |
| Limite de três aparelhos para sincronização | NÃO IMPLEMENTADA | Licença já limita aparelhos no backend; regras Firestore atuais não fiscalizam esse limite |
| Conflitos financeiros | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Versão substituída preservada em auditoria local; resolução interativa continua pendente |
| Gemini existente | PENDENTE DE CREDENCIAIS | URLs restauradas; exige sessão válida e segredos do Worker. Nenhum segredo foi pedido no chat ou inserido no APK |
| Gemini produzindo intenções estruturadas | NÃO IMPLEMENTADA | A integração existente continua conversacional; execução financeira é local e validada |
| Modelo generativo local | NÃO IMPLEMENTADA | Não foi instalado nem verificado um modelo no aparelho |
| Voz offline | NÃO IMPLEMENTADA | Reconhecimento existente depende de serviço/modelo do Android; não foi validado offline |
| Cadastro público de recarga, busca e filtros | IMPLEMENTADA, MAS NÃO TESTADA EM DISPOSITIVO | Cadastro com fonte; categorias sem dados mostram ausência de resultados |
| Telemetria, tarifas e conectores de operadores | PENDENTE DE API OU AUTORIZAÇÃO | Contrato criado; nenhum adaptador de operador simulado. Expiração UNKNOWN testada |
| Postos e preços ANP | NÃO IMPLEMENTADA | Fonte semanal identificada; importação/catálogo ainda ausentes |
| Ofertas comerciais | PENDENTE DE API OU AUTORIZAÇÃO | Nenhuma oferta/promoção verificada fornecida; nenhuma foi inventada |
| Localização compartilhada de motoristas | NÃO IMPLEMENTADA | Participantes do rádio não recebem posição presumida |
| Alertas no mapa sincronizados | NÃO IMPLEMENTADA | Reportar envia apenas relato não verificado pelo chat quando a publicação é confirmada |
| Criação e catálogo persistente de salas | NÃO IMPLEMENTADA | Seleção das salas existentes preservada; botão de criação informa indisponibilidade |
| Favoritos Casa/Trabalho e recentes de destinos | NÃO IMPLEMENTADA | Endereços ilustrativos retirados; atalhos informam ausência de cadastro |
| Períodos personalizados e alertas configuráveis por categoria | NÃO IMPLEMENTADA | Envelopes mensais existentes preservados; não foi acrescentada configuração de períodos arbitrários |

## Alterações principais

- `app/build.gradle.kts`: versão 0.1.14 (114), configuração de roteamento.
- `MainActivity.kt`, `ChatActivity.kt`: acessos e navegação da Luna.
- `chat/LunaIntentParser.kt`, `chat/LunaNavigationController.kt`, `chat/LunaConversa.kt`: comandos offline, confirmação, correção, histórico e navegação restrita a destinos conhecidos.
- `data/local/Entities.kt`, `Daos.kt`, `LunaDatabase.kt`, `data/repo/Repositories.kt`: auditoria atômica e banco v4; schemas 1–3 preservados, schema 4 acrescentado.
- `data/local/MaquinaTurno.kt`: encerramento do dia anterior limitado à meia-noite, em vez de acumular até a hora atual.
- `sync/SyncEngine.kt`: auditoria de versões financeiras substituídas pela sincronização.
- `pages/MapRadioActivity.kt`: retirada de percurso/participantes/alertas fictícios, layout flexível, controles acessíveis, envio confirmado, navegação.
- `pages/ParceirosActivity.kt`: estrutura local preservada e corrigida; catálogo com fonte, busca, categorias, dados ausentes explícitos, chat sem mensagens inventadas.
- `pages/RadioActivity.kt`, `voice/VoiceRoomManager.kt`, `voice/TokenProvider.kt`: cancelamento de transmissão, conexão/reconexão, HTTPS e autenticação.
- `charging/ChargingModels.kt`: contrato de provedores e expiração de estados.
- `navigation/RouteEngine.kt`: protocolo OSRM, geometria e manobras reais.
- `token-server/src/index.ts`, `licenseRoutes.ts`: identidade de token vinculada a Firebase autenticado.
- `token-server/package.json`: comando de testes portátil no Windows.
- Novos testes: `LunaIntentParserTest`, `LunaFinanceOfflineTest`, `ChargingStatusTest`, `MigrationAuditTest`, `MapPartnerLayoutTest`.

Também foram preservadas alterações anteriores em Manifest, LunaMotor e demais arquivos, bem como itens que já estavam preparados no Git. Nenhum commit ou push foi feito. `app/google-services.json` e `local.properties` continuam ignorados pelo Git.

## Duplicações e dados de demonstração

Removidos construtores de cards ilustrativos de parceiros que repetiam dados sem fonte operacional. Removidas conversas e contagens fixas em mapa/parceiros e o percurso de demonstração. As diferentes entradas MapActivity/RadioActivity foram preservadas porque ainda têm referências; os bloqueios de transmissão ficam no manager compartilhado. Não foi removida dependência apenas por parecer antiga ou redundante.

## Evidências e verificações

- Android: **139 testes descobertos, 138 passaram, zero falhas e um ignorado** (`FirestoreEmuladorTest.doisAparelhosNoEmulador`, exige emulador Firestore externo).
- `:app:testDebugUnitTest :app:assembleDebug`: **BUILD SUCCESSFUL**, última execução em 3m12s.
- APK: `C:\Users\MARLO\luna-neural-space\app\build\outputs\apk\debug\app-debug.apk`.
- Versão: **0.1.14**, código **114**, pacote `space.neural.luna`.
- Gerado em **23/10/2026, 02:26**, tamanho **69.661.680 bytes**.
- SHA-256: `CC0D37D0BDE7034192E39D8142B27220579DA4224AC9CAB4813B16F5567C0EEF`.

- Auditoria anterior às alterações: [AUDITORIA_RECONSTRUCAO.md](AUDITORIA_RECONSTRUCAO.md).
- Pesquisa, contratos e limites de cada integração: [INTEGRACOES_RECONSTRUCAO.md](INTEGRACOES_RECONSTRUCAO.md).
- Log Android: `tmp/reconstruction-build.log`.
- Relatório de testes: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Typecheck backend: `tmp/backend-typecheck.log`.
- Backend: **15 testes passaram**, `tmp/backend-tests.log`.
- Capturas renderizadas: `tmp/validation/*-robolectric.png`. Tiles de mapa são desligados nesses testes; a grade vazia não representa mapa em funcionamento real.
- Tentativa de dispositivo: ADB não encontrou aparelho físico. O AVD existente `Medium_Phone_API_37.0` foi iniciado sem janela, em modo de leitura, com 1536 MB; permaneceu offline. O emulador recomendou 16 GB de RAM para esse AVD, enquanto o computador tem aproximadamente 8 GB e apresentou cerca de 0,5 GB livres durante a tentativa. Somente os processos desse emulador foram encerrados. **Não há captura real nem teste físico concluído.** Logs: `tmp/emulator-validation.log` e `tmp/emulator-validation-error.log`.
- Nenhum teste instrumentado Android estava disponível em `app/src/androidTest`.

## Preservação e segurança

O backup anterior às alterações foi criado como `rota-gv-backup-20261023-015911.zip` na pasta temporária do sandbox, mas não foi localizado ao tentar transferi-lo no encerramento. Não é apresentado como backup recuperável. Um snapshot dos fontes atuais foi salvo permanentemente em `tmp/backups/rota-gv-validacao-0.1.14.zip`; ele contém a versão modificada, não o estado anterior. O histórico Git foi preservado. Nenhuma migração destrutiva, limpeza de dados, mudança de assinatura, criação de conta paga ou publicação de backend/GitHub.

Um artefato temporário pré-existente, `tmp/pdfs/token-check.json`, contém JWT e já estava preparado no índice Git. Foi preservado e não publicado. Deve ser excluído de qualquer publicação e ter sua validade/revogação revisada pelo proprietário; seu conteúdo não foi exposto neste relatório.

## Para concluir as integrações reais

1. Configurar servidor OSRM autorizado e validar cobertura/rotas em GV.
2. Revisar e implantar a correção do Worker existente; validar login e tokens com conta autorizada.
3. Implementar autorização de aparelhos no backend e regras Firestore; testar quatro aparelhos, revogação e conflitos.
4. Obter documentação e autorização de cada operador para telemetria e tarifas.
5. Implementar importação de preços ANP com data e fonte.
6. Validar áudio em dois aparelhos físicos, interrupções, Bluetooth, GPS e perda de rede.

O restante do comando mestre permanece aberto onde indicado; não foi convertido em simulação para preencher a interface.
