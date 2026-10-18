import {
  DEFAULT_GRACE_MS,
  DEFAULT_MAX_DEVICES,
  DEFAULT_TRIAL_DAYS,
  LicenseConfig,
  Store,
  accountStatus,
  checkLicense,
  deviceHash,
  getAccount,
  grantPaid,
  recordClaim,
  resetDevices,
  resolveUid,
  signPayload,
  verifyFirebaseIdToken,
  computeStatus,
  FirebaseUser,
} from "./license";

/** Variáveis/segredos usados pela licença (ver wrangler.toml e README). */
export interface LicenseEnv {
  LICENSES?: KVNamespace; // KV: contas, aparelhos, códigos, pedidos de "Já paguei"
  LICENSE_SIGNING_KEY?: string; // segredo: JWK privada ECDSA P-256 (wrangler secret put LICENSE_SIGNING_KEY)
  ADMIN_SECRET?: string; // segredo: token do tools/unlock.sh (wrangler secret put ADMIN_SECRET)
  FIREBASE_PROJECT_ID?: string;
  TRIAL_DAYS?: string;
  GRACE_HOURS?: string;
  MAX_DEVICES?: string;
  MIN_VERSION_CODE?: string;
  DOWNLOAD_URL?: string;
  /** "1" = /chat, /tts e /receipt exigem conta com teste ou assinatura em dia. */
  LICENSE_ENFORCE?: string;
}

export function licenseConfig(env: LicenseEnv): LicenseConfig {
  return {
    trialDays: Number(env.TRIAL_DAYS ?? DEFAULT_TRIAL_DAYS) || DEFAULT_TRIAL_DAYS,
    graceMs: (Number(env.GRACE_HOURS ?? "") || DEFAULT_GRACE_MS / 3_600_000) * 3_600_000,
    maxDevices: Number(env.MAX_DEVICES ?? DEFAULT_MAX_DEVICES) || DEFAULT_MAX_DEVICES,
    minVersionCode: Number(env.MIN_VERSION_CODE ?? "0") || 0,
    downloadUrl: env.DOWNLOAD_URL ?? "",
  };
}

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const ANDROID_ID_RE = /^[0-9a-fA-F]{8,32}$/;

type Authed = { user: FirebaseUser; device: string | null };

/** Confere "Authorization: Bearer <ID token do Firebase>" e "X-Device-Id" (ANDROID_ID). */
async function authenticate(request: Request, env: LicenseEnv, requireDevice: boolean): Promise<Authed | Response> {
  if (!env.FIREBASE_PROJECT_ID) return json({ error: "Servidor sem FIREBASE_PROJECT_ID" }, 500);
  const h = request.headers.get("authorization") ?? "";
  const token = h.startsWith("Bearer ") ? h.slice(7).trim() : "";
  if (!token) return json({ error: "Entre na sua conta", code: "account_required" }, 401);
  let user: FirebaseUser;
  try {
    user = await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID, Date.now());
  } catch (e) {
    console.error("ID token:", (e as Error).message);
    return json({ error: "Sessão inválida; entre de novo", code: "account_required" }, 401);
  }
  // Conta de verdade = tem e-mail (e-mail/senha; celular vira e-mail sintético). Anônimo puro não vale.
  if (!user.email || (user.provider === "anonymous" && !user.hasPasswordIdentity)) {
    return json({ error: "Crie uma conta ou entre para continuar", code: "account_required" }, 401);
  }
  const raw = request.headers.get("x-device-id") ?? "";
  if (requireDevice && !ANDROID_ID_RE.test(raw)) return json({ error: "Aparelho não identificado" }, 400);
  return { user, device: ANDROID_ID_RE.test(raw) ? await deviceHash(raw) : null };
}

/**
 * POST /license/check  {"nonce":"<aleatório do app>", "versionCode":106}
 * 200 -> {"payload":"<json assinado>", "sig":"<base64 r||s>"}
 */
export async function handleLicenseCheck(request: Request, env: LicenseEnv): Promise<Response> {
  if (!env.LICENSES || !env.LICENSE_SIGNING_KEY) return json({ error: "Licença não configurada no servidor" }, 503);
  const a = await authenticate(request, env, true);
  if (a instanceof Response) return a;
  let body: any = {};
  try {
    body = JSON.parse((await request.text()).slice(0, 2048) || "{}");
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }
  const nonce = typeof body?.nonce === "string" ? body.nonce : "";
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(nonce)) return json({ error: "nonce inválido" }, 400);
  const { payload } = await checkLicense(
    env.LICENSES as unknown as Store,
    { uid: a.user.uid, email: a.user.email, device: a.device!, nonce, now: Date.now() },
    licenseConfig(env),
  );
  const text = JSON.stringify(payload);
  return json({ payload: text, sig: await signPayload(text, env.LICENSE_SIGNING_KEY) });
}

/** POST /license/claim {"note":"..."} -> registra o "Já paguei" para o admin conferir. */
export async function handleLicenseClaim(request: Request, env: LicenseEnv): Promise<Response> {
  if (!env.LICENSES) return json({ error: "Licença não configurada no servidor" }, 503);
  const a = await authenticate(request, env, false);
  if (a instanceof Response) return a;
  const acct = await getAccount(env.LICENSES as unknown as Store, a.user.uid);
  if (!acct) return json({ error: "Conta sem licença; abra o app de novo" }, 404);
  let note = "";
  try {
    const b: any = JSON.parse((await request.text()).slice(0, 1024) || "{}");
    if (typeof b?.note === "string") note = b.note;
  } catch {
    /* sem nota */
  }
  await recordClaim(env.LICENSES as unknown as Store, a.user.uid, acct.code, Date.now(), note);
  return json({ ok: true, code: acct.code });
}

/**
 * Trava no servidor para /chat, /tts e /receipt: sem conta, com teste vencido ou versão antiga, recusa.
 * Retorna null se pode seguir. Desligada se LICENSE_ENFORCE != "1".
 */
export async function requireLicense(request: Request, env: LicenseEnv): Promise<Response | null> {
  if (env.LICENSE_ENFORCE !== "1") return null;
  if (!env.LICENSES) return json({ error: "Licença não configurada no servidor" }, 503);
  const cfg = licenseConfig(env);
  const ver = Number(request.headers.get("x-app-version") ?? "0") || 0;
  if (cfg.minVersionCode && ver < cfg.minVersionCode) {
    return json({ error: "Atualize o app Rota GV", code: "update_required", downloadUrl: cfg.downloadUrl }, 426);
  }
  const a = await authenticate(request, env, true);
  if (a instanceof Response) return a;
  const st = await accountStatus(env.LICENSES as unknown as Store, a.user.uid, a.device, Date.now(), cfg);
  if (st === "trial" || st === "active") return null;
  return json({ error: "Seu teste grátis acabou. Assine para continuar falando com a Luna.", code: st === "unknown" ? "license_check_required" : st }, 402);
}

// ---------------- admin (tools/unlock.sh) ----------------
function adminOk(request: Request, env: LicenseEnv): boolean {
  if (!env.ADMIN_SECRET || env.ADMIN_SECRET.length < 24) return false;
  const h = request.headers.get("authorization") ?? "";
  return h.startsWith("Bearer ") && safeEqual(h.slice(7).trim(), env.ADMIN_SECRET);
}

function resumo(uid: string, acct: Awaited<ReturnType<typeof getAccount>>, env: LicenseEnv) {
  if (!acct) return null;
  const cfg = licenseConfig(env);
  const now = Date.now();
  return {
    uid,
    code: acct.code,
    email: acct.email,
    status: computeStatus(acct, now, cfg),
    trialEndsAt: new Date(acct.trialStart + cfg.trialDays * 86_400_000).toISOString(),
    paidUntil: acct.paidUntil ? new Date(acct.paidUntil).toISOString() : null,
    devices: acct.devices.length,
    grants: acct.grants ?? [],
  };
}

/**
 * /admin/lookup?q=   GET   (q = código, e-mail, celular ou uid)
 * /admin/grant       POST  {"q":"ABC234","days":30} ou {"q":..,"until":"2026-12-01"} ; "ref" opcional
 * /admin/revoke      POST  {"q":..}  (paidUntil = 0)
 * /admin/reset-devices POST {"q":..} (troca de celular)
 * /admin/claims      GET   lista os "Já paguei"
 */
export async function handleAdmin(request: Request, env: LicenseEnv, path: string): Promise<Response> {
  if (!adminOk(request, env)) return json({ error: "Não autorizado" }, 401);
  if (!env.LICENSES) return json({ error: "KV LICENSES ausente" }, 503);
  const store = env.LICENSES as unknown as Store;
  const url = new URL(request.url);

  if (path === "/admin/claims" && request.method === "GET") {
    const r = await store.list!({ prefix: "claim:", limit: 200 });
    const out = [];
    for (const k of r.keys) {
      const v = await store.get(k.name);
      if (v) out.push(JSON.parse(v));
    }
    out.sort((x: any, y: any) => y.at - x.at);
    return json({ claims: out.map((c: any) => ({ ...c, at: new Date(c.at).toISOString() })) });
  }

  let body: any = {};
  if (request.method === "POST") {
    try {
      body = JSON.parse((await request.text()).slice(0, 2048) || "{}");
    } catch {
      return json({ error: "JSON inválido" }, 400);
    }
  }
  const q = String(body.q ?? url.searchParams.get("q") ?? "");
  const uid = await resolveUid(store, q);
  if (!uid) return json({ error: "Conta não encontrada", q }, 404);

  if (path === "/admin/lookup" && request.method === "GET") return json(resumo(uid, await getAccount(store, uid), env));
  if (path === "/admin/grant" && request.method === "POST") {
    const until = body.until ? Date.parse(String(body.until)) : undefined;
    if (body.until && !Number.isFinite(until)) return json({ error: "until inválido" }, 400);
    const days = body.days === undefined ? undefined : Number(body.days);
    if (days !== undefined && (!Number.isFinite(days) || days <= 0 || days > 400)) return json({ error: "days inválido" }, 400);
    const acct = await grantPaid(store, uid, { days, until, source: "admin", ref: body.ref ? String(body.ref) : undefined }, Date.now());
    if (acct) await store.delete?.(`claim:${acct.code}`);
    return json(resumo(uid, acct, env));
  }
  if (path === "/admin/revoke" && request.method === "POST") {
    return json(resumo(uid, await grantPaid(store, uid, { until: 0, source: "admin-revoke" }, Date.now()), env));
  }
  if (path === "/admin/reset-devices" && request.method === "POST") return json(resumo(uid, await resetDevices(store, uid), env));
  return json({ error: "Não encontrado" }, 404);
}

/**
 * POST /webhook/cora — GANCHO para a liberação automática quando a API Pix da Cora for aprovada.
 * Quando implementar: 1) validar a assinatura/mTLS do webhook da Cora; 2) achar a conta pelo txid
 * (o QR Pix do app usa txid "RGV" + código da conta) ou pelo valor/pagador; 3) chamar
 *   grantPaid(store, uid, { days: 30, source: "cora", ref: <id do Pix> }, Date.now())
 * Até lá, responde 501 e a liberação é manual (tools/unlock.sh).
 */
export async function handleCoraWebhook(_request: Request, _env: LicenseEnv): Promise<Response> {
  return json({ error: "Webhook da Cora ainda não configurado" }, 501);
}
