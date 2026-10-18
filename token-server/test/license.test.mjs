// Testes da lógica de licença do Worker: npm test (compila src/license.ts e roda com node --test).
import test from "node:test";
import assert from "node:assert/strict";
import * as L from "../build-test/license.js";

const DAY = 86_400_000;
const cfg = { trialDays: 7, graceMs: 48 * 3_600_000, maxDevices: 3, minVersionCode: 106, downloadUrl: "https://x" };
const T0 = Date.parse("2026-10-18T12:00:00Z");

function memStore() {
  const m = new Map();
  return {
    m,
    async get(k) { return m.has(k) ? m.get(k) : null; },
    async put(k, v) { m.set(k, v); },
    async delete(k) { m.delete(k); },
    async list({ prefix }) { return { keys: [...m.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })), list_complete: true }; },
  };
}
const check = (s, uid, device, now, email) => L.checkLicense(s, { uid, device, now, nonce: "nonce12345", email }, cfg).then((r) => r.payload);

test("primeira checagem grava o início do teste no servidor: 7 dias", async () => {
  const s = memStore();
  const p = await check(s, "u1", "devA", T0, "a@b.com");
  assert.equal(p.status, "trial");
  assert.equal(p.trialStart, T0);
  assert.equal(p.trialEndsAt, T0 + 7 * DAY);
  assert.equal(p.serverTime, T0);
  assert.match(p.code, /^[2-9A-Z]{6}$/);
  assert.equal(await s.get("email:a@b.com"), "u1");
  // Dias depois, o início não muda.
  const p2 = await check(s, "u1", "devA", T0 + 3 * DAY);
  assert.equal(p2.trialStart, T0);
  assert.equal(p2.status, "trial");
});

test("teste vence depois de 7 dias", async () => {
  const s = memStore();
  await check(s, "u1", "devA", T0);
  assert.equal((await check(s, "u1", "devA", T0 + 7 * DAY - 1)).status, "trial");
  assert.equal((await check(s, "u1", "devA", T0 + 7 * DAY)).status, "expired");
});

test("conta nova em aparelho que já usou o teste NÃO ganha teste novo", async () => {
  const s = memStore();
  await check(s, "u1", "devA", T0);
  const p = await check(s, "u2", "devA", T0 + 8 * DAY);
  assert.equal(p.trialStart, T0);
  assert.equal(p.status, "expired");
  // e no meio do teste herda só os dias restantes
  const s2 = memStore();
  await check(s2, "u1", "devA", T0);
  const q = await check(s2, "u3", "devA", T0 + 2 * DAY);
  assert.equal(q.trialEndsAt, T0 + 7 * DAY);
});

test("mesma conta em aparelho novo mantém o teste e o pago", async () => {
  const s = memStore();
  await check(s, "u1", "devA", T0);
  const p = await check(s, "u1", "devB", T0 + 10 * DAY);
  assert.equal(p.trialStart, T0);
  assert.equal(p.status, "expired");
  await L.grantPaid(s, "u1", { days: 30, source: "admin" }, T0 + 10 * DAY);
  const q = await check(s, "u1", "devC", T0 + 11 * DAY);
  assert.equal(q.status, "active");
  assert.equal(q.paidUntil, T0 + 40 * DAY);
  // o aparelho novo herdou o teste da conta (outra conta nele não ganha teste novo)
  assert.equal((await check(s, "u9", "devB", T0 + 11 * DAY)).status, "expired");
});

test("reinstalar / limpar dados (mesmo aparelho, mesma conta) não reinicia o teste", async () => {
  const s = memStore();
  await check(s, "u1", "devA", T0);
  for (let i = 0; i < 3; i++) assert.equal((await check(s, "u1", "devA", T0 + 9 * DAY)).trialStart, T0);
});

test("pago: estende a partir do fim atual e expira depois", async () => {
  const s = memStore();
  await check(s, "u1", "devA", T0);
  await L.grantPaid(s, "u1", { days: 30, source: "admin" }, T0 + 2 * DAY); // durante o teste: conta a partir de agora
  let a = await L.getAccount(s, "u1");
  assert.equal(a.paidUntil, T0 + 32 * DAY);
  await L.grantPaid(s, "u1", { days: 30, source: "admin" }, T0 + 5 * DAY); // renova antes de vencer: soma
  a = await L.getAccount(s, "u1");
  assert.equal(a.paidUntil, T0 + 62 * DAY);
  assert.equal((await check(s, "u1", "devA", T0 + 61 * DAY)).status, "active");
  assert.equal((await check(s, "u1", "devA", T0 + 62 * DAY)).status, "expired");
  await L.grantPaid(s, "u1", { until: 0, source: "admin-revoke" }, T0 + 70 * DAY);
  assert.equal((await L.getAccount(s, "u1")).grants.length, 3);
});

test("limite de aparelhos por conta", async () => {
  const s = memStore();
  for (const d of ["d1", "d2", "d3"]) assert.equal((await check(s, "u1", d, T0)).status, "trial");
  assert.equal((await check(s, "u1", "d4", T0)).status, "device_limit");
  assert.equal(await L.accountStatus(s, "u1", "d4", T0, cfg), "device_limit");
  assert.equal(await L.accountStatus(s, "u1", "d1", T0, cfg), "trial");
  await L.resetDevices(s, "u1");
  assert.equal((await check(s, "u1", "d4", T0)).status, "trial");
});

test("accountStatus: conta desconhecida e vencida", async () => {
  const s = memStore();
  assert.equal(await L.accountStatus(s, "nada", "d1", T0, cfg), "unknown");
  await check(s, "u1", "d1", T0);
  assert.equal(await L.accountStatus(s, "u1", "d1", T0 + 8 * DAY, cfg), "expired");
});

test("resolveUid por código, e-mail, celular e uid", async () => {
  const s = memStore();
  const p = await check(s, "uidX", "d1", T0, "5533987044866@tel.rotagv.app");
  assert.equal(await L.resolveUid(s, p.code.toLowerCase()), "uidX");
  assert.equal(await L.resolveUid(s, "(33) 98704-4866"), "uidX");
  assert.equal(await L.resolveUid(s, "5533987044866@TEL.rotagv.app"), "uidX");
  assert.equal(await L.resolveUid(s, "uidX"), "uidX");
  assert.equal(await L.resolveUid(s, "ninguem@x.com"), null);
});

test("normalizePhone aceita celular BR e recusa fixo/curto", () => {
  assert.equal(L.normalizePhone("33987044866"), "5533987044866");
  assert.equal(L.normalizePhone("+55 (33) 98704-4866"), "5533987044866");
  assert.equal(L.normalizePhone("033987044866"), "5533987044866");
  assert.equal(L.normalizePhone("3332714000"), null);
  assert.equal(L.normalizePhone("987044866"), null);
});

test("deviceHash é estável e igual ao do app", async () => {
  assert.equal(await L.deviceHash("9774d56d682e549c"), await L.deviceHash(" 9774D56D682E549C "));
  assert.equal(await L.deviceHash("9774d56d682e549c"), "e3dc8b138541224265d48963eaf8cee0"); // mesmo valor no LicencaLogicaTest (app)
  assert.equal((await L.deviceHash("abc12345")).length, 32);
});

test("assinatura ECDSA P-256 confere com a chave pública e falha se alterar o payload", async () => {
  const k = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const jwk = JSON.stringify(await crypto.subtle.exportKey("jwk", k.privateKey));
  const payload = JSON.stringify(await check(memStore(), "u1", "d1", T0));
  const sig = Uint8Array.from(atob(await L.signPayload(payload, jwk)), (c) => c.charCodeAt(0));
  assert.equal(sig.length, 64);
  const v = (t) => crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, k.publicKey, sig, new TextEncoder().encode(t));
  assert.equal(await v(payload), true);
  assert.equal(await v(payload.replace('"trial"', '"active"')), false);
});

test("ID token do Firebase: confere assinatura, aud, iss, validade", async () => {
  const kp = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const pub = { ...(await crypto.subtle.exportKey("jwk", kp.publicKey)), kid: "k1", alg: "RS256", use: "sig" };
  const fetcher = async () => new Response(JSON.stringify({ keys: [pub] }), { headers: { "cache-control": "max-age=3600" } });
  const b64u = (o) => Buffer.from(typeof o === "string" ? o : JSON.stringify(o)).toString("base64url");
  const now = T0, t = Math.floor(now / 1000);
  async function tok(claims, kid = "k1") {
    const h = b64u({ alg: "RS256", kid, typ: "JWT" }), p = b64u(claims);
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", kp.privateKey, new TextEncoder().encode(`${h}.${p}`));
    return `${h}.${p}.${Buffer.from(sig).toString("base64url")}`;
  }
  const base = { aud: "proj", iss: "https://securetoken.google.com/proj", sub: "uid1", iat: t - 10, exp: t + 3000, email: "a@b.com", firebase: { sign_in_provider: "password" } };
  L._resetJwksCache();
  const u = await L.verifyFirebaseIdToken(await tok(base), "proj", now, fetcher);
  assert.deepEqual([u.uid, u.provider, u.email], ["uid1", "password", "a@b.com"]);
  await assert.rejects(L.verifyFirebaseIdToken(await tok({ ...base, aud: "outro" }), "proj", now, fetcher), /aud/);
  await assert.rejects(L.verifyFirebaseIdToken(await tok({ ...base, exp: t - 3600 }), "proj", now, fetcher), /expirado/);
  await assert.rejects(L.verifyFirebaseIdToken(await tok({ ...base, iss: "https://evil" }), "proj", now, fetcher), /iss/);
  await assert.rejects(L.verifyFirebaseIdToken(await tok(base, "k2"), "proj", now, fetcher), /kid/);
  const good = await tok(base);
  const forged = good.split(".").slice(0, 1).concat(b64u({ ...base, sub: "outro" }), good.split(".")[2]).join(".");
  await assert.rejects(L.verifyFirebaseIdToken(forged, "proj", now, fetcher), /assinatura/);
});
