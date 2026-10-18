import { AccessToken } from "livekit-server-sdk";

/**
 * Servidor de tokens LiveKit do LUNA Neural Space.
 *
 * Contrato (igual ao HttpTokenProvider do app):
 *   GET /?room=<sala>&identity=<id>   (também aceita /token)
 *   200 -> {"serverUrl": "wss://...", "participantToken": "<jwt>"}
 *   erro -> {"error": "<mensagem>"} com status 4xx/5xx
 */
export interface Env {
  LIVEKIT_URL: string;
  LIVEKIT_API_KEY: string;
  LIVEKIT_API_SECRET: string; // segredo do Worker
  APP_SHARED_KEY?: string; // segredo opcional; se definido, exige o header X-App-Key
  ROOM_PREFIX?: string;
  TOKEN_TTL_SECONDS?: string;
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
