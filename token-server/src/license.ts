/**
 * Licença do Rota GV: teste grátis de 7 dias + assinatura R$ 7/mês (Pix).
 *
 * O servidor é a fonte da verdade. O relógio do celular não conta: o início do teste fica gravado
 * aqui (KV), preso à CONTA (Firebase e-mail/senha) e ao APARELHO (hash do ANDROID_ID):
 *  - conta nova em aparelho que já usou o teste herda o início do teste do aparelho (não ganha teste novo);
 *  - a mesma conta em outro aparelho mantém o próprio início de teste e o "pago até";
 *  - uma conta pode estar em no máximo MAX_DEVICES aparelhos (contra compartilhar uma assinatura).
 *
 * A resposta é assinada (ECDSA P-256/SHA-256) com o segredo LICENSE_SIGNING_KEY; o app confere com a
 * chave pública embutida e guarda a resposta para funcionar offline por até GRACE_MS.
 *
 * Este módulo não depende do Worker: recebe um Store (KV ou memória, nos testes) e funções de tempo.
 */

export const DAY_MS = 86_400_000;
export const DEFAULT_TRIAL_DAYS = 7;
export const DEFAULT_GRACE_MS = 48 * 3_600_000;
export const DEFAULT_MAX_DEVICES = 3;

export interface Store {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, opts?: { metadata?: unknown; expirationTtl?: number }): Promise<void>;
  delete?(key: string): Promise<void>;
  list?(opts: { prefix: string; limit?: number; cursor?: string }): Promise<{ keys: { name: string; metadata?: unknown }[]; list_complete: boolean; cursor?: string }>;
}

export interface Account {
  trialStart: number;
  paidUntil: number;
  devices: string[];
  code: string;
  email?: string;
  created: number;
  /** Histórico curto de liberações (admin / Cora). */
  grants?: { at: number; until: number; source: string; ref?: string }[];
}

export interface Device {
  trialStart: number;
  accounts: string[];
  created: number;
}

export type Status = "trial" | "active" | "expired" | "device_limit";

export interface LicenseConfig {
  trialDays: number;
  graceMs: number;
  maxDevices: number;
  minVersionCode: number;
  downloadUrl: string;
}

export interface LicensePayload {
  v: 1;
  status: Status;
  uid: string;
  device: string; // hash do aparelho (o app confere com o próprio)
  code: string; // código curto da conta, para o "Já paguei"
  trialStart: number;
  trialEndsAt: number;
  paidUntil: number;
  serverTime: number;
  graceMs: number;
  minVersionCode: number;
  downloadUrl: string;
  nonce: string;
}

// ---------------- utilidades ----------------
const enc = new TextEncoder();

export function b64(bytes: ArrayBuffer | Uint8Array): string {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
  return btoa(s);
}

export function b64urlDecode(s: string): Uint8Array<ArrayBuffer> {
  const p = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(p + "===".slice((p.length + 3) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** ANDROID_ID -> id estável e não reversível do aparelho (o mesmo cálculo é feito no app). */
export async function deviceHash(androidId: string): Promise<string> {
  return (await sha256Hex(`rotagv-device|${androidId.trim().toLowerCase()}`)).slice(0, 32);
}

const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // sem 0/O, 1/I/L

/** Código curto (6 caracteres) derivado do uid; [salt] muda em caso de colisão. */
export async function shortCode(uid: string, salt = 0): Promise<string> {
  const h = await sha256Hex(`rotagv-code|${uid}|${salt}`);
  let out = "";
  for (let i = 0; i < 6; i++) out += CODE_ALPHABET[parseInt(h.slice(i * 4, i * 4 + 4), 16) % CODE_ALPHABET.length];
  return out;
}

/** "33 98704-4866", "+55 (33) 98704-4866" -> "5533987044866" (celular BR), senão null. */
export function normalizePhone(s: string): string | null {
  let d = s.replace(/\D/g, "");
  if (d.length === 13 && d.startsWith("55")) d = d.slice(2);
  if (d.length === 12 && d.startsWith("0")) d = d.slice(1);
  if (!/^[1-9][1-9]9\d{8}$/.test(d)) return null;
  return "55" + d;
}

/** E-mail sintético usado no Firebase para contas por celular. */
export function phoneEmail(phone55: string): string {
  return `${phone55}@tel.rotagv.app`;
}

// ---------------- regras ----------------
export function computeStatus(acct: Account, now: number, cfg: Pick<LicenseConfig, "trialDays">): Exclude<Status, "device_limit"> {
  if (acct.paidUntil > now) return "active";
  if (now < acct.trialStart + cfg.trialDays * DAY_MS) return "trial";
  return "expired";
}

async function readJson<T>(store: Store, key: string): Promise<T | null> {
  const s = await store.get(key);
  if (!s) return null;
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
}

async function writeJson(store: Store, key: string, v: unknown, metadata?: unknown): Promise<void> {
  await store.put(key, JSON.stringify(v), metadata === undefined ? undefined : { metadata });
}

async function allocCode(store: Store, uid: string): Promise<string> {
  for (let salt = 0; salt < 20; salt++) {
    const c = await shortCode(uid, salt);
    const owner = await store.get(`code:${c}`);
    if (!owner || owner === uid) {
      if (!owner) await store.put(`code:${c}`, uid);
      return c;
    }
  }
  throw new Error("Sem código livre");
}

export interface CheckInput {
  uid: string;
  email?: string;
  device: string; // hash do aparelho (deviceHash)
  nonce: string;
  now: number;
}

/**
 * Lê/cria os registros da conta e do aparelho e decide o estado. Grava o início do teste na primeira vez.
 */
export async function checkLicense(store: Store, input: CheckInput, cfg: LicenseConfig): Promise<{ payload: LicensePayload; acct: Account }> {
  const { uid, device, now } = input;
  let acct = await readJson<Account>(store, `acct:${uid}`);
  let dev = await readJson<Device>(store, `dev:${device}`);
  let acctDirty = false;
  let devDirty = false;

  if (!acct) {
    // Conta nova: se o aparelho já usou o teste (com outra conta), não ganha teste novo.
    acct = { trialStart: dev ? dev.trialStart : now, paidUntil: 0, devices: [], code: await allocCode(store, uid), created: now };
    if (input.email) acct.email = input.email.toLowerCase();
    acctDirty = true;
    if (acct.email) await store.put(`email:${acct.email}`, uid);
  }
  if (!dev) {
    // Aparelho novo: começa a contar com o teste da conta (a conta mantém o estado em qualquer aparelho).
    dev = { trialStart: acct.trialStart, accounts: [], created: now };
    devDirty = true;
  }
  if (!dev.accounts.includes(uid)) {
    dev.accounts = [...dev.accounts, uid].slice(-20);
    devDirty = true;
  }

  let status: Status = computeStatus(acct, now, cfg);
  if (!acct.devices.includes(device)) {
    if (acct.devices.length >= cfg.maxDevices) {
      status = "device_limit";
    } else {
      acct.devices = [...acct.devices, device];
      acctDirty = true;
    }
  }
  if (acctDirty) await writeJson(store, `acct:${uid}`, acct);
  if (devDirty) await writeJson(store, `dev:${device}`, dev);

  const payload: LicensePayload = {
    v: 1,
    status,
    uid,
    device,
    code: acct.code,
    trialStart: acct.trialStart,
    trialEndsAt: acct.trialStart + cfg.trialDays * DAY_MS,
    paidUntil: acct.paidUntil,
    serverTime: now,
    graceMs: cfg.graceMs,
    minVersionCode: cfg.minVersionCode,
    downloadUrl: cfg.downloadUrl,
    nonce: input.nonce.slice(0, 64),
  };
  return { payload, acct };
}

/** Estado atual da conta, só leitura (usado para barrar /chat, /tts e /receipt). */
export async function accountStatus(store: Store, uid: string, device: string | null, now: number, cfg: LicenseConfig): Promise<Status | "unknown"> {
  const acct = await readJson<Account>(store, `acct:${uid}`);
  if (!acct) return "unknown";
  if (device && !acct.devices.includes(device)) return "device_limit";
  return computeStatus(acct, now, cfg);
}

/**
 * Libera/estende a assinatura. Ponto único usado pelo admin (tools/unlock.sh) e, no futuro, pelo
 * webhook da Cora (Pix recebido -> grantPaid(uid, +30 dias, "cora", txid)).
 */
export async function grantPaid(store: Store, uid: string, opts: { days?: number; until?: number; source: string; ref?: string }, now: number): Promise<Account | null> {
  const acct = await readJson<Account>(store, `acct:${uid}`);
  if (!acct) return null;
  let until: number;
  if (typeof opts.until === "number") until = opts.until;
  else until = Math.max(now, acct.paidUntil) + (opts.days ?? 30) * DAY_MS;
  acct.paidUntil = until;
  acct.grants = [...(acct.grants ?? []), { at: now, until, source: opts.source, ref: opts.ref?.slice(0, 80) }].slice(-24);
  await writeJson(store, `acct:${uid}`, acct);
  return acct;
}

export async function resetDevices(store: Store, uid: string): Promise<Account | null> {
  const acct = await readJson<Account>(store, `acct:${uid}`);
  if (!acct) return null;
  acct.devices = [];
  await writeJson(store, `acct:${uid}`, acct);
  return acct;
}

/** Acha o uid por código curto, e-mail, celular ou o próprio uid. */
export async function resolveUid(store: Store, q: string): Promise<string | null> {
  const t = q.trim();
  if (!t) return null;
  if (/^[2-9A-Z]{6}$/i.test(t)) {
    const u = await store.get(`code:${t.toUpperCase()}`);
    if (u) return u;
  }
  if (t.includes("@")) return store.get(`email:${t.toLowerCase()}`);
  const phone = normalizePhone(t);
  if (phone) {
    const u = await store.get(`email:${phoneEmail(phone)}`);
    if (u) return u;
  }
  if (await store.get(`acct:${t}`)) return t;
  return null;
}

export async function getAccount(store: Store, uid: string): Promise<Account | null> {
  return readJson<Account>(store, `acct:${uid}`);
}

/** "Já paguei": guarda o pedido para o admin conferir no extrato e liberar. */
export async function recordClaim(store: Store, uid: string, code: string, now: number, note: string): Promise<void> {
  await store.put(`claim:${code}`, JSON.stringify({ uid, code, at: now, note: note.slice(0, 200) }), { metadata: { uid, at: now } });
}

// ---------------- assinatura ECDSA P-256 ----------------
let signingKey: { jwk: string; key: CryptoKey } | null = null;

async function importSigningKey(jwkJson: string): Promise<CryptoKey> {
  if (signingKey && signingKey.jwk === jwkJson) return signingKey.key;
  const jwk = JSON.parse(jwkJson);
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  signingKey = { jwk: jwkJson, key };
  return key;
}

/** Assina o texto exato do payload; retorna a assinatura crua r||s (64 bytes) em base64. */
export async function signPayload(payloadJson: string, privateJwk: string): Promise<string> {
  const key = await importSigningKey(privateJwk);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(payloadJson));
  return b64(sig);
}

// ---------------- token do Firebase (ID token, RS256) ----------------
export const FIREBASE_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

export interface FirebaseUser {
  uid: string;
  email?: string;
  provider: string; // "password", "anonymous", ...
  /** firebase.identities.email não vazio (conta anônima já vinculada a e-mail/senha). */
  hasPasswordIdentity: boolean;
  exp: number;
}

type JwkSet = { keys: (JsonWebKey & { kid?: string })[] };
let jwksCache: { at: number; ttl: number; keys: Map<string, CryptoKey> } | null = null;

async function firebaseKeys(fetcher: typeof fetch, now: number): Promise<Map<string, CryptoKey>> {
  if (jwksCache && now - jwksCache.at < jwksCache.ttl) return jwksCache.keys;
  const res = await fetcher(FIREBASE_JWKS_URL);
  if (!res.ok) throw new Error(`JWKS HTTP ${res.status}`);
  const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get("cache-control") ?? "")?.[1] ?? "3600");
  const set = (await res.json()) as JwkSet;
  const keys = new Map<string, CryptoKey>();
  for (const k of set.keys ?? []) {
    if (!k.kid) continue;
    keys.set(k.kid, await crypto.subtle.importKey("jwk", k, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]));
  }
  jwksCache = { at: now, ttl: Math.min(Math.max(maxAge, 300), 6 * 3600) * 1000, keys };
  return keys;
}

/** Só para testes. */
export function _resetJwksCache(): void {
  jwksCache = null;
}

/** Confere um ID token do Firebase Auth (assinatura RS256 do Google, aud/iss do projeto, validade). */
export async function verifyFirebaseIdToken(token: string, projectId: string, now: number, fetcher: typeof fetch = fetch): Promise<FirebaseUser> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("token malformado");
  const header = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[0])));
  const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1])));
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new Error("alg/kid inválido");
  let keys = await firebaseKeys(fetcher, now);
  let key = keys.get(header.kid);
  if (!key) {
    jwksCache = null; // chaves giradas
    keys = await firebaseKeys(fetcher, now);
    key = keys.get(header.kid);
  }
  if (!key) throw new Error("kid desconhecido");
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlDecode(parts[2]), enc.encode(`${parts[0]}.${parts[1]}`));
  if (!ok) throw new Error("assinatura inválida");
  const t = Math.floor(now / 1000);
  if (claims.aud !== projectId) throw new Error("aud inválido");
  if (claims.iss !== `https://securetoken.google.com/${projectId}`) throw new Error("iss inválido");
  if (typeof claims.exp !== "number" || claims.exp < t - 60) throw new Error("token expirado");
  if (typeof claims.iat !== "number" || claims.iat > t + 300) throw new Error("iat no futuro");
  if (typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 128) throw new Error("sub inválido");
  return {
    uid: claims.sub,
    email: typeof claims.email === "string" ? claims.email : undefined,
    provider: String(claims.firebase?.sign_in_provider ?? ""),
    hasPasswordIdentity: Array.isArray(claims.firebase?.identities?.email) && claims.firebase.identities.email.length > 0,
    exp: claims.exp,
  };
}
