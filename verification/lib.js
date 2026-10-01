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

const keyStr = (k) => `${k.shop}:${k.item}:${k.part}`;
const cmpKey = (a, b) => a.item - b.item || a.part - b.part;

// ---------------- settlement (§7.12 / BATCHED) ----------------
// Caller must hold the ledger row lock for `k`.
async function settleInline(c, k, maxBatches = 20) {
  let settled = 0;
  for (let b = 0; b < maxBatches; b++) {
    const [rows] = await c.query(
      'SELECT reservation_id, entry_type, on_hand_delta, allocated_delta FROM ledger_pending_entries ' +
        'WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? ORDER BY reservation_id, entry_type LIMIT 500',
      [k.shop, k.item, k.part]);
    if (rows.length === 0) break;
    let dH = 0, dA = 0;
    for (const r of rows) { dH += Number(r.on_hand_delta); dA += Number(r.allocated_delta); }
    const [u] = await c.query(
      'UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity+?, allocated_quantity=allocated_quantity+? ' +
        'WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND on_hand_quantity+? >= 0 AND allocated_quantity+? >= 0',
      [dH, dA, k.shop, k.item, k.part, dH, dA]);
    if (u.affectedRows !== 1) throw new InvariantViolation('settle update ' + keyStr(k));
    const tuples = rows.map(() => '(?,?)').join(',');
    const params = [k.shop, k.item, k.part];
    for (const r of rows) params.push(r.reservation_id, r.entry_type);
    const [d] = await c.query(
      `DELETE FROM ledger_pending_entries WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND (reservation_id, entry_type) IN (${tuples})`,
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
      'WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? FOR UPDATE', [k.shop, k.item, k.part]);
  if (!l) throw new BizError('INVENTORY_NOT_FOUND');
  return { h: Number(l.h), a: Number(l.a), cap: Number(l.cap), status: l.status, mode: l.mode };
}

// ---------------- refill (§7.4) ----------------
async function partitionStatus(c, shop, part) {
  const [[p]] = await c.query('SELECT status FROM stock_partitions WHERE shop_id=? AND stock_partition_id=?', [shop, part]);
  if (!p) throw new BizError('STOCK_PARTITION_NOT_FOUND', { part });
  return p.status;
}

async function refillTx(c, k, need) {
  let l = await lockLedger(c, k);
  if (l.status === 'FROZEN') return { outcome: 'FROZEN' };
  // closed partitions accept no new allocation (plain read; closing is a business gate, not an invariant)
  if ((await partitionStatus(c, k.shop, k.part)) === 'CLOSED') return { outcome: 'CLOSED' };
  if (await settleInline(c, k)) l = await lockLedger(c, k);
  const [[{ p }]] = await c.query(
    'SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [k.shop, k.item, k.part]);
  const observed = Number(p);
  const free = l.h - l.a;
  const refill = Math.max(0, Math.min(l.cap - observed, free));
  if (refill > 0) {
    await c.query('UPDATE inventory_ledger SET allocated_quantity=allocated_quantity+? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?',
      [refill, k.shop, k.item, k.part]);
    const ids = Array.from({ length: refill }, uuid).sort(Buffer.compare);
    for (let i = 0; i < ids.length; i += 100) {
      const chunk = ids.slice(i, i + 100);
      await c.query('INSERT INTO reservation_units (shop_id, inventory_item_id, stock_partition_id, unit_id) VALUES ' + chunk.map(() => '(?,?,?,?)').join(','),
        chunk.flatMap((id) => [k.shop, k.item, k.part, id]));
    }
  }
  const potential = free + observed;
  if (potential < need) return { outcome: 'INSUFFICIENT', potential, refill };
  if (refill > 0) return { outcome: 'REFILLED', potential, refill };
  return { outcome: 'CONTENDED', potential, refill };
}

// ---------------- purchase limits (§7.13) ----------------
// Returns [[ruleId, {lim, qty}], ...] sorted by rule_id: every ACTIVE rule the lines fall under, with the quantity counted.
async function matchRules(c, shop, lines) {
  const parts = [...new Set(lines.map((l) => l.part))];
  const items = [...new Set(lines.map((l) => l.item))];
  const [rows] = await c.query(
    `SELECT rule_id, scope_type, stock_partition_id part, inventory_item_id item, per_buyer_limit lim FROM purchase_limit_rules
      WHERE shop_id=? AND status='ACTIVE' AND (
            (scope_type='PARTITION_ITEM' AND stock_partition_id IN (?) AND inventory_item_id IN (?))
         OR (scope_type='PARTITION_TOTAL' AND stock_partition_id IN (?) AND inventory_item_id=0)
         OR (scope_type='ITEM_ALL_PARTITIONS' AND stock_partition_id=-1 AND inventory_item_id IN (?)))`,
    [shop, parts, items, parts, items]);
  const out = [];
  for (const r of rows) {
    let qty = 0;
    for (const l of lines) {
      if (r.scope_type === 'PARTITION_ITEM' && Number(r.part) === l.part && Number(r.item) === l.item) qty += l.qty;
      else if (r.scope_type === 'PARTITION_TOTAL' && Number(r.part) === l.part) qty += l.qty;
      else if (r.scope_type === 'ITEM_ALL_PARTITIONS' && Number(r.item) === l.item) qty += l.qty;
    }
    if (qty > 0) out.push([Number(r.rule_id), { lim: Number(r.lim), qty }]);
  }
  return out.sort((a, b) => a[0] - b[0]);
}
async function usagesOf(c, shop, rid) {
  const [rows] = await c.query('SELECT rule_id, quantity FROM reservation_quota_usages WHERE shop_id=? AND reservation_id=? ORDER BY rule_id', [shop, rid]);
  return rows.map((r) => ({ rule: Number(r.rule_id), qty: Number(r.quantity) }));
}

// ---------------- reserve (§7.2) ----------------
// Order: claim units first (SKIP LOCKED, nothing written yet) -> header -> buyer quota -> delete units -> details.
// Inserting the header first made every PoolNotReady rollback leave a delete-marked unique-index entry; the retry
// with the same idempotency key then took S gap locks during the duplicate check and deadlocked with neighbours.
async function reserveTx(c, req) {
  // partition gate: plain read, no lock (a reserve racing a close is still a valid reservation)
  for (const part of new Set(req.lines.map((l) => l.part))) {
    if ((await partitionStatus(c, req.shop, part)) === 'CLOSED') throw new BizError('STOCK_PARTITION_CLOSED', { part });
  }
  const picked = [];
  for (const l of req.lines) {
    const [rows] = await c.query(
      'SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED',
      [req.shop, l.item, l.part, l.qty]);
    if (rows.length < l.qty) throw new PoolNotReady({ shop: req.shop, item: l.item, part: l.part }, l.qty);
    picked.push(rows.map((r) => r.unit_id));
  }
  const rid = uuid();
  const total = req.lines.reduce((s, l) => s + l.qty, 0);
  await c.query(
    'INSERT INTO reservations (shop_id, reservation_id, idempotency_key, request_hash, status, buyer_id, ttl_seconds, total_quantity, line_count, expires_at, claim_deadline_at) ' +
      "VALUES (?,?,?,?, 'ACTIVE', ?,?,?,?, UTC_TIMESTAMP(6) + INTERVAL ? SECOND, UTC_TIMESTAMP(6) + INTERVAL ? SECOND)",
    [req.shop, rid, req.idem, req.hash, req.buyer || null, req.ttl, total, req.lines.length, req.ttl, req.ttl + req.grace]);
  const rules = await matchRules(c, req.shop, req.lines);
  if (rules.length && !req.buyer) throw new BizError('BUYER_ID_REQUIRED');
  for (const [rule, { lim, qty }] of rules) { // rule_id ascending
    await c.query('INSERT INTO buyer_quotas (shop_id, rule_id, buyer_id, active_quantity) VALUES (?,?,?,?) ' +
      'ON DUPLICATE KEY UPDATE active_quantity = active_quantity + ?', [req.shop, rule, req.buyer, qty, qty]);
    const [[q]] = await c.query('SELECT active_quantity + claimed_quantity t FROM buyer_quotas WHERE shop_id=? AND rule_id=? AND buyer_id=?',
      [req.shop, rule, req.buyer]);
    if (Number(q.t) > lim) throw new BizError('BUYER_LIMIT_EXCEEDED', { rule });
    await c.query('INSERT INTO reservation_quota_usages (shop_id, reservation_id, rule_id, quantity) VALUES (?,?,?,?)', [req.shop, rid, rule, qty]);
  }
  for (let i = 0; i < req.lines.length; i++) {
    const l = req.lines[i];
    const ids = picked[i];
    const [d] = await c.query(
      `DELETE FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND unit_id IN (${ids.map(() => '?').join(',')})`,
      [req.shop, l.item, l.part, ...ids]);
    if (d.affectedRows !== l.qty) throw new InvariantViolation('reserve delete count');
  }
  await c.query('INSERT INTO reserved_quantities (shop_id, reservation_id, line_no, inventory_item_id, stock_partition_id, quantity) VALUES ' +
    req.lines.map(() => '(?,?,?,?,?,?)').join(','), req.lines.flatMap((l, i) => [req.shop, rid, i + 1, l.item, l.part, l.qty]));
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
        const s = this.soldOut.get(keyStr({ shop: req.shop, item: l.item, part: l.part }));
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
          if (r.outcome === 'CLOSED') throw new BizError('STOCK_PARTITION_CLOSED');
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
  const [rows] = await c.query('SELECT inventory_item_id item, stock_partition_id part, quantity qty FROM reserved_quantities WHERE shop_id=? AND reservation_id=? ORDER BY inventory_item_id, stock_partition_id', [shop, rid]);
  return rows.map((r) => ({ shop, item: Number(r.item), part: Number(r.part), qty: Number(r.qty) }));
}
// Move buyer quota exactly along the usages recorded at reserve time (never recompute from current rules).
async function quotaMove(c, shop, rid, buyer, activeSign, claimedSign) {
  for (const { rule, qty } of await usagesOf(c, shop, rid)) {
    const [u] = await c.query(
      'UPDATE buyer_quotas SET active_quantity = active_quantity + ?, claimed_quantity = claimed_quantity + ? WHERE shop_id=? AND rule_id=? AND buyer_id=? AND active_quantity + ? >= 0',
      [activeSign * qty, claimedSign * qty, shop, rule, buyer, activeSign * qty]);
    if (u.affectedRows !== 1) throw new InvariantViolation(`buyer quota rule ${rule} buyer ${buyer}`);
  }
}
async function modes(c, lines) {
  const out = [];
  for (const l of lines) {
    const [[m]] = await c.query('SELECT settlement_mode mode FROM inventory_ledger WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [l.shop, l.item, l.part]);
    out.push({ ...l, mode: m.mode });
  }
  return out;
}
async function applyLedgerOrPending(c, rid, lines, type) {
  const ls = await modes(c, lines);
  for (const l of ls.filter((x) => x.mode === 'SYNC')) {
    await lockLedger(c, l);
    const sql = type === 'CLAIM'
      ? 'UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity-?, allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND on_hand_quantity>=? AND allocated_quantity>=?'
      : 'UPDATE inventory_ledger SET allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND allocated_quantity>=?';
    const params = type === 'CLAIM' ? [l.qty, l.qty, l.shop, l.item, l.part, l.qty, l.qty] : [l.qty, l.shop, l.item, l.part, l.qty];
    const [u] = await c.query(sql, params);
    if (u.affectedRows !== 1) throw new InvariantViolation(type + ' ledger ' + keyStr(l));
  }
  for (const l of ls.filter((x) => x.mode === 'BATCHED')) {
    await c.query('INSERT INTO ledger_pending_entries (shop_id, inventory_item_id, stock_partition_id, reservation_id, entry_type, on_hand_delta, allocated_delta) VALUES (?,?,?,?,?,?,?)',
      [l.shop, l.item, l.part, rid, type, type === 'CLAIM' ? -l.qty : 0, -l.qty]);
  }
}

async function claimTx(c, shop, rid, pref, lateClaim = false) {
  const h = await lockHeader(c, shop, rid);
  if (h.status === 'CLAIMED') { if (h.pref === pref) return 'REPLAY'; throw new BizError('PAYMENT_REFERENCE_CONFLICT'); }
  if (h.status === 'CANCELLED') throw new BizError('INVALID_RESERVATION_STATE');
  if (h.status === 'EXPIRED') { if (!lateClaim) throw new BizError('RESERVATION_EXPIRED'); return lateClaimExpired(c, shop, rid, pref, h); }
  if (!h.beforeDeadline && !lateClaim) throw new BizError('RESERVATION_EXPIRED');
  const lines = await linesOf(c, shop, rid);
  await quotaMove(c, shop, rid, h.buyer, -1, +1);
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
  await quotaMove(c, shop, rid, h.buyer, -1, 0);
  await applyLedgerOrPending(c, rid, lines, 'RELEASE');
  const col = kind === 'EXPIRED' ? 'expired_at' : 'cancelled_at';
  await c.query(`UPDATE reservations SET status=?, ${col}=UTC_TIMESTAMP(6) WHERE shop_id=? AND reservation_id=?`, [kind, shop, rid]);
  return kind;
}

// §7.6.2: EXPIRED -> CLAIMED(LATE), re-acquire allocation (F first, then pool)
async function lateClaimExpired(c, shop, rid, pref, h) {
  const lines = await linesOf(c, shop, rid);
  // re-take the quota recorded at reserve time; re-check only rules that are still ACTIVE
  for (const { rule, qty } of await usagesOf(c, shop, rid)) {
    await c.query('UPDATE buyer_quotas SET claimed_quantity = claimed_quantity + ? WHERE shop_id=? AND rule_id=? AND buyer_id=?', [qty, shop, rule, h.buyer]);
    const [[q]] = await c.query(
      `SELECT b.active_quantity + b.claimed_quantity t, r.per_buyer_limit lim, r.status FROM buyer_quotas b JOIN purchase_limit_rules r ON r.rule_id=b.rule_id
        WHERE b.shop_id=? AND b.rule_id=? AND b.buyer_id=?`, [shop, rule, h.buyer]);
    if (q.status === 'ACTIVE' && Number(q.t) > Number(q.lim)) throw new BizError('BUYER_LIMIT_EXCEEDED', { rule });
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
        'SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED',
        [l.shop, l.item, l.part, fromPool]);
      if (rows.length < fromPool) {
        const [[{ p }]] = await c.query('SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [l.shop, l.item, l.part]);
        if (fromFree + Number(p) < l.qty) throw new BizError('LATE_CLAIM_INSUFFICIENT_STOCK');
        throw new BizError('INVENTORY_BUSY');
      }
      const ids = rows.map((r) => r.unit_id);
      const [d] = await c.query(`DELETE FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND unit_id IN (${ids.map(() => '?').join(',')})`,
        [l.shop, l.item, l.part, ...ids]);
      if (d.affectedRows !== fromPool) throw new InvariantViolation('late claim delete');
    }
    const [u] = await c.query('UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity-?, allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?',
      [l.qty, fromPool, l.shop, l.item, l.part]);
    if (u.affectedRows !== 1) throw new InvariantViolation('late claim ledger');
  }
  await c.query("UPDATE reservations SET status='CLAIMED', claim_mode='LATE', payment_reference=?, claimed_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND reservation_id=?", [pref, shop, rid]);
  return 'CLAIMED_LATE_REACQUIRED';
}

// Settler job for one key (BATCHED)
async function settleTx(c, k) { await lockLedger(c, k); return settleInline(c, k); }

// ---------------- logical stock partitions (§7.14) ----------------
const opHash = (o) => crypto.createHash('sha256').update(JSON.stringify(o)).digest();

async function createPartitionTx(c, shop, part, externalRef = null) {
  await c.query('INSERT INTO stock_partitions (shop_id, stock_partition_id, external_ref) VALUES (?,?,?)', [shop, part, externalRef]);
}
async function initializeTx(c, { shop, item, part, qty, opId, cap = 1000, mode = 'SYNC' }) {
  await c.query("INSERT INTO inventory_operations (shop_id, operation_id, line_no, request_hash, operation_type, inventory_item_id, stock_partition_id, delta_quantity, on_hand_after, allocated_after, pool_capacity_after) VALUES (?,?,1,?, 'INITIALIZE', ?,?,?,?,0,?)",
    [shop, opId, opHash({ shop, item, part, qty, cap }), item, part, qty, qty, cap]);
  await c.query('INSERT INTO inventory_ledger (shop_id, inventory_item_id, stock_partition_id, on_hand_quantity, pool_capacity, settlement_mode) VALUES (?,?,?,?,?,?)',
    [shop, item, part, qty, cap, mode]);
}
async function closePartitionTx(c, shop, part) {
  if (part === 0) throw new BizError('INVALID_ARGUMENT');
  const [u] = await c.query("UPDATE stock_partitions SET status='CLOSED', closed_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND stock_partition_id=? AND status='OPEN'", [shop, part]);
  return u.affectedRows === 1 ? 'CLOSED' : 'NOOP';
}

// Shared core: move `qty` of SKU `item` from partition src to dst. Caller has already inserted the operation rows.
// Lock order: ledgers by partition ascending (same SKU) -> pending entries (settle) -> units (reclaim).
async function moveBetweenPartitions(c, { shop, item, src, dst, qty, reclaimPool, dstCap, dstMode, qtyFromSource }) {
  const keys = [src, dst].sort((a, b) => a - b);
  const led = {};
  for (const part of keys) {
    const k = { shop, item, part };
    if (part === dst) {
      // create the destination ledger on first transfer; ODKU takes the row lock in sorted order
      await c.query('INSERT INTO inventory_ledger (shop_id, inventory_item_id, stock_partition_id, on_hand_quantity, pool_capacity, settlement_mode) VALUES (?,?,?,0,?,?) ' +
        'ON DUPLICATE KEY UPDATE shop_id = shop_id', [shop, item, part, dstCap, dstMode]);
    }
    let l = await lockLedger(c, k);
    if (l.status === 'FROZEN') throw new BizError('INVENTORY_FROZEN', { part });
    if (await settleInline(c, k)) l = await lockLedger(c, k);
    led[part] = l;
  }
  const s = led[src];
  if (qtyFromSource) qty = qtyFromSource(s);
  if (qty <= 0) return { qty: 0, reclaimed: 0 };
  if (s.h < qty) throw new BizError('INSUFFICIENT_STOCK', { part: src });
  const newH = s.h - qty;
  let reclaimed = 0;
  if (newH < s.a) {
    if (!reclaimPool) throw new BizError('STOCK_ALLOCATED');
    const need = s.a - newH;
    const [rows] = await c.query('SELECT unit_id FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED',
      [shop, item, src, need]);
    if (rows.length < need) {
      const [[{ p }]] = await c.query('SELECT COUNT(*) p FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [shop, item, src]);
      if (Number(p) < need) throw new BizError('STOCK_ALLOCATED'); // the gap is held by ACTIVE reservations
      throw new BizError('INVENTORY_BUSY');                          // units locked by in-flight reserves
    }
    const ids = rows.map((r) => r.unit_id);
    const [d] = await c.query(`DELETE FROM reservation_units WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=? AND unit_id IN (${ids.map(() => '?').join(',')})`,
      [shop, item, src, ...ids]);
    if (d.affectedRows !== need) throw new InvariantViolation('transfer reclaim delete');
    reclaimed = need;
  }
  await c.query('UPDATE inventory_ledger SET on_hand_quantity=?, allocated_quantity=allocated_quantity-? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?',
    [newH, reclaimed, shop, item, src]);
  await c.query('UPDATE inventory_ledger SET on_hand_quantity=on_hand_quantity+? WHERE shop_id=? AND inventory_item_id=? AND stock_partition_id=?', [qty, shop, item, dst]);
  return { qty, reclaimed };
}
async function snapshotOps(c, shop, opId, item, src, dst, reclaimed) {
  for (const [line, part] of [[1, src], [2, dst]]) {
    await c.query(
      `UPDATE inventory_operations o JOIN inventory_ledger l ON l.shop_id=o.shop_id AND l.inventory_item_id=o.inventory_item_id AND l.stock_partition_id=o.stock_partition_id
          SET o.on_hand_after=l.on_hand_quantity, o.allocated_after=l.allocated_quantity, o.pool_capacity_after=l.pool_capacity, o.pool_reclaimed_quantity=?
        WHERE o.shop_id=? AND o.operation_id=? AND o.line_no=?`, [line === 1 ? reclaimed : 0, shop, opId, line]);
  }
}

// Transfer: operation rows first (idempotency arbitration), then ledgers.
async function transferTx(c, { shop, item, src, dst, qty, opId, reclaimPool = true, dstCap = 1000, dstMode = 'SYNC' }) {
  if (src === dst || qty <= 0) throw new BizError('INVALID_ARGUMENT');
  if ((await partitionStatus(c, shop, dst)) === 'CLOSED') throw new BizError('STOCK_PARTITION_CLOSED', { part: dst });
  const hash = opHash({ shop, item, src, dst, qty, reclaimPool });
  await c.query("INSERT INTO inventory_operations (shop_id, operation_id, line_no, request_hash, operation_type, inventory_item_id, stock_partition_id, delta_quantity, on_hand_after, allocated_after, pool_capacity_after) VALUES " +
    "(?,?,1,?,'TRANSFER_OUT',?,?,?,0,0,1),(?,?,2,?,'TRANSFER_IN',?,?,?,0,0,1)",
    [shop, opId, hash, item, src, -qty, shop, opId, hash, item, dst, qty]);
  const r = await moveBetweenPartitions(c, { shop, item, src, dst, qty, reclaimPool, dstCap, dstMode });
  await snapshotOps(c, shop, opId, item, src, dst, r.reclaimed);
  return r;
}

// Return the remainder of a CLOSED partition to dst (normally partition 0) once no ACTIVE reservation is left.
// Quantity is only known after locking, so operation rows are written after the ledger locks here.
// A reserve that raced the close and committed after the ACTIVE count still cannot be lost: returning all of H
// would need to reclaim more units than the pool holds, so moveBetweenPartitions fails with STOCK_ALLOCATED.
async function returnPartition(c, { shop, item, part, dst = 0, opId }) {
  if ((await partitionStatus(c, shop, part)) !== 'CLOSED') throw new BizError('STOCK_PARTITION_NOT_CLOSED');
  const [[{ n }]] = await c.query(
    `SELECT COUNT(*) n FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
      WHERE q.shop_id=? AND q.inventory_item_id=? AND q.stock_partition_id=? AND r.status='ACTIVE'`, [shop, item, part]);
  const r = await moveBetweenPartitions(c, {
    shop, item, src: part, dst, reclaimPool: true, dstCap: 1000, dstMode: 'SYNC',
    qtyFromSource: (s) => {
      if (Number(n) > 0) throw new BizError('ACTIVE_RESERVATIONS_REMAIN', { active: Number(n) });
      return s.h;
    },
  });
  if (r.qty === 0) return { qty: 0 };
  const hash = opHash({ shop, item, part, dst, ret: true });
  await c.query("INSERT INTO inventory_operations (shop_id, operation_id, line_no, request_hash, operation_type, inventory_item_id, stock_partition_id, delta_quantity, on_hand_after, allocated_after, pool_capacity_after) VALUES " +
    "(?,?,1,?,'TRANSFER_OUT',?,?,?,0,0,1),(?,?,2,?,'TRANSFER_IN',?,?,?,0,0,1)",
    [shop, opId, hash, item, part, -r.qty, shop, opId, hash, item, dst, r.qty]);
  await snapshotOps(c, shop, opId, item, part, dst, r.reclaimed);
  return r;
}

// ---------------- audit (§11) — single consistent statement ----------------
async function audit(pool, k, initialPlusAdjust) {
  const [[r]] = await pool.query(
    `SELECT l.on_hand_quantity h, l.allocated_quantity a, l.pool_capacity cap,
       (SELECT COUNT(*) FROM reservation_units u WHERE u.shop_id=l.shop_id AND u.inventory_item_id=l.inventory_item_id AND u.stock_partition_id=l.stock_partition_id) p,
       (SELECT COALESCE(SUM(q.quantity),0) FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
          WHERE q.shop_id=l.shop_id AND q.inventory_item_id=l.inventory_item_id AND q.stock_partition_id=l.stock_partition_id AND r.status='ACTIVE') ract,
       (SELECT COALESCE(SUM(q.quantity),0) FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
          WHERE q.shop_id=l.shop_id AND q.inventory_item_id=l.inventory_item_id AND q.stock_partition_id=l.stock_partition_id AND r.status='CLAIMED') claimed,
       (SELECT COALESCE(SUM(e.on_hand_delta),0) FROM ledger_pending_entries e WHERE e.shop_id=l.shop_id AND e.inventory_item_id=l.inventory_item_id AND e.stock_partition_id=l.stock_partition_id) dh,
       (SELECT COALESCE(SUM(e.allocated_delta),0) FROM ledger_pending_entries e WHERE e.shop_id=l.shop_id AND e.inventory_item_id=l.inventory_item_id AND e.stock_partition_id=l.stock_partition_id) da,
       (SELECT COUNT(*) FROM inventory_operations o WHERE o.shop_id=l.shop_id AND o.inventory_item_id=l.inventory_item_id AND o.stock_partition_id=l.stock_partition_id) opcount,
       (SELECT COALESCE(SUM(o.delta_quantity),0) FROM inventory_operations o WHERE o.shop_id=l.shop_id AND o.inventory_item_id=l.inventory_item_id AND o.stock_partition_id=l.stock_partition_id) opdelta
     FROM inventory_ledger l WHERE l.shop_id=? AND l.inventory_item_id=? AND l.stock_partition_id=?`, [k.shop, k.item, k.part]);
  const v = Object.fromEntries(Object.entries(r).map(([a, b]) => [a, Number(b)]));
  const violations = [];
  const H = v.h + v.dh, A = v.a + v.da;
  if (!(H >= A && A >= 0)) violations.push(`H>=A>=0 fails: H=${H} A=${A}`);
  if (A !== v.p + v.ract) violations.push(`A=P+R fails: A=${A} P=${v.p} R=${v.ract}`);
  if (v.p > v.cap) violations.push(`P<=C fails: P=${v.p}`);
  // per-dimension total: explicit initial (ledger seeded without operation rows) or the operation log
  const base = initialPlusAdjust !== undefined ? initialPlusAdjust : (v.opcount > 0 ? v.opdelta : undefined);
  if (base !== undefined && base - v.claimed !== H) violations.push(`audit total fails: ops-claimed=${base - v.claimed} H=${H}`);
  return { ...v, H, A, valid: violations.length === 0, violations };
}

// SKU across all partitions: transfers cancel out, so Σ(H+dH) = initial + adjust − claimed.
async function auditSku(pool, shop, item, initialPlusAdjust) {
  const [[r]] = await pool.query(
    `SELECT (SELECT COALESCE(SUM(on_hand_quantity),0) FROM inventory_ledger WHERE shop_id=? AND inventory_item_id=?) h,
            (SELECT COALESCE(SUM(on_hand_delta),0) FROM ledger_pending_entries WHERE shop_id=? AND inventory_item_id=?) dh,
            (SELECT COALESCE(SUM(q.quantity),0) FROM reserved_quantities q JOIN reservations r ON r.shop_id=q.shop_id AND r.reservation_id=q.reservation_id
               WHERE q.shop_id=? AND q.inventory_item_id=? AND r.status='CLAIMED') claimed,
            (SELECT COALESCE(SUM(delta_quantity),0) FROM inventory_operations WHERE shop_id=? AND inventory_item_id=? AND operation_type IN ('INITIALIZE','ADJUST')) base,
            (SELECT COALESCE(SUM(delta_quantity),0) FROM inventory_operations WHERE shop_id=? AND inventory_item_id=? AND operation_type IN ('TRANSFER_OUT','TRANSFER_IN')) transfers`,
    [shop, item, shop, item, shop, item, shop, item, shop, item]);
  const v = Object.fromEntries(Object.entries(r).map(([a, b]) => [a, Number(b)]));
  const total = v.h + v.dh;
  const expected = (initialPlusAdjust !== undefined ? initialPlusAdjust : v.base) - v.claimed;
  const violations = [];
  if (total !== expected) violations.push(`SKU total fails: Σ(H+dH)=${total} expected=${expected}`);
  if (v.transfers !== 0) violations.push(`transfers do not cancel out: ${v.transfers}`);
  return { ...v, total, expected, valid: violations.length === 0, violations };
}

// buyer_quotas must equal the usages of that buyer's CLAIMED / ACTIVE reservations, per rule.
async function quotaMismatches(pool) {
  const [rows] = await pool.query(
    `SELECT b.rule_id, b.buyer_id, b.active_quantity, b.claimed_quantity,
            COALESCE(SUM(CASE WHEN r.status='ACTIVE'  THEN u.quantity END),0) exp_active,
            COALESCE(SUM(CASE WHEN r.status='CLAIMED' THEN u.quantity END),0) exp_claimed
       FROM buyer_quotas b
       LEFT JOIN reservations r ON r.shop_id=b.shop_id AND r.buyer_id=b.buyer_id
       LEFT JOIN reservation_quota_usages u ON u.shop_id=r.shop_id AND u.reservation_id=r.reservation_id AND u.rule_id=b.rule_id
      GROUP BY b.rule_id, b.buyer_id, b.active_quantity, b.claimed_quantity
     HAVING b.active_quantity <> exp_active OR b.claimed_quantity <> exp_claimed`);
  return rows;
}

// Wipe all data (dedicated test database only) and recreate the default partition 0 for `shop`.
const TABLES = ['reservation_units', 'inventory_ledger', 'reservations', 'reserved_quantities', 'ledger_pending_entries',
  'buyer_quotas', 'purchase_limit_rules', 'reservation_quota_usages', 'inventory_operations', 'stock_partitions'];
async function resetAll(c, shop = 1) {
  for (const t of TABLES) await c.query(`DELETE FROM ${t}`);
  await c.query('INSERT INTO stock_partitions (shop_id, stock_partition_id) VALUES (?, 0)', [shop]);
}
async function addLimitRule(c, { shop, scope, part, item, limit }) {
  const [r] = await c.query('INSERT INTO purchase_limit_rules (shop_id, scope_type, stock_partition_id, inventory_item_id, per_buyer_limit) VALUES (?,?,?,?,?)',
    [shop, scope, part, item, limit]);
  return r.insertId;
}

module.exports = { makePool, conn, uuid, sleep, txn, classify, PoolNotReady, BizError, InvariantViolation, Facade, refillTx, reserveTx, claimTx, releaseTx, settleTx, settleInline, audit, auditSku, keyStr, cmpKey,
  createPartitionTx, initializeTx, closePartitionTx, transferTx, returnPartition, resetAll, addLimitRule, quotaMismatches, TABLES };
