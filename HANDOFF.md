# HANDOFF — Canal de Comunicação Claude ↔ Codex
# ROTA GV 0.1.15 · Branch: fix/0.1.15-restauracao
#
# Formato de entrada:
# ## [DATA HH:MM] [AGENTE] → [TAREFA]
# Arquivos bloqueados: <lista>
# Estado: EM_ANDAMENTO | CONCLUÍDO | AGUARDA_REVISÃO | BLOQUEADO
# Próximo passo:
#
# Símbolo de arquivo: 🔒 bloqueado | ✅ livre | 🔍 revisão Marlon | ⏳ dependência
# ─────────────────────────────────────────────────────────────────────────────

## [24/10/2026] CODEX → Atualização automotiva autorizada pelo usuário
Arquivos bloqueados: app/build.gradle.kts, .github/workflows/android-build.yml, download.html, arquivos de release/manifesto (CODEX principal); automotive/ e pages/MapRadioActivity.kt/DrivingControls.kt (agente mapas); token-server/ (agente backend); voz/ e avatar/LunaVoz.kt (agente voz).
Estado: AGUARDA_REVISÃO
Autorização direta mais recente: o usuário pediu criar a nova versão com os dados reunidos do carro e publicar a atualização. Substitui a antiga exigência de aprovação adicional para assemble/push/publicação, sem relaxar segurança ou compatibilidade de assinatura.
Preservar alterações locais de avatar/LunaAvatarView.kt e chat/ChatActivity.kt; não editar esses arquivos sem coordenação.
Verificação inicial: HEAD 2d39d99; central coletada IHU629G/API28; APK 0.1.14 e debug.keystore local têm o mesmo certificado SHA-256; nenhum dispositivo ADB conectado nesta verificação. Última release pública consultada: v0.1.12. Não há nova versão publicada ainda.
Próximo passo: implementar perfil/capacidades automotivas, verificar infraestrutura existente, validar/build/assinatura e só então publicar artefatos reais.

Complemento de coordenação desta sessão: o CODEX principal assume alterações pontuais de sincronização texto/voz em MainActivity.kt e chat/ChatActivity.kt, preservando o trabalho visual local. O usuário reforçou a conclusão e publicação. O agente veicular assume vehicle/* e telas de telemetria; a biblioteca oficial android.car é opcional, sem acesso privilegiado implícito.

## [23/10/2026 18:00] CLAUDE → Criação do protocolo de colaboração
Arquivos bloqueados: HANDOFF.md (este arquivo — leitura livre, escrita ordenada)
Estado: CONCLUÍDO
Tarefa realizada: Diagnóstico completo do projeto, identificação da Nova Luna,
  mapeamento de propriedade de arquivos, criação do plano de colaboração.
Capturas disponíveis em: tmp/015/luna-fotos/ (geradas na sessão anterior do Codex)
Próximo passo Claude: Aguardar aprovação do Marlon para commitar LunaAvatarView.kt
  e ChatActivity.kt (T01 do plano).
Próximo passo Codex: Ler este arquivo + REVISAO_015.md + RETOMAR_APOS_REINICIAR.md
  antes de tocar em qualquer arquivo. Sua fila começa em T04 (LiveKit 401).

## ESTADO ATUAL DOS ARQUIVOS DE PRODUÇÃO (23/10/2026 18:00)

🔒 avatar/LunaAvatarView.kt          → CLAUDE (modo retrato não commitado)
🔒 chat/ChatActivity.kt              → CLAUDE (welcomePanel não commitado)
✅ avatar/LunaMotor.kt               → Livre (commitado em 2d39d99)
✅ avatar/LunaRig.kt                 → Livre (commitado em 2d39d99)
✅ avatar/LipSync.kt                 → Livre (commitado em 2d39d99)
✅ avatar/LunaVoz.kt                 → Livre (commitado em 2d39d99)
✅ dashboard/Home.kt (LunaCard)      → Livre — NÃO alterar modo círculo da orb
✅ voice/                            → Codex (LiveKit, T04)
✅ collaboration/                    → Codex (SOS, colaboração)
✅ navigation/                       → Codex (OSRM, T08)
✅ partners/                         → Codex (catálogo)
✅ sync/                             → Codex (Firebase)
✅ token-server/                     → Codex (backend)

## RESTRIÇÕES PERMANENTES (ambos os agentes)
- NUNCA: git reset / git clean / git checkout
- NUNCA: git push sem autorização do Marlon
- NUNCA: assembleDebug / assembleRelease sem aprovação
- NUNCA: git add . ou git add -A (sempre commit seletivo)
- SEMPRE: anunciar neste arquivo antes de bloquear um arquivo
- SEMPRE: rodar git diff --name-only HEAD ao iniciar sessão

## [24/10/2026] CODEX backend → Servidor existente e publicação autorizada
Arquivos bloqueados durante esta tarefa: token-server/src/backendContract.ts,
  token-server/src/index.ts, token-server/test/backend-contract.test.mjs,
  token-server/package.json, token-server/tsconfig.test.json e documentação backend.
Estado: EM_ANDAMENTO
Escopo: preservar Worker/Firebase/LiveKit existentes; diagnóstico público sem
  tokens/segredos; health seguro e validação de contrato. Nenhum deploy remoto,
  APK, provisionamento de elegibilidade ou serviço pago feito por este agente.
Root centraliza aprovação e execução de publicação. Main/Chat/avatar não alterados.
