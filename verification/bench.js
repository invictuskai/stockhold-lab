// Micro-benchmarks on one hot SKU (local laptop MySQL; relative numbers only).
'use strict';
const crypto = require('crypto');
const L = require('./lib');
const K = { shop: 1, item: 100, loc: 1 };
const N = Number(process.env.N || 4000);
const CONC = Number(process.env.CONC || 32);

function pct(arr, p) { const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; }
async function runConcurrent(items, conc, fn) {
  const lat = []; let i = 0, ok = 0; const t0 = Date.now(); const errors = {};
  await Promise.all(Array.from({ length: conc }, async () => {
    while (i < items.length) {
      const it = items[i++]; const s = Date.now();
      try { await fn(it); ok++; } catch (e) { const k = e.code || L.classify(e); errors[k] = (errors[k] || 0) + 1; }
      lat.push(Date.now() - s);
    }
  }));
  const sec = (Date.now() - t0) / 1000;
  // perSec counts completed attempts (successes + business rejections)
  return { attempts: lat.length, ok, perSec: Math.round(lat.length / sec), p50: pct(lat, 0.5), p99: pct(lat, 0.99), max: Math.max(...lat), errors };
}
async function resetLedger(c, stock, mode) {
  for (const t of ['reservation_units', 'inventory_ledger', 'reservations', 'reserved_quantities', 'ledger_pending_entries', 'buyer_quotas', 'item_purchase_limits'])
    await c.query(`DELETE FROM ${t}`);
  await c.query('INSERT INTO inventory_ledger (shop_id,inventory_item_id,location_id,on_hand_quantity,pool_capacity,settlement_mode) VALUES (?,?,?,?,1000,?)', [K.shop, K.item, K.loc, stock, mode]);
}
const mkReq = (i, qty = 1) => ({ shop: K.shop, idem: 'b' + i + '-' + Math.random(), hash: crypto.randomBytes(32), ttl: 900, grace: 120, lines: [{ item: K.item, loc: K.loc, qty }] });

(async () => {
  const c = await L.conn();
  const pool = L.makePool(CONC), crit = L.makePool(16);
  const out = {};

  for (const mode of ['SYNC', 'BATCHED']) {
    await resetLedger(c, N * 2, mode);
    await L.txn(pool, (x) => L.refillTx(x, K, 1));
    const f = new L.Facade(pool, { soldOutTtlMs: 0 });
    const rids = [];
    const reserve = await runConcurrent(Array.from({ length: N }, (_, i) => mkReq(i)), CONC, async (r) => rids.push((await f.reserve(r)).rid));
    reserve.refillTx = f.stats.refillTx; reserve.retries = f.stats.retries;
    const claim = await runConcurrent(rids, 16, (rid) => L.txn(crit, (x) => L.claimTx(x, K.shop, rid, 'p-' + rid.toString('hex'))));
    const t0 = Date.now(); let settled = 0;
    if (mode === 'BATCHED') settled = await L.txn(pool, (x) => L.settleTx(x, K));
    const settleMs = Date.now() - t0;
    const a = await L.audit(c, K, N * 2);
    out[mode] = { reserve, claim, settled, settleMs, auditValid: a.valid, violations: a.violations };
    console.log(mode, JSON.stringify(out[mode]));
  }

  // cancel/expiry-style release throughput on one SKU
  for (const mode of ['SYNC', 'BATCHED']) {
    await resetLedger(c, N * 2, mode);
    await L.txn(pool, (x) => L.refillTx(x, K, 1));
    const f = new L.Facade(pool);
    const rids = [];
    await runConcurrent(Array.from({ length: N }, (_, i) => mkReq(i)), CONC, async (r) => rids.push((await f.reserve(r)).rid));
    const rel = await runConcurrent(rids, 16, (rid) => L.txn(crit, (x) => L.releaseTx(x, K.shop, rid, 'CANCELLED')));
    if (mode === 'BATCHED') await L.txn(pool, (x) => L.settleTx(x, K));
    const a = await L.audit(c, K, N * 2);
    out['release_' + mode] = { release: rel, auditValid: a.valid };
    console.log('release', mode, JSON.stringify(out['release_' + mode]));
  }

  // sold-out tail: cost of a sold-out request with and without the sold-out cache
  for (const ttl of [0, 300]) {
    await resetLedger(c, 0, 'BATCHED');
    const f = new L.Facade(pool, { soldOutTtlMs: ttl });
    const r = await runConcurrent(Array.from({ length: 5000 }, (_, i) => mkReq(i)), CONC, (q) => f.reserve(q));
    out['soldout_cache_' + ttl] = { ...r, refillTx: f.stats.refillTx, flightJoins: f.stats.flightJoins, fastRejects: f.stats.soldOutFastReject };
    console.log('soldout ttl=' + ttl, JSON.stringify(out['soldout_cache_' + ttl]));
  }

  require('fs').mkdirSync(__dirname + '/results', { recursive: true });
  require('fs').writeFileSync(__dirname + '/results/bench-result.json', JSON.stringify(out, null, 2));
  await pool.end(); await crit.end(); await c.end();
})().catch((e) => { console.error(e); process.exit(2); });
