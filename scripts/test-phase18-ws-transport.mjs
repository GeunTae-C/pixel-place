// 실제 송신 함수의 유한 경계·저장 확인 검증. k6 속도·bridge 저장 검증은 별도 시험 담당
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
if (!process.argv[2] || fs.existsSync(process.argv[2])) throw new Error('Explicit new results path required');
const source = fs.readFileSync(new URL('./phase18-k6.js', import.meta.url), 'utf8');
const script = source.replace(/^import .*;\r?\n/gm, '').replace(/export /g, '');
const results = [];
function run(name, mode, payload, count = 700, causal = false) {
  let now = 1000, closing = false, stopped = null, complete = null, applied = 0;
  const sockets = [], timers = [], batches = [], aborts = [], cleared = new Set();
  const input = { plan: { schemaVersion: causal ? 4 : 2, stage: 'A-1', cases: [{ workload: { schedule: { kind: 'fixed' }, wsConnections: 2, requestMillis: 10000, warmupSeconds: 1, measurementSeconds: 1 } }] }, expiresEpochMillis: 100000,
    bridge: 'http://bridge', target: 'http://target', limits: { wsTransport: { batchCount: 512, batchBytes: 524288, pendingCount: 8192, pendingBytes: 4194304, cacheCount: 16384, cacheBytes: 16777216 } } };
  const ctx = {
    __ENV: { PHASE18_K6_INPUT: 'fixture', PHASE18_K6_PHASE: 'ws' }, open: () => JSON.stringify(input), Trend: class {}, Counter: class {}, crypto: {}, sleep() {}, Date: { now: () => now },
    console: { error() {} }, exec: { test: { abort: reason => { aborts.push(reason); throw new Error('EXPECTED_ABORT'); } } },
    setInterval: f => { timers.push(f); return f; }, clearInterval(f) { cleared.add(f); },
    WebSocket: class { constructor() { this.closed = false; sockets.push(this); } close() { if (this.closed) return; this.closed = true; this.onclose({ code: 1000, reason: '' }); } },
    http: { post(url, text) {
      const value = JSON.parse(text);
      if (url.endsWith('/ws')) {
        batches.push({ text, ...value });
        if (mode === 'request_exception') throw new Error('injected request exception');
        applied += mode === 'partial_non200' ? Math.min(1, value.events.length) : value.events.length;
        if (mode === 'delay') now += 150;
        return { status: mode === 'non200' || mode === 'partial_non200' ? 503 : mode === 'response_lost' || mode === 'timeout' ? 0 : 200,
          json() { if (mode === 'malformed') throw new Error('bad JSON'); if (mode === 'missing') return { saved: true };
            return { clockFloorMillis: mode === 'missing_clock' ? undefined : mode === 'bad_clock' ? -1 : batches.length, saved: mode !== 'false', batchId: value.batchId, stop: mode === 'stop_in_ack' && value.events.some(e => e.event === 'message'), closing: false, count: mode === 'wrong_count' ? value.events.length + 1 : value.events.length }; } };
      }
      if (url.endsWith('/stop')) stopped = value;
      if (url.endsWith('/ws-transport-complete')) complete = value;
      return { status: 200, json: () => ({ closing, stop: false, epochMillis: now }) };
    } }
  };
  vm.createContext(ctx); vm.runInContext(script, ctx); ctx.websockets();
  try {
    for (const socket of sockets) socket.onopen();
    for (let n = 0; n < count; n++) sockets[n % 2].onmessage({ data: payload(n) });
    for (const timer of timers) timer();
    closing = true; for (const timer of timers) timer(); for (const timer of timers) timer();
  } catch (error) { if (error.message !== 'EXPECTED_ABORT') throw error; }
  if (mode === 'ok' || mode === 'delay') {
    assert.ok(complete, name); assert.equal(complete.pending, 0); assert.equal(complete.pendingBytes, 0); assert.equal(complete.acknowledged, count + 4);
    const ordinals = [0, 0];
    for (const event of batches.flatMap(b => b.events)) { assert.equal(event.receiveOrdinal, ++ordinals[event.connection]); assert.ok(event.epochMillis >= 1000); }
    for (const batch of batches) { assert.ok(Buffer.byteLength(batch.text) <= 524288); assert.ok(batch.events.length <= 512); }
    if (mode === 'delay') assert.equal(complete.maxBatchMillis, 150);
    assert.ok(complete.maxCacheEntries <= 16384 && complete.maxCacheBytes <= 16777216);
    if (name === 'connection-skew-cache-window') { assert.equal(complete.cacheMisses, 2500); assert.equal(complete.cacheHits, 2500); }
    results.push({ name, pass: true, received: complete.received, maxCount: Math.max(...batches.map(b => b.events.length)), maxBytes: Math.max(...batches.map(b => Buffer.byteLength(b.text))), maxPending: complete.maxPending });
  } else {
    const prior = batches.length;
    for (let n = 0; n < 100000; n++) sockets[n % 2].onmessage({ data: 'after failure' });
    for (const timer of timers) timer();
    assert.equal(batches.length, prior); assert.equal(cleared.size, 2); assert.ok(sockets.every(s => s.closed));
    assert.equal(aborts.length, 1); assert.ok(stopped, name); assert.equal(complete, null);
    if (mode === 'stop_in_ack') {
      assert.equal(stopped.wsTransport.unconfirmedBatches, 0); assert.equal(stopped.wsTransport.pending, 0);
      assert.equal(stopped.wsTransport.acknowledged, 514); assert.equal(stopped.wsTransport.failure.reason, 'controller_stop');
    } else {
      assert.equal(stopped.wsTransport.unconfirmedBatches, 1); assert.equal(stopped.wsTransport.acknowledged, 0); assert.ok(stopped.wsTransport.pending > 0);
      assert.ok(stopped.wsTransport.failure.batch); assert.equal(stopped.wsTransport.received, stopped.wsTransport.pending);
    }
    results.push({ name, pass: true, reason: stopped.wsTransport.failure.reason, appliedByFakeBridge: applied, acknowledged: stopped.wsTransport.acknowledged, pending: stopped.wsTransport.pending, lateCallbacksIgnored: 100000, timersCleared: cleared.size, socketsClosed: sockets.length });
  }
}
run('count512-and-residual', 'ok', n => JSON.stringify({ type: 'pixel', eventSeq: n + 1, tileVersion: 1, x: n, y: 1, color: 1 }), 1025);
run('utf8-envelope-byte-cut', 'ok', () => '한😀'.repeat(500));
run('max-escaped-payload-and-residual', 'ok', () => '\u0001'.repeat(4096), 70);
run('delayed-bridge-preserves-counts', 'delay', () => 'x', 1025);
run('connection-skew-cache-window', 'ok', n => JSON.stringify({eventSeq: n % 2500 + 1}), 5000);
run('busy-callback-stop-in-storage-ack', 'stop_in_ack', () => 'x', 1025);
for (const mode of ['non200', 'partial_non200', 'malformed', 'missing', 'false', 'wrong_count', 'timeout', 'response_lost', 'request_exception']) run(mode, mode, () => 'x');
run('causal-count-and-residual', 'ok', () => 'x', 1025, true);
run('causal-utf8-envelope-byte-cut', 'ok', () => '한😀'.repeat(500), 700, true);
run('causal-delayed-bridge', 'delay', () => 'x', 1025, true);
for (const mode of ['missing_clock', 'bad_clock', 'non200', 'partial_non200', 'malformed', 'missing', 'false', 'wrong_count', 'timeout', 'response_lost', 'request_exception']) run('causal-'+mode, mode, () => 'x', 700, true);
// 실제 request 함수의 claim 허가·응답 도착 후 경계. WS 생성 없이 HTTP 송신 호출만 관찰
for (const mode of ['authorized', 'stopped', 'phase-end', 'expiry', 'missing-authorization', 'legacy-state-stop']) {
  let now = 1000, targets = 0, cancels = 0, stateCalls = 0;
  const revised = mode !== 'legacy-state-stop';
  const fixture = { plan: { schemaVersion: revised ? 4 : 2, stage: 'A-1', cases: [{ workload: { schedule: { kind: 'fixed' }, writeRate: 1, readRate: 0, writeInFlight: 1, requestMillis: 10000, warmupSeconds: 1, measurementSeconds: 1 } }] }, bridge: 'http://bridge', target: 'http://target', expiresEpochMillis: 5000 };
  const context = { __ENV: { PHASE18_K6_INPUT: 'fixture', PHASE18_K6_PHASE: 'warmup' }, open: () => JSON.stringify(fixture), Date: { now: () => now }, Trend: class { add() {} }, Counter: class { add() {} },
    exec: { test: { abort: reason => { throw new Error(reason); } } }, http: { post(url, text) {
      if (url.endsWith('/claim')) { if (mode === 'phase-end') now = 2000; if (mode === 'expiry') now = 5000;
        return { status: 200, json: () => ({ send: mode !== 'stopped', reason: 'stopped', authorized: mode !== 'missing-authorization', id: 'owned', end: 2000, expires: 5000, due: 1000, epochMillis: 1000, token: 'fixture', x: 1, y: 1, color: 1 }) }; }
      if (url.endsWith('/state')) { stateCalls++; return { status: 200, json: () => ({ stop: true }) }; }
      if (url.endsWith('/cancel')) { cancels++; assert.equal(JSON.parse(text).reason, mode === 'phase-end' ? 'arrival_late' : 'stopped'); }
      if (url.startsWith('http://target')) { targets++; return { status: 200, body: '{}', timings: { duration: 1 } }; }
      return { status: 200, json: () => ({ accepted: true, stop: false, saved: true }) };
    } } };
  vm.createContext(context); vm.runInContext(script, context);
  if (mode === 'missing-authorization') assert.throws(() => context.request('write', 0, 1000), /missing claim send authorization/);
  else context.request('write', 0, 1000);
  assert.equal(targets, mode === 'authorized' ? 1 : 0);
  assert.equal(cancels, ['phase-end', 'expiry', 'legacy-state-stop'].includes(mode) ? 1 : 0);
  assert.equal(stateCalls, revised ? 0 : 1);
  results.push({ name: 'claim-' + mode, pass: true, targets, cancels, stateCalls });
}
fs.writeFileSync(process.argv[2], JSON.stringify({ source: 'actual phase18-k6.js through Node VM; no asynchronous WS request remains', results }, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ tests: results.length, failed: 0, cases: results }, null, 2));
