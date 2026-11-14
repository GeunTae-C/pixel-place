// k6가 실제 HTTP/WS와 arrival-rate를 소유. 계정/phase 공유 상태는 인증된 로컬 bridge가 소유
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { WebSocket } from 'k6/websockets';
import { setInterval, clearInterval } from 'k6/timers';
import crypto from 'k6/crypto';
import { Trend, Counter } from 'k6/metrics';

// 전체 Plan/source inventory는 원본 input 파일에 유지. 각 VU는 송수신에 필요한 값만 보유
const input = (() => {
  const source = JSON.parse(open(__ENV.PHASE18_K6_INPUT));
  return { plan: { schemaVersion: source.plan.schemaVersion, stage: source.plan.stage, cases: [{ workload: source.plan.cases[0].workload }] },
    bridge: source.bridge, target: source.target, expiresEpochMillis: source.expiresEpochMillis, limits: source.limits };
})();
if (__ENV.PHASE18_K6_FORCE_THRESHOLD === '1' && input.plan.stage !== 'A-1') throw new Error('Threshold fixture is A-1 only');
const phase = __ENV.PHASE18_K6_PHASE;
if (!['bootstrap', 'ws', 'initial-read', 'warmup', 'measurement'].includes(phase)) throw new Error('Unsupported phase18 input phase');
const w = input.plan.cases[0].workload;
const scheduled = ['users', 'smoke-s2'].includes(w.schedule.kind);
const seconds = phase === 'bootstrap' ? Math.max(1, w.requestMillis / 1000) : phase === 'initial-read' ? Math.ceil(w.wsConnections / 100) : phase === 'warmup' ? w.warmupSeconds : w.measurementSeconds;
const acceptedDuration = new Trend('accepted_http_duration', true);
const targetStarted = new Counter('target_started');
const targetNotSent = new Counter('target_not_sent');
const scenarios = {};
function count(kind) {
  if (phase === 'bootstrap') return kind === 'write' ? 1 : 0;
  if (phase === 'initial-read') return kind === 'read' ? w.wsConnections : 0;
  if (!scheduled) return w[kind + 'Rate'] * seconds;
  if (phase === 'warmup' && (kind === 'write' || w.schedule.kind === 'smoke-s2')) return 0;
  if (w.schedule.kind === 'smoke-s2') return 4;
  return Math.ceil(seconds * (kind === 'write' ? w.schedule.activeUsers : w.wsConnections) / (kind === 'write' ? w.schedule.writePeriodSeconds : w.schedule.readPeriodSeconds));
}
function owners(kind) { return phase === 'initial-read' ? w.wsConnections : kind === 'write' ? w.schedule.activeUsers : w.wsConnections; }
if (phase === 'ws') scenarios.ws = { executor: 'shared-iterations', vus: 1, iterations: 1, exec: 'websockets', maxDuration: `${Math.max(1, Math.ceil((input.expiresEpochMillis - Date.now()) / 1000))}s`, gracefulStop: '2s' };
else for (const kind of ['write', 'read']) {
  if (!count(kind)) continue;
  if (phase === 'bootstrap') scenarios[kind] = { executor: 'shared-iterations', vus: 1, iterations: 1, exec: kind, maxDuration: `${seconds + 5}s` };
  else if (scheduled || phase === 'initial-read') scenarios[kind] = { executor: 'per-vu-iterations', vus: owners(kind), iterations: 1, exec: kind, maxDuration: `${seconds + w.requestMillis / 1000 + 5}s`, gracefulStop: `${w.requestMillis / 1000 + 1}s` };
  else scenarios[kind] = { executor: 'constant-arrival-rate', rate: w[kind + 'Rate'], timeUnit: '1s', duration: `${seconds}s`, preAllocatedVUs: w[kind + 'InFlight'], maxVUs: w[kind + 'InFlight'], exec: kind, gracefulStop: `${w.requestMillis / 1000 + 1}s` };
}
const thresholds = {};
if (phase === 'measurement' && count('write')) thresholds.accepted_http_duration = [
  { threshold: __ENV.PHASE18_K6_FORCE_THRESHOLD === '1' ? 'p(95)<0' : 'p(95)<=100', abortOnFail: false },
  { threshold: 'p(99)<=250', abortOnFail: false },
];
export const options = {
  scenarios, thresholds, maxRedirects: 0, throw: false, discardResponseBodies: false,
  setupTimeout: '15s', teardownTimeout: '15s',
  systemTags: ['scenario', 'status', 'method', 'expected_response'],
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};
function bridgeParams() { return { headers: { 'X-Bridge-Key': __ENV.PIXEL_PLACE_BENCH_JWT_KEY, 'Content-Type': 'application/json' }, timeout: '5s', redirects: 0, tags: { role: 'control', phase } }; }
function rpc(path, data = {}) {
  const response = http.post(input.bridge + path, JSON.stringify(data), bridgeParams());
  if (response.status !== 200) exec.test.abort('required bridge operation failed');
  return response.json();
}
export function setup() {
  if (phase !== 'ws') rpc('/begin', { phase });
  return { anchor: Date.now() + 500 };
}
function due(kind, ordinal) {
  if (phase === 'initial-read') return ordinal * 10;
  if (w.schedule.kind === 'smoke-s2') return (kind === 'write' ? [0, 1, 200, 201] : [10, 60, 110, 160])[ordinal] * 1000;
  if (w.schedule.kind === 'users') return Math.floor(ordinal * (kind === 'write' ? w.schedule.writePeriodSeconds : w.schedule.readPeriodSeconds) * 1000 / owners(kind));
  return 0;
}
function run(kind, data) {
  const total = count(kind), index = exec.scenario.iterationInTest;
  if (!scheduled && phase !== 'initial-read' && index >= total) {
    targetNotSent.add(1, { kind, phase, reason: 'executor_outside_plan' });
    return;
  }
  const start = scheduled || phase === 'initial-read' ? data.anchor : exec.scenario.startTime;
  if (scheduled || phase === 'initial-read') {
    for (let ordinal = index; ordinal < total; ordinal += owners(kind)) {
      const target = start + due(kind, ordinal);
      while (Date.now() < target) {
        if (Date.now() >= input.expiresEpochMillis) return;
        const remaining = target - Date.now();
        // Windows timer 양자화가 10ms 초기 GET 창을 통째로 넘기지 않도록 마지막20ms만 유한 대기
        if (remaining > 20) {
          if (rpc('/state').stop) return;
          sleep(Math.min(0.05, Math.max(0, (remaining - 20) / 1000)));
        }
      }
      request(kind, ordinal, start);
    }
    // 반복이 먼저 끝나도 원래 phase 기간은 소진/결과와 별도로 유지
    while (Date.now() < start + seconds * 1000) {
      if (Date.now() >= input.expiresEpochMillis || rpc('/state').stop) return;
      sleep(Math.min(0.1, (start + seconds * 1000 - Date.now()) / 1000));
    }
  } else request(kind, index, start);
}
export function write(data) { run('write', data); }
export function read(data) { run('read', data); }
function request(kind, ordinal, start) {
  const before = Date.now();
  const assignment = rpc('/claim', { phase, kind, ordinal, scenarioStart: start });
  const after = Date.now();
  if (!assignment.send) { targetNotSent.add(1, { kind, phase, reason: assignment.reason }); return; }
  // v4는 claim 마지막 STOP 검사를 송신 허가로 사용. 그 뒤 만료·기간은 실제 송신 직전에 재검사
  const revised = input.plan.schemaVersion === 4;
  if (revised && assignment.authorized !== true) exec.test.abort('missing claim send authorization');
  const state = revised ? { stop: false } : rpc('/state');
  const now = Date.now();
  const reason = state.stop || now >= assignment.expires ? 'stopped' : now >= assignment.end || (scheduled || phase === 'initial-read') && (now < assignment.due || now >= assignment.due + assignment.lateMillis) ? 'arrival_late' : '';
  if (reason) { rpc('/cancel', { id: assignment.id, reason }); targetNotSent.add(1, { kind, phase, reason }); return; }
  const headers = { 'X-Phase18-Request': assignment.id, 'Origin': 'http://localhost:3000' };
  if (kind === 'write') { headers.Authorization = `Bearer ${assignment.token}`; headers['Content-Type'] = 'application/json'; }
  else headers['Accept-Encoding'] = 'gzip';
  const params = { headers, timeout: `${w.requestMillis}ms`, redirects: 0, responseType: kind === 'read' ? 'binary' : 'text', tags: { role: 'target', kind, phase } };
  targetStarted.add(1, { kind, phase });
  const started = Date.now();
  const response = kind === 'write'
    ? http.post(input.target + '/api/pixels', JSON.stringify({ x: assignment.x, y: assignment.y, color: assignment.color }), params)
    : http.get(`${input.target}/api/tiles/0/${Math.floor(assignment.x / 256)}/${Math.floor(assignment.y / 256)}`, params);
  const bodyAt = Date.now();
  const record = { id: assignment.id, startedEpochMillis: started, bodyEpochMillis: bodyAt, bridgeBeforeEpochMillis: before, bridgeAfterEpochMillis: after, bridgeEpochMillis: assignment.epochMillis, status: response.status, durationMillis: response.timings.duration };
  if (kind === 'write') record.body = response.body && response.body.length <= 4096 ? response.body : '{}';
  else {
    record.decodedLength = response.body ? response.body.byteLength : 0;
    record.version = response.headers['X-Tile-Version'] || '';
    record.contentType = response.headers['Content-Type'] || '';
    record.encoding = response.headers['Content-Encoding'] || '';
    record.cacheControl = response.headers['Cache-Control'] || '';
    record.sha256 = record.decodedLength === 65536 ? crypto.sha256(response.body, 'hex') : '';
  }
  const saved = rpc('/result', record);
  if (saved.accepted && kind === 'write' && phase === 'measurement') acceptedDuration.add(response.timings.duration);
  if (saved.stop) exec.test.abort('target result requires STOP');
}
export function websockets() {
  const limits = input.limits.wsTransport;
  if (!limits || limits.batchCount !== 512 || limits.batchBytes !== 524288 || limits.pendingCount !== 8192 || limits.pendingBytes !== 4194304 || limits.cacheCount !== 16384 || limits.cacheBytes !== 16777216) throw new Error('Unsupported WS transport limits');
  const sockets = [], sequences = [], queue = [];
  const causal = input.plan.schemaVersion === 4;
  let callbackFloorMillis = 0;
  // 같은 broadcast body의 JSON escape만 재사용. 모든 delivery는 별도 전송·전수 검증
  const bodyCache = new Map();
  let cacheBytes = 0, flushTimer = null, timer = null;
  let closing = false, closed = 0, inFlight = null, nextBatch = 0, failed = false;
  let pending = 0, pendingBytes = 0;
  const stats = { received: 0, acknowledged: 0, batches: 0, maxPending: 0, maxPendingBytes: 0, maxAgeMillis: 0, maxBatchMillis: 0, totalBatchMillis: 0, maxBatchCount: 0, maxBatchBytes: 0, unconfirmedBatches: 0, afterFailureCallbacks: 0, maxCacheBytes: 0, maxCacheEntries: 0, cacheHits: 0, cacheMisses: 0, failure: null };
  function byteLength(text) {
    let bytes = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c < 128) bytes++; else if (c < 2048) bytes += 2;
      else if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length && text.charCodeAt(i + 1) >= 0xdc00 && text.charCodeAt(i + 1) <= 0xdfff) { bytes += 4; i++; }
      else bytes += 3;
    }
    return bytes;
  }
  function identity(item) {
    return { connection: item.connection, receiveOrdinal: item.receiveOrdinal, event: item.event, epochMillis: item.epochMillis };
  }
  function snapshot() {
    return { ...stats, pending, pendingBytes, waiting: queue.length, inFlight: inFlight ? inFlight.count : 0, inFlightBytes: inFlight ? inFlight.bytes : 0, oldestEpochMillis: inFlight ? inFlight.first.epochMillis : queue.length ? queue[0].epochMillis : 0, closed };
  }
  function fail(reason) {
    if (failed) return;
    failed = true; closing = true;
    if (flushTimer !== null) clearInterval(flushTimer);
    if (timer !== null) clearInterval(timer);
    stats.failure = { reason, batch: inFlight ? { id: inFlight.id, count: inFlight.count, bytes: inFlight.bytes, first: inFlight.first, last: inFlight.last } : null, waiting: queue.length, firstWaiting: queue.length ? identity(queue[0]) : null, lastWaiting: queue.length ? identity(queue[queue.length - 1]) : null };
    stats.unconfirmedBatches = inFlight ? 1 : 0;
    console.error('WS_TRANSPORT_FAILURE ' + JSON.stringify(snapshot()));
    // 이미 수신된 callback이 abort 이후에도 큐를 늘리지 않도록 먼저 입력·timer 종료
    for (const socket of sockets) { try { socket.close(1000); } catch (_) { /* 최초 실패 유지 */ } }
    // 실패 항목·전송 중 수량을 보존. 재전송으로 부분 저장/응답 유실을 숨기지 않음
    try { rpc('/stop', { reason: 'ws_transport_' + reason, wsTransport: snapshot() }); } finally { exec.test.abort('WS evidence ' + reason); }
  }
  function flush() {
    if (failed || inFlight || queue.length === 0) return;
    const id = ++nextBatch, floor = queue[0].callbackFloorMillis;
    const prefix = '{"batchId":' + id + (causal ? ',"callbackFloorMillis":' + floor : '') + ',"events":[', suffix = ']}';
    let bytes = byteLength(prefix) + 2, count = 0, itemsBytes = 0;
    while (count < queue.length && count < limits.batchCount) {
      const next = queue[count].bytes + (count ? 1 : 0);
      if (bytes + next > limits.batchBytes) break;
      bytes += next; itemsBytes += queue[count].bytes; count++;
    }
    if (!count) { fail('single_item_byte_limit'); return; }
    const items = queue.splice(0, count), body = prefix + items.map(v => v.text).join(',') + suffix;
    const started = Date.now();
    inFlight = { id, count, bytes, itemsBytes, first: identity(items[0]), last: identity(items[count - 1]), started };
    stats.maxAgeMillis = Math.max(stats.maxAgeMillis, started - items[0].epochMillis);
    stats.maxBatchCount = Math.max(stats.maxBatchCount, count); stats.maxBatchBytes = Math.max(stats.maxBatchBytes, bytes);
    // 한 VU의 대량 WS callback이 Promise 확인 callback을 굶기지 않도록 같은 FIFO에서 유한 동기 확인
    let response;
    try { response = http.post(input.bridge + '/ws', body, bridgeParams()); }
    catch (_) { fail('request_exception'); return; }
    if (response.status !== 200) { fail(response.status === 0 ? 'timeout_or_transport' : 'non200'); return; }
    let ack;
    try { ack = response.json(); } catch (_) { fail('malformed200'); return; }
    if (!ack || ack.saved !== true || ack.batchId !== id || ack.count !== count || typeof ack.stop !== 'boolean' || typeof ack.closing !== 'boolean') { fail('storage_ack_mismatch'); return; }
    if (causal) {
      if (!Number.isInteger(ack.clockFloorMillis) || ack.clockFloorMillis < callbackFloorMillis || ack.clockFloorMillis >= 21600000) { fail('causal_ack_mismatch'); return; }
      callbackFloorMillis = ack.clockFloorMillis;
    }
    const elapsed = Date.now() - started;
    stats.maxBatchMillis = Math.max(stats.maxBatchMillis, elapsed); stats.totalBatchMillis += elapsed;
    stats.batches++; stats.acknowledged += count; pending -= count; pendingBytes -= itemsBytes; inFlight = null;
    if (ack.stop) { fail('controller_stop'); return; }
    if (ack.closing && !closing) { closing = true; for (const socket of sockets) socket.close(1000); }
    if (queue.length) flush();
  }

  function event(data) {
    if (failed) { stats.afterFailureCallbacks++; return; }
    // callback 시각·연결별 순서를 직렬화/대기보다 먼저 고정
    data.callbackFloorMillis = callbackFloorMillis;
    data.receiveOrdinal = sequences[data.connection] = (sequences[data.connection] || 0) + 1;
    let text, bytes;
    if (data.event === 'message') {
      let body = bodyCache.get(data.body);
      if (!body) {
        stats.cacheMisses++;
        const quoted = JSON.stringify(data.body);
        body = { text: quoted, bytes: byteLength(quoted) };
        // 원문·escaped UTF-16 사본과 UTF-8 크기를 보수 계상, 실패 시 보존된 12651-event 전체 구간까지 포함하는 유한 16384개/16MiB
        body.retained = 2 * (data.body.length + quoted.length) + body.bytes + 128;
        while (bodyCache.size >= limits.cacheCount || cacheBytes + body.retained > limits.cacheBytes) {
          const key = bodyCache.keys().next().value;
          cacheBytes -= bodyCache.get(key).retained; bodyCache.delete(key);
        }
        bodyCache.set(data.body, body); cacheBytes += body.retained;
        stats.maxCacheBytes = Math.max(stats.maxCacheBytes, cacheBytes);
        stats.maxCacheEntries = Math.max(stats.maxCacheEntries, bodyCache.size);
      } else stats.cacheHits++;
      const prefix = '{"event":"message","connection":' + data.connection + ',"epochMillis":' + data.epochMillis + ',"receiveOrdinal":' + data.receiveOrdinal + ',"body":';
      text = prefix + body.text + '}'; bytes = prefix.length + body.bytes + 1;
    } else { text = JSON.stringify(data); bytes = byteLength(text); }
    // callback 항목 자체를 유한 FIFO에 보유. 식별 요약 객체는 batch 경계·실패 때만 만든다.
    // callback 시각/ordinal은 그대로 두고 직렬화가 끝난 원문 참조만 해제한다.
    data.text = text; data.bytes = bytes; data.body = null;
    queue.push(data);
    pending++; pendingBytes += bytes; stats.received++;
    stats.maxPending = Math.max(stats.maxPending, pending); stats.maxPendingBytes = Math.max(stats.maxPendingBytes, pendingBytes);
    if (pending > limits.pendingCount || pendingBytes > limits.pendingBytes) { fail('queue_limit'); return; }
    if (queue.length >= limits.batchCount || pendingBytes >= limits.batchBytes - 64 || data.event !== 'message') flush();
  }
  function stateWithClock() {
    const before = Date.now(), state = rpc('/state'), after = Date.now();
    rpc('/clock-sample', { before, after, bridge: state.epochMillis, wsTransport: snapshot() });
    return state;
  }
  stateWithClock();
  let lastClock = Date.now();
  flushTimer = setInterval(flush, 5);
  for (let connection = 0; connection < w.wsConnections; connection++) {
    const socket = new WebSocket(input.target.replace('http:', 'ws:') + '/ws', [], { headers: { Origin: 'http://localhost:3000' }, tags: { role: 'target', phase: 'ws' } });
    socket.onmessage = message => {
      const at = Date.now();
      if (failed) { stats.afterFailureCallbacks++; return; }
      if (typeof message.data !== 'string' || message.data.length > 4096) { fail('payload_bound'); return; }
      event({ event: 'message', connection, epochMillis: at, body: message.data });
    };
    socket.onopen = () => event({ event: 'open', connection, epochMillis: Date.now() });
    socket.onclose = e => { const at = Date.now(); closed++; event({ event: 'close', connection, epochMillis: at, code: e.code, reason: e.reason || '' }); };
    socket.onerror = () => event({ event: 'error', connection, epochMillis: Date.now() });
    sockets.push(socket);
  }
  timer = setInterval(() => {
    if (failed) return;
    const sample = Date.now() - lastClock >= 1000;
    const state = sample ? stateWithClock() : rpc('/state');
    if (sample) lastClock = Date.now();
    if (!closing && (state.closing || state.stop || Date.now() >= input.expiresEpochMillis)) {
      closing = true; for (const socket of sockets) socket.close(1000);
    }
    if (closing && closed === sockets.length && pending === 0 && pendingBytes === 0 && !inFlight) {
      rpc('/ws-transport-complete', snapshot()); clearInterval(timer); clearInterval(flushTimer);
    }
  }, 100);
}

export function handleSummary(data) {
  return { [__ENV.PHASE18_K6_SUMMARY]: JSON.stringify({ schema: 'phase18-k6-summary-v1', phase, k6Version: '2.3.0', data }) + '\n' };
}
