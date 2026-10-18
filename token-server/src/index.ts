import { AccessToken } from "livekit-server-sdk";

/**
 * Servidor de tokens LiveKit do LUNA Neural Space.
 *
 * Contrato (igual ao HttpTokenProvider do app):
 *   GET /?room=<sala>&identity=<id>   (também aceita /token)
 *   200 -> {"serverUrl": "wss://...", "participantToken": "<jwt>"}
 *   erro -> {"error": "<mensagem>"} com status 4xx/5xx
 *
 * Conversa com a Luna (Workers AI, plano gratuito):
 *   POST /chat  {"messages":[{"role":"user"|"assistant","content":"..."}], "context":{...}}
 *   200 -> {"reply": "...", "provider": "gemini" | "workers-ai"}
 *   Usa o Gemini (segredo GEMINI_API_KEY); se falhar, cai no Workers AI (binding AI).
 */
export interface Env {
  LIVEKIT_URL: string;
  LIVEKIT_API_KEY: string;
  LIVEKIT_API_SECRET: string; // segredo do Worker
  APP_SHARED_KEY?: string; // segredo opcional; se definido, exige o header X-App-Key
  ROOM_PREFIX?: string;
  TOKEN_TTL_SECONDS?: string;
  AI?: Ai; // binding do Workers AI ([ai] no wrangler.toml), reserva se o Gemini falhar
  CHAT_MODEL?: string; // modelo do Workers AI
  GEMINI_API_KEY?: string; // segredo do Worker (wrangler secret put GEMINI_API_KEY)
  GEMINI_MODEL?: string;
}

const CHAT_MAX_MESSAGES = 12;
const CHAT_MAX_CHARS = 1000;
const CHAT_MAX_CONTEXT_CHARS = 3000;
const CHAT_MAX_BODY = 32 * 1024;
const DEFAULT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const DEFAULT_GEMINI_MODEL = "gemini-flash-latest";
/** Usado quando o principal responde 503 (alta demanda) ou 429 (limite). */
const GEMINI_LITE_MODEL = "gemini-flash-lite-latest";
const GEMINI_TIMEOUT_MS = 15_000;

type ChatMsg = { role: "user" | "assistant"; content: string };

/** Uma chamada ao Gemini. Retorna o texto, ou o status HTTP em caso de erro (0 = rede/timeout). */
async function geminiCall(env: Env, model: string, system: string, conv: ChatMsg[]): Promise<string | number> {
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY ?? "" },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: system }] },
          contents: conv.map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content }] })),
          generationConfig: { maxOutputTokens: 1024, temperature: 0.6 },
        }),
        signal: AbortSignal.timeout(GEMINI_TIMEOUT_MS),
      },
    );
    if (!res.ok) {
      console.error("Gemini HTTP", model, res.status);
      return res.status;
    }
    const data: any = await res.json();
    const parts: any[] = data?.candidates?.[0]?.content?.parts ?? [];
    const text = parts.filter((p) => !p?.thought && typeof p?.text === "string").map((p) => p.text).join("").trim();
    return text || 502;
  } catch (e) {
    console.error("Gemini:", model, (e as Error)?.name ?? "erro");
    return 0;
  }
}

/**
 * Gemini: tenta o modelo principal; em 503/429 tenta o "lite". Retorna null em
 * qualquer outra falha, para cair no Workers AI.
 */
async function geminiReply(env: Env, system: string, messages: ChatMsg[]): Promise<string | null> {
  if (!env.GEMINI_API_KEY) return null;
  // O Gemini espera que a conversa comece pelo usuário.
  const first = messages.findIndex((m) => m.role === "user");
  const conv = first > 0 ? messages.slice(first) : messages;
  const r1 = await geminiCall(env, env.GEMINI_MODEL || DEFAULT_GEMINI_MODEL, system, conv);
  if (typeof r1 === "string") return r1;
  if (r1 !== 503 && r1 !== 429) return null;
  const r2 = await geminiCall(env, GEMINI_LITE_MODEL, system, conv);
  return typeof r2 === "string" ? r2 : null;
}

const SYSTEM_PROMPT = `Você é a Luna, copiloto simpática e direta de um motorista de aplicativo (99, Uber e InDrive) em Governador Valadares (MG). O motorista se chama Marlon e dirige um carro elétrico Geely (o gasto é com recarga elétrica, não combustível).
Regras:
- Você é a Luna, assistente do app LUNA Neural Space. Nunca diga que é o Gemini, um modelo do Google ou outra IA.
- Responda sempre em português do Brasil, com frases curtas (no máximo 3 ou 4), porque ele pode estar dirigindo.
- Use os dados do CONTEXTO abaixo (ganhos do dia por app, gastos, metas e percentuais, tempo de turno) quando forem relevantes. Valores em reais no formato R$ 0,00.
- Não invente números que não estejam no contexto. Se não souber, diga que não sabe.
- Seja encorajadora, prática e segura: nunca incentive a usar o celular dirigindo nem a correr riscos.
- Você não consegue lançar ganhos nem mudar o turno por aqui; se ele pedir, explique que é só tocar nos botões do painel.`;

async function handleChat(request: Request, env: Env): Promise<Response> {
  if (!env.AI && !env.GEMINI_API_KEY) return json({ error: "Servidor sem IA configurada" }, 500);
  const len = Number(request.headers.get("content-length") ?? "0");
  if (len > CHAT_MAX_BODY) return json({ error: "Mensagem grande demais" }, 413);
  const raw = await request.text();
  if (raw.length > CHAT_MAX_BODY) return json({ error: "Mensagem grande demais" }, 413);

  let body: any;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }
  const input = Array.isArray(body?.messages) ? body.messages : [];
  const messages = input
    .filter((m: any) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
    .slice(-CHAT_MAX_MESSAGES)
    .map((m: any) => ({ role: m.role as "user" | "assistant", content: m.content.slice(0, CHAT_MAX_CHARS) }));
  if (messages.length === 0 || messages[messages.length - 1].role !== "user") {
    return json({ error: "Envie pelo menos uma mensagem do usuário" }, 400);
  }
  let context = "";
  if (body?.context && typeof body.context === "object") {
    context = JSON.stringify(body.context).slice(0, CHAT_MAX_CONTEXT_CHARS);
  }
  const system = SYSTEM_PROMPT + (context ? `\n\nCONTEXTO (dados do app agora):\n${context}` : "");

  const gemini = await geminiReply(env, system, messages);
  if (gemini) return json({ reply: gemini, provider: "gemini" });
  if (!env.AI) return json({ error: "A Luna não conseguiu responder agora" }, 502);

  try {
    const out: any = await env.AI.run((env.CHAT_MODEL || DEFAULT_MODEL) as any, {
      messages: [{ role: "system", content: system }, ...messages],
      max_tokens: 300,
      temperature: 0.6,
    });
    const text = typeof out?.response === "string" ? out.response : out?.choices?.[0]?.message?.content;
    const reply = typeof text === "string" ? text.trim() : "";
    if (!reply) return json({ error: "A Luna não conseguiu responder agora" }, 502);
    return json({ reply, provider: "workers-ai" });
  } catch (e) {
    console.error("Workers AI:", (e as Error)?.message ?? e);
    return json({ error: "Falha no Workers AI" }, 502);
  }
}

const ROOM_RE = /^[A-Za-z0-9_-]{1,64}$/;
const IDENTITY_RE = /^[A-Za-z0-9_.@-]{1,128}$/;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

/** Comparação em tempo constante. */
function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/chat") {
      if (request.method !== "POST") return json({ error: "Use POST" }, 405);
      if (env.APP_SHARED_KEY) {
        const sent = request.headers.get("x-app-key") ?? "";
        if (!safeEqual(sent, env.APP_SHARED_KEY)) return json({ error: "Não autorizado" }, 401);
      }
      return handleChat(request, env);
    }

    if (request.method !== "GET") return json({ error: "Use GET" }, 405);
    if (url.pathname !== "/" && url.pathname !== "/token") return json({ error: "Não encontrado" }, 404);

    if (!env.LIVEKIT_API_SECRET || !env.LIVEKIT_API_KEY || !env.LIVEKIT_URL) {
      return json({ error: "Servidor sem configuração do LiveKit" }, 500);
    }

    if (env.APP_SHARED_KEY) {
      const sent = request.headers.get("x-app-key") ?? "";
      if (!safeEqual(sent, env.APP_SHARED_KEY)) return json({ error: "Não autorizado" }, 401);
    }

    const room = url.searchParams.get("room") ?? "";
    const identity = url.searchParams.get("identity") ?? "";
    const prefix = env.ROOM_PREFIX ?? "luna-";

    if (!ROOM_RE.test(room) || !room.startsWith(prefix)) {
      return json({ error: `Sala inválida: use letras, números, - ou _, começando com "${prefix}"` }, 400);
    }
    if (!IDENTITY_RE.test(identity)) {
      return json({ error: "identity inválida" }, 400);
    }

    const ttl = Number(env.TOKEN_TTL_SECONDS ?? "14400") || 14400;
    const at = new AccessToken(env.LIVEKIT_API_KEY, env.LIVEKIT_API_SECRET, { identity, ttl });
    at.addGrant({
      roomJoin: true,
      room,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
    });

    return json({ serverUrl: env.LIVEKIT_URL, participantToken: await at.toJwt() });
  },
};
