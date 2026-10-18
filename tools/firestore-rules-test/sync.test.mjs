// v0.1.7: as regras de main (users/{uid}/** só do dono) já cobrem os caminhos novos da sincronização
// (estado/turnoAtual, estado/relogio_<aparelho>, config/dashboard). Este teste confirma isso no emulador
// e simula dois aparelhos da mesma conta disputando o turno compartilhado numa transação.
// FIRESTORE_EMULATOR_HOST=127.0.0.1:8090 node sync.test.mjs   (NÃO publica regras)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, setDoc, getDoc, runTransaction, serverTimestamp, collection, query, where, getDocs, Timestamp } from "firebase/firestore";

const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8090").split(":");
const env = await initializeTestEnvironment({
  projectId: "demo-rotagv-regras",
  firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8"), host, port: Number(port) },
});
await env.clearFirestore();
let n = 0; const t = async (nome, p) => { await p; n++; console.log("✓", nome); };
const db = (u) => (u ? env.authenticatedContext(u, { email: `${u}@rotagv.app` }) : env.unauthenticatedContext()).firestore();

const turno = (estado, versao, deviceId) => ({ turnoId: "2026-10-18_1", data: "2026-10-18", inicio: 1, estado, trabalhoMs: 0, paradoMs: 0,
  ultimoEvento: 1, numero: 1, updatedAt: versao * 1000, deleted: false, versao, deviceId, serverUpdatedAt: serverTimestamp() });

for (const p of ["estado/turnoAtual", "estado/relogio_abc123", "config/dashboard", "ganhos/g1", "turnos/2026-10-18_1", "planejamento/plano"]) {
  await t(`dono grava users/ana/${p}`, assertSucceeds(setDoc(doc(db("ana"), `users/ana/${p}`), { updatedAt: 1, deviceId: "cel", serverUpdatedAt: serverTimestamp() })));
  await t(`dono lê users/ana/${p}`, assertSucceeds(getDoc(doc(db("ana"), `users/ana/${p}`))));
  await t(`outra conta não lê users/ana/${p}`, assertFails(getDoc(doc(db("bia"), `users/ana/${p}`))));
  await t(`outra conta não grava users/ana/${p}`, assertFails(setDoc(doc(db("bia"), `users/ana/${p}`), { x: 1 })));
  await t(`sem login não lê users/ana/${p}`, assertFails(getDoc(doc(db(null), `users/ana/${p}`))));
}
await t("consulta por serverUpdatedAt (descida) funciona para o dono",
  assertSucceeds(getDocs(query(collection(db("ana"), "users/ana/ganhos"), where("serverUpdatedAt", ">", new Timestamp(0, 0))))));
await t("nada fora de users/{uid}", assertFails(setDoc(doc(db("ana"), "estado/turnoAtual"), { x: 1 })));

// Dois aparelhos da Ana: celular e central, cada um com a versão que viu por último.
await setDoc(doc(db("ana"), "users/ana/estado/turnoAtual"), turno("ATIVO", 1, "cel"));
const acao = (dbx, eu, vista, estado) => runTransaction(dbx, async (tx) => {
  const ref = doc(dbx, "users/ana/estado/turnoAtual");
  const r = (await tx.get(ref)).data();
  if (r && r.versao > vista && r.deviceId !== eu) return { conflito: r.estado };
  tx.set(ref, turno(estado, (r?.versao ?? 0) + 1, eu));
  return { ok: (r?.versao ?? 0) + 1 };
});
const cel = db("ana"), central = db("ana");
const r1 = await acao(cel, "cel", 1, "PAUSADO");
assert.deepEqual(r1, { ok: 2 }); n++; console.log("✓ celular pausa (versão 2)");
const r2 = await acao(central, "central", 1, "CAFE");
assert.deepEqual(r2, { conflito: "PAUSADO" }); n++; console.log("✓ central, que só tinha visto a versão 1, recebe conflito: 'já foi pausado no outro aparelho'");
const r3 = await acao(central, "central", 2, "ATIVO");
assert.deepEqual(r3, { ok: 3 }); n++; console.log("✓ central, já atualizada, retoma (versão 3)");
// Disputa simultânea: as duas ações partem da versão 3; só uma grava, a outra vê conflito.
const [x, y] = await Promise.all([acao(cel, "cel", 3, "PAUSADO"), acao(central, "central", 3, "FECHADO")]);
assert.equal([x, y].filter((r) => r.ok).length, 1); assert.equal([x, y].filter((r) => r.conflito).length, 1);
n++; console.log("✓ ações simultâneas: uma vence, a outra recebe conflito", JSON.stringify([x, y]));
const fim = (await getDoc(doc(cel, "users/ana/estado/turnoAtual"))).data();
assert.equal(fim.versao, 4); n++; console.log("✓ versão final 4");

await env.cleanup();
console.log(`\n${n} verificações OK`);
