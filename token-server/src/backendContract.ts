/** Public readiness deliberately exposes booleans, never endpoints, keys or account data. */
export interface BackendReadinessEnv {
  LIVEKIT_URL?: string;
  LIVEKIT_API_KEY?: string;
  LIVEKIT_API_SECRET?: string;
  FIREBASE_PROJECT_ID?: string;
  GEMINI_API_KEY?: string;
  AI?: unknown;
}

export function validLiveKitServer(value: string | undefined): boolean {
  try {
    const url = new URL(value ?? "");
    return url.protocol === "wss:" && !!url.hostname && !url.username && !url.password && !url.search && !url.hash;
  } catch { return false; }
}

export function liveKitReadiness(env: BackendReadinessEnv): boolean {
  return validLiveKitServer(env.LIVEKIT_URL) && !!env.LIVEKIT_API_KEY?.trim() && !!env.LIVEKIT_API_SECRET?.trim();
}

export function healthPayload(env: BackendReadinessEnv) {
  const firebaseConfigured = !!env.FIREBASE_PROJECT_ID?.trim();
  const livekitConfigured = liveKitReadiness(env);
  return {
    service: "rota-gv-existing-worker",
    contractVersion: 2,
    ready: firebaseConfigured && livekitConfigured,
    capabilities: {
      firebaseConfigured,
      livekitConfigured,
      tokenAuthentication: "firebase-id-token",
      conversationalAiConfigured: !!env.GEMINI_API_KEY?.trim() || !!env.AI,
      routingProxyConfigured: false,
      sosStorage: "firestore-with-server-controlled-driver-eligibility",
    },
    // Presence of configuration is not proof that LiveKit accepts its key/secret pair.
    credentialPairVerified: false,
  };
}

export function publicHealth(method: string, env: BackendReadinessEnv): Response {
  const headers = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "allow": "GET, HEAD" };
  if (method !== "GET" && method !== "HEAD") return new Response(JSON.stringify({ error: "Use GET ou HEAD" }), { status: 405, headers });
  const payload = healthPayload(env);
  return new Response(method === "HEAD" ? null : JSON.stringify(payload), { status: payload.ready ? 200 : 503, headers });
}

/** Preserve the previous four-hour default, while rejecting negative/unbounded TTL configuration. */
export function boundedTokenTtl(value: string | undefined): number {
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0 ? Math.max(300, Math.min(14_400, Math.floor(seconds))) : 14_400;
}
