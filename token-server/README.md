# Servidor de tokens LiveKit (Cloudflare Worker)

## Continuidade com o servidor existente — 24/10/2026

O Worker, o projeto LiveKit e o Firebase configurados localmente correspondem
aos endereços recuperados da versão 0.1.14. Não substituir o serviço nem criar
outra conta/namespace para corrigir a conexão. O roteiro histórico de criação
abaixo não deve ser repetido em uma instalação já existente.

O contrato local atual exige Firebase ID token em `Authorization: Bearer ...`
para `/token`. A identidade vem do token verificado; `identity` na URL não
permite assumir outro usuário. Tokens de sala continuam emitidos somente no
backend. Nenhuma chave secreta deve ser transferida ao APK.

`GET /health` e `HEAD /health` (também `/healthz`) foram preparados no código:
retornam somente prontidão e capacidades booleanas, sem URLs, credenciais,
email ou token de participante. A prontidão não comprova que a chave/segredo
LiveKit pertençam ao mesmo projeto ou sejam aceitos pela nuvem. O servidor de
produção consultado ainda respondeu 405 a HEAD: a mudança não foi publicada
por este trabalho. Detalhes e sequência de validação em `EXISTING_SERVER_AUDIT.md`.

Gera o token que o app Rota GV usa para entrar na sala de voz.
Roda no plano gratuito do Cloudflare Workers (100 mil requisições por dia).

## Como o app chama

```
GET https://luna-token-server.<sua-conta>.workers.dev/?room=luna-parceiros&identity=<id-do-usuario>
```

Resposta:

```json
{ "serverUrl": "wss://luna-neural-space-vmz1wywd.livekit.cloud", "participantToken": "<token>" }
```

- O nome da sala precisa começar com `luna-` (configurável em `ROOM_PREFIX` no `wrangler.toml`).
- O token vale 4 horas (`TOKEN_TTL_SECONDS`) e permite entrar na sala, falar, ouvir e mandar mensagens.

## Segredos (nunca em arquivo do repositório)

- `LIVEKIT_API_SECRET` (obrigatório): a API Secret do projeto no LiveKit Cloud.
- `APP_SHARED_KEY` (opcional): se for definido, o servidor só responde a quem mandar o header
  `X-App-Key` com esse valor. **O app ainda não manda esse header**, então só ative depois que o app for atualizado.

## Publicar (uma vez)

Precisa do Node.js 22 ou mais novo.

```bash
cd token-server
npm install
npx wrangler login                          # abre o navegador para entrar na conta Cloudflare
npx wrangler deploy                         # mostra a URL *.workers.dev
npx wrangler secret put LIVEKIT_API_SECRET  # cole a API Secret quando pedir
```

Depois, no `local.properties` do app:

```
LIVEKIT_URL=wss://luna-neural-space-vmz1wywd.livekit.cloud
LIVEKIT_TOKEN_ENDPOINT=https://luna-token-server.<sua-conta>.workers.dev/token
```

## Testar no computador

Crie o arquivo `.dev.vars` (fica fora do Git) com um segredo de teste:

```
LIVEKIT_API_SECRET=segredo-de-teste
```

E rode `npx wrangler dev`. Depois abra `http://localhost:8787/token?room=luna-teste&identity=marlon`.

## Conversa com a Luna (`POST /chat`)

```
POST /chat
{"messages":[{"role":"user","content":"Quanto falta pra meta?"}], "context":{...resumo do dia...}}
-> 200 {"reply":"...", "provider":"gemini" | "workers-ai"}
```

- Modelo principal: Gemini (`GEMINI_MODEL`, padrão `gemini-flash-latest`). Em 503/429 tenta `gemini-flash-lite-latest`.
- Reserva automática: Workers AI (binding `AI`, modelo `CHAT_MODEL`).
- Limites: até 12 mensagens, 1000 caracteres cada, contexto até 3000 caracteres, corpo até 32 KB.
- Respeita `APP_SHARED_KEY`/`X-App-Key`, igual ao `/token`.
- A chave do Gemini é segredo do Worker (nunca no repositório):
  `printf '%s' "$GEMINI_API_KEY" | npx wrangler secret put GEMINI_API_KEY`

## Licença: conta + teste grátis de 7 dias + assinatura (v0.1.6)

> **SOS é sempre liberado.** A trava de licença (Worker `requireLicense` e o portão do app em
> `MainActivity.portaoLicenca`/`LicencaActivity`) **nunca** pode bloquear SOS. Quando o Rádio entrar,
> `POST /sos` e qualquer rota/tela de emergência ficam fora da licença: funcionam com teste vencido,
> sem pagamento e mesmo em versão antiga (só autenticação e limite antiabuso).
> Hoje só `/chat`, `/tts` e `/receipt` exigem licença.

Código: `src/license.ts` (regras, assinatura, verificação do ID token do Firebase) e
`src/licenseRoutes.ts` (rotas). Testes: `npm test`.

- `POST /license/check` — headers `Authorization: Bearer <ID token Firebase>`, `X-Device-Id: <ANDROID_ID>`;
  corpo `{"nonce": "...", "versionCode": 106}`. Responde `{"payload": "<json>", "sig": "<ECDSA P-256 r||s base64>"}`.
  O payload traz `status` (`trial` | `active` | `expired` | `device_limit`), `trialEndsAt`, `paidUntil`,
  `serverTime`, `graceMs`, `minVersionCode`, `downloadUrl`, `code` (código curto da conta) e o `nonce`.
- `POST /license/claim` — "Já paguei" (guarda `claim:<código>` no KV para o admin conferir).
- `/admin/*` — protegido por `ADMIN_SECRET`; use `tools/unlock.sh` (lê o token de um arquivo).
- `POST /webhook/cora` — gancho (501) para a liberação automática quando a API Pix da Cora for aprovada:
  validar o webhook, achar a conta pelo txid (`RGV<código>`) e chamar `grantPaid(...)`.
- Com `LICENSE_ENFORCE = "1"`, `/chat`, `/tts` e `/receipt` exigem conta (e-mail/senha), aparelho da conta,
  `X-App-Version >= MIN_VERSION_CODE` e teste/assinatura em dia (402 se vencido, 426 se versão antiga).
  `/token` (LiveKit) ainda não é barrado.

Regras (no servidor, KV `LICENSES`):
- `acct:<uid>`: `trialStart`, `paidUntil`, `devices[]`, `code`, `email`, `grants[]`.
- `dev:<hash do ANDROID_ID>`: `trialStart`, `accounts[]`.
- Conta nova em aparelho que já teve teste herda o `trialStart` do aparelho (sem teste novo).
- A mesma conta em outro aparelho mantém o próprio teste/pagamento (até `MAX_DEVICES` aparelhos).

### Publicar (só com autorização)
```sh
npx wrangler kv namespace create LICENSES        # copie o id para wrangler.toml ([[kv_namespaces]])
npx wrangler secret put LICENSE_SIGNING_KEY < /workspace/secrets/license_signing_key.jwk
npx wrangler secret put ADMIN_SECRET        < /workspace/secrets/license_admin_secret
npm test && npx wrangler deploy
```
A chave pública correspondente está em `app/.../licenca/AssinaturaLicenca.kt` (`CHAVE_PUBLICA`).
