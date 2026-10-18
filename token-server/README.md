# Servidor de tokens LiveKit (Cloudflare Worker)

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
