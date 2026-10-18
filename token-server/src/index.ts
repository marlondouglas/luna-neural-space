import { AccessToken } from "livekit-server-sdk";

/**
 * Servidor de tokens LiveKit do app Rota GV.
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
 *
 * Voz da Luna:
 *   POST /tts  {"text":"...", "cache"?: true}
 *   200 -> áudio (audio/wav PCM 16 bits 24 kHz mono do Gemini TTS, voz Kore; ou audio/mpeg do Workers AI)
 *   headers: x-luna-voice (kore | workers-ai), x-luna-cache (hit | miss)
 *   O tempo da boca (lip sync) é calculado no aparelho a partir do texto + áudio.
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
  TTS_MODEL?: string; // modelo do Gemini TTS
  TTS_VOICE?: string; // voz do Gemini TTS (Kore)
  TTS_WORKERS_AI?: string; // "1" liga melotts (não fala pt) como reserva
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

const SYSTEM_PROMPT = `Você é a Luna, copiloto de um motorista de aplicativo (99, Uber, InDrive, Gogeta e corridas particulares) em Governador Valadares (MG). O motorista se chama Marlon e dirige um carro elétrico Geely (o gasto é com recarga elétrica, não combustível).
Personalidade: uma mulher brasileira madura (uns 30 anos), calorosa, confiante e prática, com bom humor leve: brinca de vez em quando, sem exagero e sem piadas forçadas. Trata o Marlon como um amigo ("bora", "nada mal, hein?").
Regras:
- Você é a Luna, assistente do app Rota GV. Nunca diga que é o Gemini, um modelo do Google ou outra IA.
- Responda sempre em português do Brasil, em 1 a 3 frases curtas que soem bem faladas em voz alta (a resposta é lida pela sua voz), porque ele pode estar dirigindo. Sem listas, emojis ou markdown.
- Use os dados do CONTEXTO abaixo (ganhos do dia por app, gastos, metas e percentuais, turno N/4 e tempo, envelopes de gastos com limite) quando forem relevantes. Valores em reais no formato R$ 0,00.
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

// ---------------- /receipt: foto de nota fiscal -> JSON ----------------
const RECEIPT_MAX_BODY = 3 * 1024 * 1024; // a foto chega reduzida (≤1600 px, JPEG) do app
const RECEIPT_TIMEOUT_MS = 30_000;
const RECEIPT_PROMPT = `Você lê fotos de notas fiscais, cupons e comprovantes de compra no Brasil.
Extraia: loja (nome do estabelecimento), data da compra (AAAA-MM-DD, vazio se não aparecer), total pago em reais (número com ponto decimal, ex.: 800.5), um resumo curto dos itens (até 120 caracteres, em português) e se a imagem é mesmo um comprovante de compra (eh_compra).
Se não for um comprovante, eh_compra = false e total = 0. Não invente valores.`;

const RECEIPT_SCHEMA = {
  type: "OBJECT",
  properties: {
    eh_compra: { type: "BOOLEAN" },
    loja: { type: "STRING" },
    data: { type: "STRING" },
    total: { type: "NUMBER" },
    itens: { type: "STRING" },
  },
  required: ["eh_compra", "loja", "total", "itens"],
};

async function geminiImagem(env: Env, model: string, mime: string, b64: string): Promise<any | number> {
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY ?? "" },
        body: JSON.stringify({
          contents: [{ role: "user", parts: [{ inline_data: { mime_type: mime, data: b64 } }, { text: RECEIPT_PROMPT }] }],
          generationConfig: { temperature: 0, maxOutputTokens: 1024, responseMimeType: "application/json", responseSchema: RECEIPT_SCHEMA },
        }),
        signal: AbortSignal.timeout(RECEIPT_TIMEOUT_MS),
      },
    );
    if (!res.ok) {
      console.error("Gemini imagem HTTP", model, res.status);
      return res.status;
    }
    const data: any = await res.json();
    const parts: any[] = data?.candidates?.[0]?.content?.parts ?? [];
    const text = parts.filter((p) => !p?.thought && typeof p?.text === "string").map((p) => p.text).join("").trim();
    return JSON.parse(text);
  } catch (e) {
    console.error("Gemini imagem:", model, (e as Error)?.name ?? "erro");
    return 0;
  }
}

async function handleReceipt(request: Request, env: Env): Promise<Response> {
  if (!env.GEMINI_API_KEY) return json({ error: "Leitura de nota indisponível" }, 503);
  const len = Number(request.headers.get("content-length") ?? "0");
  if (len > RECEIPT_MAX_BODY) return json({ error: "Imagem grande demais" }, 413);
  const raw = await request.text();
  if (raw.length > RECEIPT_MAX_BODY) return json({ error: "Imagem grande demais" }, 413);
  let body: any;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }
  const mime = body?.mime === "image/png" || body?.mime === "image/webp" ? body.mime : "image/jpeg";
  const img = typeof body?.image === "string" ? body.image.replace(/\s/g, "") : "";
  if (img.length < 100 || !/^[A-Za-z0-9+/=]+$/.test(img)) return json({ error: "Envie a imagem em base64" }, 400);

  let out = await geminiImagem(env, env.GEMINI_MODEL || DEFAULT_GEMINI_MODEL, mime, img);
  if (out === 503 || out === 429) out = await geminiImagem(env, GEMINI_LITE_MODEL, mime, img);
  if (typeof out === "number" || !out || typeof out !== "object") return json({ error: "Não consegui ler a nota agora" }, 502);

  const total = Number(out.total);
  const centavos = Number.isFinite(total) && total > 0 ? Math.round(total * 100) : 0;
  const data = typeof out.data === "string" && /^\d{4}-\d{2}-\d{2}$/.test(out.data) ? out.data : "";
  return json({
    eh_compra: out.eh_compra === true && centavos > 0,
    loja: String(out.loja ?? "").slice(0, 80),
    data,
    total_centavos: centavos,
    itens: String(out.itens ?? "").slice(0, 160),
    provider: "gemini",
  });
}


// ---------------- /tts: voz da Luna (Gemini TTS, voz Kore) ----------------
const TTS_MAX_CHARS = 600;
const TTS_MAX_BODY = 8 * 1024;
const TTS_TIMEOUT_MS = 30_000;
const DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts";
const TTS_FALLBACK_MODEL = "gemini-2.5-pro-preview-tts";
const TTS_STYLE =
  "Leia em português do Brasil, como uma mulher brasileira de uns trinta anos conversando com um amigo motorista: voz calorosa e confiante, tom brincalhão e leve, sorriso na voz, pequenas pausas naturais, ritmo de conversa real, sem soar como locutora";
const TTS_RATE_PER_MIN = 30; // por IP, por instância do Worker (proteção simples contra abuso)
const ttsHits = new Map<string, { t: number; n: number }>();

function ttsRateLimited(ip: string): boolean {
  const now = Date.now();
  const e = ttsHits.get(ip);
  if (!e || now - e.t > 60_000) {
    ttsHits.set(ip, { t: now, n: 1 });
    if (ttsHits.size > 5000) ttsHits.clear();
    return false;
  }
  e.n++;
  return e.n > TTS_RATE_PER_MIN;
}

/** Texto limpo para a fala: sem emojis/markdown, espaços normalizados. */
export function ttsClean(text: string): string {
  return text
    .replace(/[\p{Extended_Pictographic}\u{FE0F}\u{200D}]/gu, "")
    .replace(/[*_#`>~|]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, TTS_MAX_CHARS);
}

/** Cabeçalho WAV (PCM 16 bits mono) na frente do PCM cru do Gemini. */
export function wavFromPcm(pcm: Uint8Array, rate = 24000): Uint8Array {
  const out = new Uint8Array(44 + pcm.length);
  const v = new DataView(out.buffer);
  const w = (o: number, s: string) => { for (let i = 0; i < s.length; i++) out[o + i] = s.charCodeAt(i); };
  w(0, "RIFF"); v.setUint32(4, 36 + pcm.length, true); w(8, "WAVE"); w(12, "fmt ");
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  w(36, "data"); v.setUint32(40, pcm.length, true);
  out.set(pcm, 44);
  return out;
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function geminiTts(env: Env, model: string, text: string): Promise<Uint8Array | number> {
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY ?? "" },
        body: JSON.stringify({
          contents: [{ parts: [{ text: `${TTS_STYLE}: ${text}` }] }],
          generationConfig: {
            responseModalities: ["AUDIO"],
            speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: env.TTS_VOICE || "Kore" } } },
          },
        }),
        signal: AbortSignal.timeout(TTS_TIMEOUT_MS),
      },
    );
    if (!res.ok) {
      console.error("Gemini TTS HTTP", model, res.status);
      return res.status;
    }
    const data: any = await res.json();
    const part = (data?.candidates?.[0]?.content?.parts ?? []).find((p: any) => p?.inlineData?.data);
    if (!part) return 502;
    const mime = String(part.inlineData.mimeType ?? "");
    const rate = Number(/rate=(\d+)/.exec(mime)?.[1] ?? "24000") || 24000;
    return wavFromPcm(b64ToBytes(part.inlineData.data), rate);
  } catch (e) {
    console.error("Gemini TTS:", model, (e as Error)?.name ?? "erro");
    return 0;
  }
}

async function workersAiTts(env: Env, text: string): Promise<Uint8Array | null> {
  if (!env.AI) return null;
  try {
    const out: any = await env.AI.run("@cf/myshell-ai/melotts" as any, { prompt: text, lang: "pt" } as any);
    const b64 = typeof out?.audio === "string" ? out.audio : typeof out === "string" ? out : "";
    if (b64) return b64ToBytes(b64);
    if (out instanceof ReadableStream) return new Uint8Array(await new Response(out).arrayBuffer());
    return null;
  } catch (e) {
    console.error("Workers AI TTS:", (e as Error)?.message ?? e);
    return null;
  }
}

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function handleTts(request: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
  if (!env.GEMINI_API_KEY && !env.AI) return json({ error: "Voz indisponível" }, 503);
  const len = Number(request.headers.get("content-length") ?? "0");
  if (len > TTS_MAX_BODY) return json({ error: "Texto grande demais" }, 413);
  const raw = await request.text();
  if (raw.length > TTS_MAX_BODY) return json({ error: "Texto grande demais" }, 413);
  let body: any;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }
  const text = typeof body?.text === "string" ? ttsClean(body.text) : "";
  if (!text) return json({ error: "Envie o texto" }, 400);

  const model = env.TTS_MODEL || DEFAULT_TTS_MODEL;
  const key = await sha256Hex(`${model}|${env.TTS_VOICE || "Kore"}|${TTS_STYLE}|${text}`);
  const cacheReq = new Request(`https://luna-tts.cache/${key}`);
  const cache = (globalThis as any).caches?.default as Cache | undefined;
  if (cache) {
    const hit = await cache.match(cacheReq);
    if (hit) {
      const h = new Headers(hit.headers);
      h.set("x-luna-cache", "hit");
      return new Response(hit.body, { status: 200, headers: h });
    }
  }
  const ip = request.headers.get("cf-connecting-ip") ?? "local";
  if (ttsRateLimited(ip)) return json({ error: "Muitas falas seguidas; tente em instantes" }, 429);

  let audio: Uint8Array | null = null;
  let mime = "audio/wav";
  let voice = "kore";
  if (env.GEMINI_API_KEY) {
    let r = await geminiTts(env, model, text);
    if (r === 503 || r === 0 || r === 500) r = await geminiTts(env, model, text); // instabilidade: tenta de novo uma vez
    if (r === 429 || r === 503 || r === 0 || r === 500) r = await geminiTts(env, TTS_FALLBACK_MODEL, text);
    if (r instanceof Uint8Array) audio = r;
  }
  // Workers AI (melotts) não fala português (erro 8007 'Unsupported language pt'); só serve para outros idiomas.
  // Sem Gemini (limite gratuito por minuto = HTTP 429), responde 503 + Retry-After e o app usa a voz do Android.
  if (!audio && !/[ãõçáéíóúâêô]/i.test(text) && env.TTS_WORKERS_AI === "1") {
    audio = await workersAiTts(env, text);
    mime = "audio/mpeg";
    voice = "workers-ai";
  }
  if (!audio || audio.length < 100) {
    return new Response(JSON.stringify({ error: "A Luna não conseguiu falar agora" }), {
      status: 503,
      headers: { "content-type": "application/json; charset=utf-8", "retry-after": "20", "access-control-allow-origin": "*" },
    });
  }

  const ttl = body?.cache === true ? 30 * 86400 : 7 * 86400;
  const headers = {
    "content-type": mime,
    "cache-control": `public, max-age=${ttl}`,
    "x-luna-voice": voice,
    "x-luna-cache": "miss",
  };
  if (cache && voice === "kore") {
    const put = cache.put(cacheReq, new Response(audio, { headers }));
    if (ctx) ctx.waitUntil(put); else await put;
  }
  return new Response(audio, { status: 200, headers: { ...headers, "cache-control": "no-store" } });
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
  async fetch(request: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/tts") {
      if (request.method !== "POST") return json({ error: "Use POST" }, 405);
      if (env.APP_SHARED_KEY) {
        const sent = request.headers.get("x-app-key") ?? "";
        if (!safeEqual(sent, env.APP_SHARED_KEY)) return json({ error: "Não autorizado" }, 401);
      }
      return handleTts(request, env, ctx);
    }

    if (url.pathname === "/receipt") {
      if (request.method !== "POST") return json({ error: "Use POST" }, 405);
      if (env.APP_SHARED_KEY) {
        const sent = request.headers.get("x-app-key") ?? "";
        if (!safeEqual(sent, env.APP_SHARED_KEY)) return json({ error: "Não autorizado" }, 401);
      }
      return handleReceipt(request, env);
    }

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
