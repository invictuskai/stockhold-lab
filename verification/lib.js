// Reference implementation of the StockHold transaction protocols (docs/总体技术方案.md §7),
// used only to verify the design against a real MySQL 8.4 instance.
'use strict';
const mysql = require('mysql2/promise');
const crypto = require('crypto');

const DB = {
  host: process.env.MYSQL_HOST || '127.0.0.1',
  port: Number(process.env.MYSQL_PORT || 33306),
  user: process.env.MYSQL_USER || 'root',
  password: process.env.MYSQL_PASSWORD || '',
  database: process.env.MYSQL_DATABASE || 'stockhold',
  timezone: 'Z',
  supportBigNumbers: true,
};

function makePool(limit) {
  return mysql.createPool({ ...DB, connectionLimit: limit, waitForConnections: true, queueLimit: 0 });
}
async function conn() { return mysql.createConnection(DB); }

const uuid = () => Buffer.from(crypto.randomUUID().replace(/-/g, ''), 'hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class PoolNotReady extends Error { constructor(key, need) { super('PoolNotReady'); this.key = key; this.need = need; } }
class BizError extends Error { constructor(code, details) { super(code); this.code = code; this.details = details; } }
class InvariantViolation extends Error { constructor(msg) { super('INVARIANT: ' + msg); } }

// SQL error classification (§10.2)
function classify(e) {
  if (e instanceof PoolNotReady) return 'POOL_NOT_READY';
  if (e instanceof BizError) return 'BUSINESS';
  if (e instanceof InvariantViolation) return 'INVARIANT';
  if (e.errno === 1213) return 'DEADLOCK';
  if (e.errno === 1205) return 'LOCK_WAIT_TIMEOUT';
  if (e.errno === 1062) return 'DUPLICATE';
  if (e.errno === 3819) return 'CHECK_VIOLATION';
  return 'UNKNOWN';
}

async function txn(pool, fn) {
  const c = await pool.getConnection();
  try {
    await c.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED');
    await c.beginTransaction();
    try {
      const r = await fn(c);
      await c.commit();
      return r;
    } catch (e) {
      await c.rollback().catch(() => {});
      throw e;
    }
  } finally {
    c.release();
  }
}

const keyStr = (k) => `${k.shop}:${k.item}:${k.loc}`;
const cmpKey = (a, b) => a.item - b.item || a.loc - b.loc;

// ---------------- settlement (§7.12 / BATCHED) ----------------
// Caller must hold the ledger row lock for `k`.
async function settleInline(c, k, maxBatches = 20) {
  let settled = 0;
  for (let b = 0; b < maxBatches; b++) {
    const [rows] = await c.query(
      'SELECT reservation_id, entry_type, on_hand_delta, allocated_delta FROM ledger_pending_entries ' +
        'WHERE shop_id=? AND inventory_item_id=? AND location_id=? ORDER BY reservation_id, entry_type LIMIT 500',
      [k.shop, k.item, k.loc]);
    if (rows.length === 0) break;
    let dH = 0, dA = 0;
    for (const r of rows) { dH += Number(r.on_hand_delta); dA += Number(r.allocated_delta); }
    const [u] = await c.query(
      'UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity+?, allocated_quantity=allocated_quantity+? ' +
        'WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND on_hand_quantity+? >= 0 AND allocated_quantity+? >= 0',
      [dH, dA, k.shop, k.item, k.loc, dH, dA]);
    if (u.affectedRows !== 1) throw new InvariantViolation('settle update ' + keyStr(k));
    const tuples = rows.map(() => '(?,?)').join(',');
    const params = [k.shop, k.item, k.loc];
    for (const r of rows) params.push(r.reservation_id, r.entry_type);
    const [d] = await c.query(
      `DELETE FROM ledger_pending_entries WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND (reservation_id, entry_type) IN (${tuples})`,
      params);
    if (d.affectedRows !== rows.length) throw new InvariantViolation('settle delete count ' + keyStr(k));
    settled += rows.length;
    if (rows.length < 500) break;
  }
  return settled;
}

async function lockLedger(c, k) {
  const [[l]] = await c.query(
    'SELECT on_hand_quantity h, allocated_quantity a, pool_capacity cap, status, settlement_mode mode FROM inventory_ledger ' +
      'WHERE shop_id=? AND inventory_item_id=? AND location_id=? FOR UPDATE', [k.shop, k.item, k.loc]);
  if (!l) throw new BizError('INVENTORY_NOT_FOUND');
  return { h: Number(l.h), a: Number(l.a), cap: Number(l.cap), status: l.status, mode: l.mode };
}

// ---------------- refill (§7.4) ----------------
async function refillTx(c, k, need) {
  let l = await lockLedger(c, k);
  if (l.status === 'FROZEN') return { outcome: 'FROZEN' };
  if (await settleInline(c, k)) l = await lockLedger(c, k);
  const [[{ p }]] = await c.query(
    'SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=?', [k.shop, k.item, k.loc]);
  const observed = Number(p);
  const free = l.h - l.a;
  const refill = Math.max(0, Math.min(l.cap - observed, free));
  if (refill > 0) {
    await c.query('UPDATE inventory_ledger SET allocated_quantity=allocated_quantity+? WHERE shop_id=? AND inventory_item_id=? AND location_id=?',
      [refill, k.shop, k.item, k.loc]);
    const ids = Array.from({ length: refill }, uuid).sort(Buffer.compare);
    for (let i = 0; i < ids.length; i += 100) {
      const chunk = ids.slice(i, i + 100);
      await c.query('INSERT INTO reservation_units (shop_id, inventory_item_id, location_id, unit_id) VALUES ' + chunk.map(() => '(?,?,?,?)').join(','),
        chunk.flatMap((id) => [k.shop, k.item, k.loc, id]));
    }
  }
  const potential = free + observed;
  if (potential < need) return { outcome: 'INSUFFICIENT', potential, refill };
  if (refill > 0) return { outcome: 'REFILLED', potential, refill };
  return { outcome: 'CONTENDED', potential, refill };
}

// ---------------- buyer quota ----------------
async function limitsFor(c, shop, items) {
  if (items.length === 0) return new Map();
  const [rows] = await c.query(
    `SELECT inventory_item_id item, per_buyer_limit lim FROM item_purchase_limits WHERE shop_id=? AND inventory_item_id IN (${items.map(() => '?').join(',')})`,
    [shop, ...items]);
  return new Map(rows.map((r) => [Number(r.item), Number(r.lim)]));
}
function qtyByItem(lines) {
  const m = new Map();
  for (const l of lines) m.set(l.item, (m.get(l.item) || 0) + l.qty);
  return [...m.entries()].sort((a, b) => a[0] - b[0]);
}

// ---------------- reserve (§7.2) ----------------
// Order: claim units first (SKIP LOCKED, nothing written yet) -> header -> buyer quota -> delete units -> details.
// Inserting the header first made every PoolNotReady rollback leave a delete-marked unique-index entry; the retry
// with the same idempotency key then took S gap locks during the duplicate check and deadlocked with neighbours.
async function reserveTx(c, req) {
  const picked = [];
  for (const l of req.lines) {
    const [rows] = await c.query(
      'SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=? ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED',
      [req.shop, l.item, l.loc, l.qty]);
    if (rows.length < l.qty) throw new PoolNotReady({ shop: req.shop, item: l.item, loc: l.loc }, l.qty);
    picked.push(rows.map((r) => r.unit_id));
  }
  const rid = uuid();
  const total = req.lines.reduce((s, l) => s + l.qty, 0);
  await c.query(
    'INSERT INTO reservations (shop_id, reservation_id, idempotency_key, request_hash, status, buyer_id, ttl_seconds, total_quantity, line_count, expires_at, claim_deadline_at) ' +
      "VALUES (?,?,?,?, 'ACTIVE', ?,?,?,?, UTC_TIMESTAMP(6) + INTERVAL ? SECOND, UTC_TIMESTAMP(6) + INTERVAL ? SECOND)",
    [req.shop, rid, req.idem, req.hash, req.buyer || null, req.ttl, total, req.lines.length, req.ttl, req.ttl + req.grace]);
  if (req.buyer) {
    const per = qtyByItem(req.lines);
    const limits = await limitsFor(c, req.shop, per.map((x) => x[0]));
    for (const [item, qty] of per) {
      const lim = limits.get(item);
      if (lim === undefined) continue;
      await c.query('INSERT INTO buyer_quotas (shop_id, inventory_item_id, buyer_id, active_quantity) VALUES (?,?,?,?) ' +
        'ON DUPLICATE KEY UPDATE active_quantity = active_quantity + ?', [req.shop, item, req.buyer, qty, qty]);
      const [[q]] = await c.query('SELECT active_quantity + claimed_quantity t FROM buyer_quotas WHERE shop_id=? AND inventory_item_id=? AND buyer_id=?',
        [req.shop, item, req.buyer]);
      if (Number(q.t) > lim) throw new BizError('BUYER_LIMIT_EXCEEDED', { item });
    }
  }
  for (let i = 0; i < req.lines.length; i++) {
    const l = req.lines[i];
    const ids = picked[i];
    const [d] = await c.query(
      `DELETE FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND unit_id IN (${ids.map(() => '?').join(',')})`,
      [req.shop, l.item, l.loc, ...ids]);
    if (d.affectedRows !== l.qty) throw new InvariantViolation('reserve delete count');
  }
  await c.query('INSERT INTO reserved_quantities (shop_id, reservation_id, line_no, inventory_item_id, location_id, quantity) VALUES ' +
    req.lines.map(() => '(?,?,?,?,?,?)').join(','), req.lines.flatMap((l, i) => [req.shop, rid, i + 1, l.item, l.loc, l.qty]));
  return rid;
}

// Facade (§7.3): no transaction; per-process single-flight refill; bounded retries.
class Facade {
  constructor(pool, opts = {}) {
    this.pool = pool;
    this.flights = new Map();
    this.lastRefillAt = new Map();
    this.soldOut = new Map(); // key -> {potential, until}
    this.opts = { attempts: 5, budgetMs: 2000, immediate: 2, soldOutTtlMs: 0, ...opts };
    this.stats = { refillTx: 0, flightJoins: 0, soldOutFastReject: 0, retries: 0, deadlocks: 0, lockTimeouts: 0 };
  }
  refillSingleFlight(k, need) {
    const ks = keyStr(k);
    let f = this.flights.get(ks);
    if (f) { this.stats.flightJoins++; return f; }
    this.stats.refillTx++;
    f = txn(this.pool, (c) => refillTx(c, k, need))
      .then((r) => { if (r.refill > 0) this.lastRefillAt.set(ks, Date.now()); return r; })
      .finally(() => this.flights.delete(ks));
    this.flights.set(ks, f);
    return f;
  }
  async reserve(req) {
    // sold-out fast path (秒杀扩展): reject without touching the DB while a recent INSUFFICIENT verdict is fresh
    if (this.opts.soldOutTtlMs > 0) {
      for (const l of req.lines) {
        const s = this.soldOut.get(keyStr({ shop: req.shop, item: l.item, loc: l.loc }));
        if (s && s.until > Date.now() && l.qty > s.potential) { this.stats.soldOutFastReject++; throw new BizError('INSUFFICIENT_STOCK', { fast: true }); }
      }
    }
    const deadline = Date.now() + this.opts.budgetMs;
    let immediate = 0;
    for (let attempt = 1; attempt <= this.opts.attempts && Date.now() < deadline; attempt++) {
      const startedAt = Date.now();
      try {
        return { rid: await txn(this.pool, (c) => reserveTx(c, req)), replay: false };
      } catch (e) {
        const kind = classify(e);
        if (kind === 'DUPLICATE' && /uq_reservation_idempotency/.test(e.message)) {
          const [[r]] = await this.pool.query('SELECT reservation_id, request_hash FROM reservations WHERE shop_id=? AND idempotency_key=?', [req.shop, req.idem]);
          if (!r) continue; // racing transaction rolled back: retry
          if (!Buffer.from(r.request_hash).equals(req.hash)) throw new BizError('IDEMPOTENCY_CONFLICT');
          return { rid: r.reservation_id, replay: true };
        }
        if (kind === 'POOL_NOT_READY') {
          const r = await this.refillSingleFlight(e.key, e.need);
          if (r.outcome === 'INSUFFICIENT' && e.need > r.potential) {
            if (this.opts.soldOutTtlMs > 0) this.soldOut.set(keyStr(e.key), { potential: r.potential, until: Date.now() + this.opts.soldOutTtlMs });
            throw new BizError('INSUFFICIENT_STOCK');
          }
          if (r.outcome === 'FROZEN') throw new BizError('INVENTORY_FROZEN');
          const refilledAfter = (this.lastRefillAt.get(keyStr(e.key)) || 0) >= startedAt;
          this.stats.retries++;
          if ((r.outcome === 'REFILLED' || refilledAfter) && immediate < this.opts.immediate) { immediate++; continue; }
          await sleep(20 + Math.random() * 60);
          continue;
        }
        if (kind === 'DEADLOCK' || kind === 'LOCK_WAIT_TIMEOUT') {
          kind === 'DEADLOCK' ? this.stats.deadlocks++ : this.stats.lockTimeouts++;
          this.stats.retries++;
          await sleep(20 + Math.random() * 60);
          continue;
        }
        throw e;
      }
    }
    throw new BizError('INVENTORY_BUSY');
  }
}

// ---------------- claim / release (§7.6, §7.7, BATCHED) ----------------
async function lockHeader(c, shop, rid) {
  const [[h]] = await c.query(
    'SELECT status, payment_reference pref, buyer_id buyer FROM reservations WHERE shop_id=? AND reservation_id=? FOR UPDATE', [shop, rid]);
  if (!h) throw new BizError('RESERVATION_NOT_FOUND');
  const [[t]] = await c.query('SELECT UTC_TIMESTAMP(6) < claim_deadline_at before_deadline FROM reservations WHERE shop_id=? AND reservation_id=?', [shop, rid]);
  h.beforeDeadline = Number(t.before_deadline) === 1;
  return h;
}
async function linesOf(c, shop, rid) {
  const [rows] = await c.query('SELECT inventory_item_id item, location_id loc, quantity qty FROM reserved_quantities WHERE shop_id=? AND reservation_id=? ORDER BY inventory_item_id, location_id', [shop, rid]);
  return rows.map((r) => ({ shop, item: Number(r.item), loc: Number(r.loc), qty: Number(r.qty) }));
}
async function quotaMove(c, shop, buyer, lines, activeDelta, claimedDelta) {
  if (!buyer) return;
  for (const [item, qty] of qtyByItem(lines)) {
    const [u] = await c.query(
      'UPDATE buyer_quotas SET active_quantity = active_quantity + ?, claimed_quantity = claimed_quantity + ? WHERE shop_id=? AND inventory_item_id=? AND buyer_id=? AND active_quantity + ? >= 0',
      [activeDelta * qty, claimedDelta * qty, shop, item, buyer, activeDelta * qty]);
    if (u.affectedRows !== 1) {
      // items without a purchase limit have no quota row: that is fine only if no limit exists
      const lim = await limitsFor(c, shop, [item]);
      if (lim.has(item)) throw new InvariantViolation('buyer quota ' + buyer + ' item ' + item);
    }
  }
}
async function modes(c, lines) {
  const out = [];
  for (const l of lines) {
    const [[m]] = await c.query('SELECT settlement_mode mode FROM inventory_ledger WHERE shop_id=? AND inventory_item_id=? AND location_id=?', [l.shop, l.item, l.loc]);
    out.push({ ...l, mode: m.mode });
  }
  return out;
}
async function applyLedgerOrPending(c, rid, lines, type) {
  const ls = await modes(c, lines);
  for (const l of ls.filter((x) => x.mode === 'SYNC')) {
    await lockLedger(c, l);
    const sql = type === 'CLAIM'
      ? 'UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity-?, allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND on_hand_quantity>=? AND allocated_quantity>=?'
      : 'UPDATE inventory_ledger SET allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND allocated_quantity>=?';
    const params = type === 'CLAIM' ? [l.qty, l.qty, l.shop, l.item, l.loc, l.qty, l.qty] : [l.qty, l.shop, l.item, l.loc, l.qty];
    const [u] = await c.query(sql, params);
    if (u.affectedRows !== 1) throw new InvariantViolation(type + ' ledger ' + keyStr(l));
  }
  for (const l of ls.filter((x) => x.mode === 'BATCHED')) {
    await c.query('INSERT INTO ledger_pending_entries (shop_id, inventory_item_id, location_id, reservation_id, entry_type, on_hand_delta, allocated_delta) VALUES (?,?,?,?,?,?,?)',
      [l.shop, l.item, l.loc, rid, type, type === 'CLAIM' ? -l.qty : 0, -l.qty]);
  }
}

async function claimTx(c, shop, rid, pref, lateClaim = false) {
  const h = await lockHeader(c, shop, rid);
  if (h.status === 'CLAIMED') { if (h.pref === pref) return 'REPLAY'; throw new BizError('PAYMENT_REFERENCE_CONFLICT'); }
  if (h.status === 'CANCELLED') throw new BizError('INVALID_RESERVATION_STATE');
  if (h.status === 'EXPIRED') { if (!lateClaim) throw new BizError('RESERVATION_EXPIRED'); return lateClaimExpired(c, shop, rid, pref, h); }
  if (!h.beforeDeadline && !lateClaim) throw new BizError('RESERVATION_EXPIRED');
  const lines = await linesOf(c, shop, rid);
  await quotaMove(c, shop, h.buyer, lines, -1, +1);
  await applyLedgerOrPending(c, rid, lines, 'CLAIM');
  await c.query("UPDATE reservations SET status='CLAIMED', claim_mode=?, payment_reference=?, claimed_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND reservation_id=?",
    [h.beforeDeadline ? 'RESERVED' : 'LATE', pref, shop, rid]);
  return h.beforeDeadline ? 'CLAIMED' : 'CLAIMED_LATE';
}

async function releaseTx(c, shop, rid, kind) {
  const h = await lockHeader(c, shop, rid);
  if (h.status !== 'ACTIVE') return 'NOOP_' + h.status;
  if (kind === 'EXPIRED' && h.beforeDeadline) return 'NOT_DUE';
  const lines = await linesOf(c, shop, rid);
  await quotaMove(c, shop, h.buyer, lines, -1, 0);
  await applyLedgerOrPending(c, rid, lines, 'RELEASE');
  const col = kind === 'EXPIRED' ? 'expired_at' : 'cancelled_at';
  await c.query(`UPDATE reservations SET status=?, ${col}=UTC_TIMESTAMP(6) WHERE shop_id=? AND reservation_id=?`, [kind, shop, rid]);
  return kind;
}

// §7.6.2: EXPIRED -> CLAIMED(LATE), re-acquire allocation (F first, then pool)
async function lateClaimExpired(c, shop, rid, pref, h) {
  const lines = await linesOf(c, shop, rid);
  if (h.buyer) {
    const per = qtyByItem(lines);
    const limits = await limitsFor(c, shop, per.map((x) => x[0]));
    for (const [item, qty] of per) {
      if (!limits.has(item)) continue;
      await c.query('UPDATE buyer_quotas SET claimed_quantity = claimed_quantity + ? WHERE shop_id=? AND inventory_item_id=? AND buyer_id=?', [qty, shop, item, h.buyer]);
      const [[q]] = await c.query('SELECT active_quantity + claimed_quantity t FROM buyer_quotas WHERE shop_id=? AND inventory_item_id=? AND buyer_id=?', [shop, item, h.buyer]);
      if (Number(q.t) > limits.get(item)) throw new BizError('BUYER_LIMIT_EXCEEDED');
    }
  }
  // lock order: all ledger rows (sorted) first, then units (§7.1)
  const ledgers = [];
  for (const l of lines) {
    let led = await lockLedger(c, l);
    if (led.status === 'FROZEN') throw new BizError('INVENTORY_FROZEN');
    if (await settleInline(c, l)) led = await lockLedger(c, l);
    ledgers.push(led);
  }
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i], led = ledgers[i];
    const fromFree = Math.min(l.qty, led.h - led.a);
    const fromPool = l.qty - fromFree;
    if (fromPool > 0) {
      const [rows] = await c.query(
        'SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=? ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED',
        [l.shop, l.item, l.loc, fromPool]);
      if (rows.length < fromPool) {
        const [[{ p }]] = await c.query('SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=?', [l.shop, l.item, l.loc]);
        if (fromFree + Number(p) < l.qty) throw new BizError('LATE_CLAIM_INSUFFICIENT_STOCK');
        throw new BizError('INVENTORY_BUSY');
      }
      const ids = rows.map((r) => r.unit_id);
      const [d] = await c.query(`DELETE FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND location_id=? AND unit_id IN (${ids.map(() => '?').join(',')})`,
        [l.shop, l.item, l.loc, ...ids]);
      if (d.affectedRows !== fromPool) throw new InvariantViolation('late claim delete');
    }
    const [u] = await c.query('UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity-?, allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND location_id=?',
      [l.qty, fromPool, l.shop, l.item, l.loc]);
    if (u.affectedRows !== 1) throw new InvariantViolation('late claim ledger');
  }
  await c.query("UPDATE reservations SET status='CLAIMED', claim_mode='LATE', payment_reference=?, claimed_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND reservation_id=?", [pref, shop, rid]);
  return 'CLAIMED_LATE_REACQUIRED';
}

// Settler job for one key (BATCHED)
async function settleTx(c, k) { await lockLedger(c, k); return settleInline(c, k); }

// ---------------- audit (§11) — single consistent statement ----------------
async function audit(pool, k, initialPlusAdjust) {
  const [[r]] = await pool.query(
    `SELECT l.on_hand_quantity h, l.allocated_quantity a, l.pool_capacity cap,
       (SELECT COUNT(*) FROM reservation_units u WHERE u.shop_id=l.shop_id AND u.inventory_item_id=l.inventory_item_id AND u.location_id=l.location_id) p,
       (SELECT COALESCE(SUM(q.quantity),0) FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
          WHERE q.shop_id=l.shop_id AND q.inventory_item_id=l.inventory_item_id AND q.location_id=l.location_id AND r.status='ACTIVE') ract,
       (SELECT COALESCE(SUM(q.quantity),0) FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
          WHERE q.shop_id=l.shop_id AND q.inventory_item_id=l.inventory_item_id AND q.location_id=l.location_id AND r.status='CLAIMED') claimed,
       (SELECT COALESCE(SUM(e.on_hand_delta),0) FROM ledger_pending_entries e WHERE e.shop_id=l.shop_id AND e.inventory_item_id=l.inventory_item_id AND e.location_id=l.location_id) dh,
       (SELECT COALESCE(SUM(e.allocated_delta),0) FROM ledger_pending_entries e WHERE e.shop_id=l.shop_id AND e.inventory_item_id=l.inventory_item_id AND e.location_id=l.location_id) da
     FROM inventory_ledger l WHERE l.shop_id=? AND l.inventory_item_id=? AND l.location_id=?`, [k.shop, k.item, k.loc]);
  const v = Object.fromEntries(Object.entries(r).map(([a, b]) => [a, Number(b)]));
  const violations = [];
  const H = v.h + v.dh, A = v.a + v.da;
  if (!(H >= A && A >= 0)) violations.push(`H>=A>=0 fails: H=${H} A=${A}`);
  if (A !== v.p + v.ract) violations.push(`A=P+R fails: A=${A} P=${v.p} R=${v.ract}`);
  if (v.p > v.cap) violations.push(`P<=C fails: P=${v.p}`);
  if (initialPlusAdjust !== undefined && initialPlusAdjust - v.claimed !== H) violations.push(`audit total fails: initial-claimed=${initialPlusAdjust - v.claimed} H=${H}`);
  return { ...v, H, A, valid: violations.length === 0, violations };
}

module.exports = { makePool, conn, uuid, sleep, txn, classify, PoolNotReady, BizError, InvariantViolation, Facade, refillTx, reserveTx, claimTx, releaseTx, settleTx, settleInline, audit, keyStr, cmpKey };
