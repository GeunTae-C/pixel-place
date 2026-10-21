// 브라우저 WS·시계를 합성해 유한 재시도와 페이지/연결 소유권 검증. 실제 서버 수신 증거와 구분
import test from "node:test";
import assert from "node:assert/strict";
import { RealtimeConnection, CONNECTION_TIMEOUT_MS, RETRY_DELAYS_MS, STABLE_CONNECTION_MS } from "../realtime.mjs";

class Clock {
  constructor() { this.time = 0; this.nextId = 0; this.pending = new Map(); this.history = new Map(); }
  set = (callback, delay) => {
    const id = ++this.nextId;
    const timer = { callback, due: this.time + delay, delay };
    this.pending.set(id, timer);
    this.history.set(id, timer);
    return id;
  };
  clear = (id) => this.pending.delete(id);
  now = () => this.time;
  advance(duration) {
    const end = this.time + duration;
    while (true) {
      const next = [...this.pending].filter(([, timer]) => timer.due <= end).sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) break;
      const [id, timer] = next;
      this.time = timer.due;
      this.pending.delete(id);
      timer.callback();
    }
    this.time = end;
  }
  fireLate(id) { this.history.get(id).callback(); }
}

class Socket {
  constructor(url) { this.url = url; this.listeners = new Map(); this.saved = new Map(); this.closeCount = 0; }
  addEventListener(type, listener) { this.listeners.set(type, listener); this.saved.set(type, listener); }
  removeEventListener(type, listener) {
    if (this.listeners.get(type) === listener) this.listeners.delete(type);
  }
  emit(type, data) { this.listeners.get(type)?.({ data }); }
  late(type, data) { this.saved.get(type)?.({ data }); }
  close() { this.closeCount += 1; }
}

function fixture(options = {}) {
  const clock = new Clock();
  const sockets = [];
  const opened = [];
  const disconnected = [];
  const messages = [];
  const states = [];
  const realtime = new RealtimeConnection({ url: "ws://localhost:8080/ws", setTimer: clock.set,
    clearTimer: clock.clear, now: clock.now,
    createSocket: (url) => { const socket = new Socket(url); sockets.push(socket); return socket; },
    onOpen: (event) => opened.push(event), onDisconnect: (event) => disconnected.push(event),
    onMessage: (...args) => messages.push(args), onChange: (state) => states.push(state), ...options });
  return { realtime, clock, sockets, opened, disconnected, messages, states };
}

test("기본 브라우저 생성자는 공개 URL 하나만 받고 open 이후 초기 조회 콜백 호출", (t) => {
  const original = globalThis.WebSocket;
  const calls = [];
  globalThis.WebSocket = class extends Socket {
    constructor(...args) { super(args[0]); calls.push(args); }
  };
  const f = fixture({ createSocket: undefined });
  t.after(() => { f.realtime.suspend(); globalThis.WebSocket = original; });
  f.realtime.start();
  assert.deepEqual(calls, [["ws://localhost:8080/ws"]]);
  assert.equal(f.opened.length, 0);
  assert.equal(f.realtime.getState().status, "connecting");
  assert.equal(f.clock.pending.size, 1);
  const socket = f.realtime.current.socket;
  assert.deepEqual([...socket.listeners.keys()], ["open", "message", "error", "close"]);
  socket.emit("open");
  assert.deepEqual(f.opened, [{ epoch: 1 }]);
  assert.equal(f.realtime.getState().status, "connected");
  assert.equal(f.realtime.getState().synchronized, false);
});

test("정상 메시지 원문과 연결 epoch 전달, 정상 이벤트마다 연결이나 타이머 추가 없음", () => {
  const f = fixture();
  f.realtime.start();
  f.sockets[0].emit("open");
  const raw = '{"type":"pixel","eventSeq":9223372036854775807}';
  f.sockets[0].emit("message", raw);
  f.sockets[0].emit("message", raw);
  assert.deepEqual(f.messages, [[raw, { epoch: 1 }], [raw, { epoch: 1 }]]);
  assert.equal(f.sockets.length, 1);
  assert.equal(f.clock.pending.size, 1);
  f.realtime.suspend();
});

test("error·close·timeout 중복 통지는 최초 실패 하나와 재시도 하나만 유지", () => {
  const f = fixture();
  f.realtime.start();
  const socket = f.sockets[0];
  const deadline = [...f.clock.pending.keys()][0];
  socket.emit("error");
  socket.late("close");
  f.clock.fireLate(deadline);
  assert.equal(socket.closeCount, 1);
  assert.equal(socket.listeners.size, 0);
  assert.equal(f.disconnected.length, 1);
  assert.equal(f.disconnected[0].reason, "error");
  assert.equal(f.clock.pending.size, 1);
  assert.equal(f.realtime.getState().retries, 1);
  assert.equal(f.realtime.getState().failureSeen, true);
  f.clock.advance(999);
  assert.equal(f.sockets.length, 1);
  f.clock.advance(1);
  assert.equal(f.sockets.length, 2);
  assert.equal(f.realtime.getState().status, "connecting");
  f.realtime.suspend();
});

test("각 연결 시도는 15초 제한을 적용하고 최초 뒤 1·2·4·8·15초 다섯 번만 재시도", () => {
  const f = fixture();
  f.realtime.start();
  for (let index = 0; index < RETRY_DELAYS_MS.length; index += 1) {
    f.clock.advance(CONNECTION_TIMEOUT_MS - 1);
    assert.equal(f.realtime.getState().status, "connecting");
    f.clock.advance(1);
    assert.equal(f.realtime.getState().status, "retrying");
    assert.equal(f.clock.pending.size, 1);
    f.clock.advance(RETRY_DELAYS_MS[index]);
    assert.equal(f.sockets.length, index + 2);
  }
  f.clock.advance(CONNECTION_TIMEOUT_MS);
  assert.equal(f.realtime.getState().status, "exhausted");
  assert.equal(f.realtime.getState().retries, 5);
  assert.equal(f.realtime.getState().attempt, 6);
  assert.equal(f.clock.pending.size, 0);
  f.clock.advance(1000000);
  assert.equal(f.sockets.length, 6);
  assert.ok(f.sockets.every((socket) => socket.closeCount === 1));
});

test("socket 생성 예외도 별도 무한 루프 없이 동일 예산으로 소진", () => {
  let creations = 0;
  const f = fixture({ createSocket: () => { creations += 1; throw new Error("synthetic creation failure"); } });
  f.realtime.start();
  for (const delay of RETRY_DELAYS_MS) f.clock.advance(delay);
  assert.equal(creations, 6);
  assert.equal(f.disconnected.length, 6);
  assert.ok(f.disconnected.every((event) => event.reason === "creation"));
  assert.equal(f.realtime.getState().status, "exhausted");
  assert.equal(f.clock.pending.size, 0);
});

test("짧은 open과 조회 완료의 반복으로 재시도 예산을 초기화하지 않음", () => {
  const f = fixture();
  f.realtime.start();
  for (let index = 0; index <= RETRY_DELAYS_MS.length; index += 1) {
    const socket = f.sockets.at(-1);
    socket.emit("open");
    f.realtime.setSynchronized(true);
    f.clock.advance(STABLE_CONNECTION_MS - 1);
    socket.emit("close");
    if (index < RETRY_DELAYS_MS.length) f.clock.advance(RETRY_DELAYS_MS[index]);
  }
  assert.equal(f.realtime.getState().status, "exhausted");
  assert.equal(f.sockets.length, 6);
  assert.equal(f.clock.pending.size, 0);
});

test("30초 유지와 현재 영역 완료를 모두 충족해야 예산 초기화", () => {
  const f = fixture();
  f.realtime.start();
  f.sockets[0].emit("error");
  f.clock.advance(1000);
  f.sockets[1].emit("open");
  f.realtime.setSynchronized(true);
  f.clock.advance(STABLE_CONNECTION_MS - 1);
  assert.equal(f.realtime.getState().retries, 1);
  f.clock.advance(1);
  assert.equal(f.realtime.getState().retries, 0);
  f.sockets[1].emit("close");
  assert.equal(f.realtime.getState().retries, 1);
  assert.equal([...f.clock.pending.values()][0].delay, 1000);
  f.realtime.suspend();
});

test("30초 이후 GET 실패 상태는 소켓을 유지하고 나중 영역 완료 시에만 예산 초기화", () => {
  const f = fixture();
  f.realtime.start();
  f.sockets[0].emit("error");
  f.clock.advance(1000);
  f.sockets[1].emit("open");
  f.realtime.setSynchronized(false);
  f.clock.advance(100000);
  assert.equal(f.realtime.getState().retries, 1);
  assert.equal(f.realtime.getState().status, "connected");
  assert.equal(f.sockets.length, 2);
  assert.equal(f.sockets[1].closeCount, 0);
  assert.equal(f.clock.pending.size, 0);
  f.realtime.setSynchronized(true);
  assert.equal(f.realtime.getState().retries, 0);
  f.realtime.suspend();
});

test("안정 시간 직전 새 영역 로딩으로 바뀌면 과거 영역 완료로 예산을 초기화하지 않음", () => {
  const f = fixture();
  f.realtime.start();
  f.sockets[0].emit("error");
  f.clock.advance(1000);
  f.sockets[1].emit("open");
  f.realtime.setSynchronized(true);
  f.clock.advance(20000);
  f.realtime.setSynchronized(false);
  f.clock.advance(10000);
  assert.equal(f.realtime.getState().retries, 1);
  f.sockets[1].emit("close");
  assert.equal(f.realtime.getState().retries, 2);
  assert.equal([...f.clock.pending.values()][0].delay, 2000);
  f.realtime.suspend();
});

test("검증 callback 예외는 protocol 오류로 닫고 같은 유한 재시도 예산 소비", () => {
  const f = fixture({ onMessage: () => { throw new TypeError("synthetic invalid wire"); } });
  f.realtime.start();
  for (let index = 0; index <= RETRY_DELAYS_MS.length; index += 1) {
    f.sockets.at(-1).emit("open");
    assert.equal(f.realtime.getState().protocolError, false);
    f.sockets.at(-1).emit("message", "invalid");
    assert.equal(f.realtime.getState().protocolError, true);
    assert.equal(f.disconnected.at(-1).reason, "protocol");
    assert.equal(f.disconnected.at(-1).wasConnected, true);
    if (index < RETRY_DELAYS_MS.length) f.clock.advance(RETRY_DELAYS_MS[index]);
  }
  assert.equal(f.realtime.getState().status, "exhausted");
  assert.equal(f.clock.pending.size, 0);
});

test("명시적 wire 거부도 현재 열린 연결에 한 번만 적용", () => {
  const f = fixture();
  f.realtime.start();
  f.realtime.rejectMessage();
  assert.equal(f.realtime.getState().status, "connecting");
  f.sockets[0].emit("open");
  f.realtime.rejectMessage();
  f.realtime.rejectMessage();
  assert.equal(f.disconnected.length, 1);
  assert.equal(f.realtime.getState().protocolError, true);
  assert.equal(f.clock.pending.size, 1);
  f.realtime.suspend();
});

test("이전 소켓의 지연 open·message·error·close와 deadline은 새 연결에 영향 없음", () => {
  const f = fixture();
  f.realtime.start();
  const stale = f.sockets[0];
  const deadline = [...f.clock.pending.keys()][0];
  stale.emit("close");
  f.clock.advance(1000);
  f.sockets[1].emit("open");
  const before = f.realtime.getState();
  stale.late("open");
  stale.late("message", "invalid but obsolete");
  stale.late("error");
  stale.late("close");
  f.clock.fireLate(deadline);
  assert.deepEqual(f.realtime.getState(), before);
  assert.equal(f.opened.length, 1);
  assert.equal(f.messages.length, 0);
  assert.equal(f.disconnected.length, 1);
  assert.equal(f.sockets[1].closeCount, 0);
  f.realtime.suspend();
});

test("연속 start·retry·resume은 연결 중과 연결 정상 상태에서 자원 누적 없음", () => {
  const f = fixture();
  f.realtime.start();
  for (let index = 0; index < 5; index += 1) {
    f.realtime.start(); f.realtime.retry(); f.realtime.resume();
  }
  assert.equal(f.sockets.length, 1);
  assert.equal(f.clock.pending.size, 1);
  f.sockets[0].emit("open");
  for (let index = 0; index < 5; index += 1) f.realtime.retry();
  assert.equal(f.sockets.length, 1);
  assert.equal(f.opened.length, 1);
  f.realtime.suspend();
});

test("사용자 재시도는 기존 예약을 취소하고 새 시도를 하나만 시작", () => {
  const f = fixture();
  f.realtime.start();
  f.sockets[0].emit("error");
  const reservation = [...f.clock.pending.keys()][0];
  f.realtime.retry();
  f.realtime.retry();
  f.clock.fireLate(reservation);
  assert.equal(f.sockets.length, 2);
  assert.equal(f.realtime.getState().retries, 0);
  assert.equal(f.realtime.getState().failureSeen, true);
  assert.equal(f.clock.pending.size, 1);
  f.realtime.suspend();
});

test("소진 후 명시적 재시도로 새 예산 시작", () => {
  const f = fixture();
  f.realtime.start();
  for (const delay of RETRY_DELAYS_MS) {
    f.sockets.at(-1).emit("close");
    f.clock.advance(delay);
  }
  f.sockets.at(-1).emit("close");
  assert.equal(f.realtime.getState().status, "exhausted");
  f.realtime.retry();
  assert.equal(f.sockets.length, 7);
  assert.equal(f.realtime.getState().status, "connecting");
  assert.equal(f.realtime.getState().retries, 0);
  f.sockets.at(-1).emit("error");
  assert.equal([...f.clock.pending.values()][0].delay, 1000);
  f.realtime.suspend();
});

test("pagehide는 연결·기한·재시도·안정 타이머를 정리하고 pageshow 중복은 새 연결 하나", () => {
  for (const phase of ["connecting", "connected", "retrying", "exhausted"]) {
    const f = fixture();
    f.realtime.start();
    if (phase === "connected") f.sockets[0].emit("open");
    if (phase === "retrying") f.sockets[0].emit("error");
    if (phase === "exhausted") {
      for (const delay of RETRY_DELAYS_MS) {
        f.sockets.at(-1).emit("error");
        f.clock.advance(delay);
      }
      f.sockets.at(-1).emit("error");
    }
    const staleTimers = [...f.clock.pending.keys()];
    const staleSockets = [...f.sockets];
    f.realtime.suspend();
    f.realtime.suspend();
    f.realtime.retry();
    f.realtime.setSynchronized(true);
    assert.equal(f.clock.pending.size, 0, phase);
    assert.equal(f.realtime.getState().status, "suspended", phase);
    f.realtime.resume();
    f.realtime.resume();
    for (const timer of staleTimers) f.clock.fireLate(timer);
    for (const socket of staleSockets) {
      socket.late("open"); socket.late("message", "invalid"); socket.late("error"); socket.late("close");
    }
    assert.equal(f.sockets.length, staleSockets.length + 1, phase);
    assert.equal(f.realtime.getState().status, "connecting", phase);
    assert.equal(f.realtime.getState().retries, 0, phase);
    assert.equal(f.realtime.getState().failureSeen, false, phase);
    assert.equal(f.clock.pending.size, 1, phase);
    f.realtime.suspend();
    assert.equal(f.clock.pending.size, 0, phase);
  }
});

test("연결 상태 알림 도중 pagehide가 오면 socket을 뒤늦게 만들지 않음", () => {
  let f;
  f = fixture({ onChange: (state) => { if (state.status === "connecting") f.realtime.suspend(); } });
  f.realtime.start();
  assert.equal(f.sockets.length, 0);
  assert.equal(f.clock.pending.size, 0);
  assert.equal(f.realtime.getState().status, "suspended");
});
