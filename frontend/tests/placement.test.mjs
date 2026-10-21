// 실제 전송 횟수·고정 payload·인증 수명·타이머 결과를 합성 transport로 검증. 실제 서버 수락 증거와 분리
import test from "node:test";
import assert from "node:assert/strict";
import { AuthSession, AUTH_STORAGE_KEYS } from "../auth-session.mjs";
import { PlacementController, parseAcceptedPlacement, PLACEMENT_REQUEST_TIMEOUT_MS, PLACEMENT_COOLDOWN_MS } from "../placement.mjs";
import { TileLoader } from "../tile-loader.mjs";

const START = 1000000;
const METADATA = { boardSize: 8, tileSize: 4, tileCountX: 2, tileCountY: 2,
  paletteSize: 3, palette: ["#ffffff", "#000000", "#ff0000"], overviewRefreshSeconds: 5, z: 0 };
const PAYLOAD = { x: 1, y: 2, color: 1 };
const settle = () => new Promise((resolve) => setImmediate(resolve));
const acceptedRaw = (overrides = {}) => JSON.stringify({ accepted: true, ...PAYLOAD, eventSeq: 11, tileVersion: 12, ...overrides });
const response = (status = 200, raw = acceptedRaw()) => ({ status, text: async () => raw });

function setup(t, { signedIn = true, context = {}, onAccepted, getViewGeneration } = {}) {
  let time = START;
  let nextTimerId = 0;
  const timers = new Map();
  const values = new Map();
  const calls = [];
  const changes = [];
  const states = [];
  const storage = { getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) };
  const install = (token = "synthetic-placement-access") => storage.setItem(AUTH_STORAGE_KEYS.session,
    JSON.stringify({ accessToken: token, expiresAt: time + 600000 }));
  if (signedIn) install();
  const now = () => time;
  const setTimer = (callback, delay) => { const id = ++nextTimerId; timers.set(id, { callback, at: time + delay }); return id; };
  const clearTimer = (id) => timers.delete(id);
  let placement;
  const auth = new AuthSession({ getStorage: () => storage, now, setTimer, clearTimer,
    onChange: () => placement?.reconcileAuth(), navigate: () => {} });
  auth.reconcile();
  placement = new PlacementController({ auth, now, setTimer, clearTimer, getViewGeneration,
    fetchImpl: (url, options) => new Promise((resolve, reject) => calls.push({ url, options, resolve, reject })),
    onAccepted: onAccepted ?? ((change) => changes.push(change)), onChange: (state) => states.push(state) });
  placement.setContext({ metadata: METADATA, selectedPixel: { x: 1, y: 2 }, selectedColor: 1, ...context });
  t.after(() => { placement.suspend(); auth.suspend(); });
  return { placement, auth, calls, changes, states, storage, values, install, timers,
    jump: (ms) => { time += ms; },
    advance(ms) {
      time += ms;
      for (const [id, timer] of [...timers]) if (timer.at <= time) { timers.delete(id); timer.callback(); }
    } };
}

test("유효한 선택과 인증에서만 Bearer·JSON·credentials omit으로 한 요청을 보내고 선택 변경은 payload에 영향 없음", async (t) => {
  const f = setup(t);
  assert.equal(f.placement.getState().canPlace, true);
  const pending = f.placement.submit();
  assert.equal(f.placement.getState().pending, true);
  assert.equal(f.placement.getState().canPlace, false);
  assert.equal(await f.placement.submit(), false);
  assert.equal(f.calls.length, 1);
  const call = f.calls[0];
  assert.equal(call.url, "http://localhost:8080/api/pixels");
  assert.equal(call.options.method, "POST");
  assert.equal(call.options.credentials, "omit");
  assert.deepEqual(call.options.headers, { "Content-Type": "application/json", Authorization: "Bearer synthetic-placement-access" });
  assert.deepEqual(JSON.parse(call.options.body), PAYLOAD);
  assert.equal(Object.hasOwn(call.options.headers, "X-User-Id"), false);
  f.placement.setContext({ metadata: METADATA, selectedPixel: { x: 6, y: 7 }, selectedColor: 2 });
  assert.equal(f.changes.length, 0);
  call.resolve(response());
  assert.equal(await pending, true);
  assert.deepEqual(f.changes, [{ ...PAYLOAD, eventSeq: 11n, tileVersion: 12n }]);
  assert.deepEqual(f.placement.selectedPixel, { x: 6, y: 7 });
  assert.equal(f.placement.selectedColor, 2);
  assert.equal(f.placement.getState().status, "accepted");
  assert.equal(f.placement.getState().pending, false);
  assert.equal(f.placement.getState().remainingMillis, PLACEMENT_COOLDOWN_MS);
  assert.equal(await f.placement.submit(), false);
  assert.equal(f.calls.length, 1);
});

test("POST 중 연결 세대가 바뀌어도 수락·180초 유지하고 과거 변경 대신 해당 타일의 수락 이후 GET만 수행", async (t) => {
  const gets = [];
  const acceptedGenerations = [];
  const loader = new TileLoader({ metadata: METADATA,
    fetchImpl: (url, options) => new Promise((resolve) => gets.push({ url, options, resolve })) });
  t.after(() => loader.dispose());
  const tileResponse = (version, color = 0) => ({ ok: true, status: 200,
    headers: { get: () => version }, arrayBuffer: async () => new Uint8Array(16).fill(color).buffer });
  const visible = [{ tx: 0, ty: 0, key: "0,0" }, { tx: 1, ty: 0, key: "1,0" }];
  loader.reset([visible[0]]);
  gets[0].resolve(tileResponse("10"));
  await settle();
  const f = setup(t, { getViewGeneration: () => loader.getGeneration(),
    onAccepted: (change, generation) => {
      acceptedGenerations.push(generation);
      loader.applyAccepted(change, generation);
    } });
  const submittedGeneration = loader.getGeneration();
  const pending = f.placement.submit();
  assert.equal(f.calls.length, 1);
  assert.equal(f.placement.request.viewGeneration, submittedGeneration);

  // WS 단절·새 연결은 인증 세션과 진행 중 POST의 소유권을 바꾸지 않음
  loader.invalidate();
  loader.reset(visible);
  assert.equal(f.calls[0].options.signal.aborted, false);
  assert.equal(f.placement.getState().status, "sending");
  assert.equal(gets.length, 3);
  loader.applyChange({ x: 2, y: 2, color: 2, tileVersion: 15n, eventSeq: 20n });
  f.jump(1000);
  f.calls[0].resolve(response());
  assert.equal(await pending, true);
  assert.deepEqual(acceptedGenerations, [submittedGeneration]);
  assert.equal(f.placement.getState().status, "accepted");
  assert.equal(f.placement.getState().remainingMillis, PLACEMENT_COOLDOWN_MS);
  assert.equal(f.placement.getState().cooldownEndsAt, START + 1000 + PLACEMENT_COOLDOWN_MS);
  assert.equal(f.auth.getState().status, "authenticated");
  assert.equal(f.calls.length, 1);
  assert.equal(gets.length, 4);
  assert.equal(gets[1].options.signal.aborted, true);
  assert.equal(gets[2].options.signal.aborted, false);
  assert.equal(gets[3].url.endsWith("/0/0"), true);
  assert.equal(loader.get("0,0").merge.changes.has(PAYLOAD.y * METADATA.tileSize + PAYLOAD.x), false);
  assert.equal(loader.get("0,0").merge.changes.size, 1);

  gets[3].resolve(tileResponse("13", 1));
  gets[2].resolve(tileResponse("10"));
  await settle();
  gets[1].resolve(tileResponse("11"));
  await settle();
  assert.equal(loader.get("0,0").data.bytes[PAYLOAD.y * METADATA.tileSize + PAYLOAD.x], 1);
  assert.equal(loader.get("0,0").data.bytes[2 * METADATA.tileSize + 2], 2);
  assert.equal(loader.get("0,0").merge.snapshot.version, "13");
  assert.equal(loader.getVisibleState().complete, true);
  assert.equal(f.placement.getState().status, "accepted");
  assert.equal(await f.placement.submit(), false);
  assert.equal(f.calls.length, 1);
  assert.equal(gets.length, 4);
});

test("미선택·범위 밖·정수가 아닌 입력과 미인증은 POST 없이 거부", async (t) => {
  const cases = [
    { signedIn: false }, { context: { metadata: null } }, { context: { selectedPixel: null } },
    { context: { selectedColor: null } }, { context: { selectedColor: -1 } }, { context: { selectedColor: 3 } },
    { context: { selectedColor: 1.5 } }, { context: { selectedPixel: { x: -1, y: 2 } } },
    { context: { selectedPixel: { x: 8, y: 2 } } }, { context: { selectedPixel: { x: 1, y: 8 } } },
    { context: { selectedPixel: { x: 1.5, y: 2 } } }, { context: { selectedPixel: { x: "1", y: 2 } } },
  ];
  for (const [index, input] of cases.entries()) await t.test(`invalid selection ${index}`, async (t) => {
    const f = setup(t, input);
    assert.equal(f.placement.getState().canPlace, false);
    assert.equal(await f.placement.submit(), false);
    assert.equal(f.calls.length, 0);
    assert.equal(f.placement.getState().status, "invalid");
  });
});

test("2^53 초과와 Java long 상한을 JSON 원문에서 정확한 BigInt로 보존", () => {
  for (const exact of ["1", "9007199254740993", "9223372036854775807"]) {
    const raw = `{"accepted":true,"x":1,"y":2,"color":1,"eventSeq":${exact},"tileVersion":${exact}}`;
    const change = parseAcceptedPlacement(raw, PAYLOAD, METADATA);
    assert.equal(change.eventSeq, BigInt(exact));
    assert.equal(change.tileVersion, BigInt(exact));
  }
});

test("순서 값 범위·타입·정수 원문·수락 필드·고정 요청 불일치는 성공 설치 금지", async (t) => {
  const invalidRaw = ["0", "-1", "9223372036854775808", "1.1", "1.0000000000000001", "1e3", '"12"', "null", "true"];
  const bodies = invalidRaw.map((value) => `{"accepted":true,"x":1,"y":2,"color":1,"eventSeq":${value},"tileVersion":1}`);
  bodies.push(...[{ accepted: false }, { accepted: "true" }, { x: 2 }, { y: 3 }, { color: 2 },
    { x: null }, { eventSeq: undefined }, { tileVersion: undefined }, { tileVersion: 0 }].map(acceptedRaw));
  bodies.push(acceptedRaw().replace('"x":1', '"x":1.0000000000000001'), "{", "null");
  for (const [index, raw] of bodies.entries()) await t.test(`invalid acceptance ${index}`, async (t) => {
    const f = setup(t);
    const pending = f.placement.submit();
    f.calls[0].resolve(response(200, raw));
    assert.equal(await pending, false);
    assert.equal(f.placement.getState().status, "unknown");
    assert.equal(f.placement.getState().cooldownKnown, false);
    assert.equal(f.changes.length, 0);
    assert.equal(f.calls.length, 1);
  });
});

test("표준 reviver source가 없는 환경에서는 반올림된 Number 성공을 설치하지 않음", () => {
  const original = JSON.parse;
  try {
    JSON.parse = (raw, reviver) => original(raw, (key, value) => reviver(key, value));
    assert.throws(() => parseAcceptedPlacement(acceptedRaw(), PAYLOAD, METADATA));
  } finally { JSON.parse = original; }
});

test("수락 확인 시각부터 예상180초를 시작하고 같은 세션 focus·시간 경과·보존 페이지 복귀는 종료 시각 유지", async (t) => {
  const f = setup(t);
  const pending = f.placement.submit();
  f.jump(3000);
  f.calls[0].resolve(response());
  await pending;
  const end = START + 3000 + PLACEMENT_COOLDOWN_MS;
  assert.equal(f.placement.getState().cooldownEndsAt, end);
  f.jump(61000);
  f.auth.reconcile();
  f.placement.tick();
  assert.equal(f.placement.getState().remainingMillis, 119000);
  assert.equal(f.placement.getState().cooldownEndsAt, end);
  const completed = f.placement.getState();
  f.placement.suspend();
  assert.deepEqual(f.placement.getState(), completed);
  f.auth.suspend();
  f.jump(120000);
  f.auth.resume();
  f.placement.resume();
  assert.equal(f.placement.getState().remainingMillis, 0);
  assert.equal(f.placement.getState().canPlace, true);
  assert.equal(f.placement.getState().cooldownEndsAt, end);
  assert.equal(f.calls.length, 1);
});

test("새 페이지와 새 세션은 남은 시간을 모르며 사용자 요청429로만 타이머 보정", async (t) => {
  const f = setup(t);
  assert.equal(f.placement.getState().cooldownKnown, false);
  const pending = f.placement.submit();
  f.jump(1000);
  f.calls[0].resolve(response(429, '{"remainingMillis":123456}'));
  await pending;
  assert.equal(f.placement.getState().status, "cooldown");
  assert.equal(f.placement.getState().remainingMillis, 123456);
  assert.equal(f.placement.getState().cooldownEndsAt, START + 124456);
  assert.equal(f.placement.getState().canPlace, false);
  assert.equal(f.changes.length, 0);
  f.auth.logout();
  assert.equal(f.placement.getState().status, "idle");
  assert.equal(f.placement.getState().cooldownEndsAt, null);
  f.install("new-synthetic-access");
  f.auth.reconcile();
  assert.equal(f.placement.getState().status, "idle");
  assert.equal(f.placement.getState().cooldownKnown, false);
  assert.equal(f.placement.getState().canPlace, true);
  assert.equal(f.calls.length, 1);
});

test("잘못된429 잔여값·본문 실패도 쿨다운 거절을 유지하며 0초를 만들어내지 않음", async (t) => {
  for (const raw of ['{}', '{"remainingMillis":0}', '{"remainingMillis":-1}', '{"remainingMillis":180001}',
    '{"remainingMillis":1.5}', '{"remainingMillis":1.0000000000000001}', '{"remainingMillis":"500"}', '{"remainingMillis":null}', '{']) {
    await t.test(raw, async (t) => {
      const f = setup(t);
      const pending = f.placement.submit();
      f.calls[0].resolve(response(429, raw));
      await pending;
      assert.equal(f.placement.getState().status, "cooldown");
      assert.equal(f.placement.getState().remainingMillis, null);
      assert.match(f.placement.getState().message, /확인하지 못했습니다/);
      assert.equal(f.changes.length, 0);
      assert.equal(f.calls.length, 1);
    });
  }
});

test("400·401·403·5xx·예상 밖2xx는 구분하고 자동 재전송하지 않음", async (t) => {
  for (const [status, expected] of [[400, "rejected"], [401, "unauthorized"], [403, "forbidden"], [500, "unknown"], [503, "unknown"], [201, "unknown"]]) {
    await t.test(`HTTP ${status}`, async (t) => {
      const f = setup(t);
      const pending = f.placement.submit();
      f.calls[0].resolve(response(status));
      await pending;
      assert.equal(f.placement.getState().status, expected);
      assert.equal(f.placement.getState().pending, false);
      assert.equal(f.placement.getState().remainingMillis, null);
      assert.equal(f.changes.length, 0);
      assert.equal(f.auth.getState().status, status === 401 ? "expired" : "authenticated");
      f.placement.tick();
      f.auth.reconcile();
      assert.equal(f.calls.length, 1);
    });
  }
});

test("통신 예외 원문은 노출하지 않고 미확정 유지·GET과 같은 외부 상태 갱신은 성공으로 바꾸지 않음", async (t) => {
  const f = setup(t);
  const pending = f.placement.submit();
  f.calls[0].reject(new Error("synthetic private server message"));
  await pending;
  assert.equal(f.placement.getState().status, "unknown");
  assert.doesNotMatch(f.placement.getState().message, /synthetic private/);
  f.placement.setContext({ metadata: METADATA, selectedPixel: { x: 1, y: 2 }, selectedColor: 1 });
  assert.equal(f.placement.getState().status, "unknown");
  assert.equal(f.calls.length, 1);
});

test("fetch·늦은본문·동결timer 초과는15초 기한 뒤 수락 설치 금지", async (t) => {
  for (const phase of ["fetch", "body", "frozen-timer"]) await t.test(phase, async (t) => {
    const f = setup(t);
    const pending = f.placement.submit();
    let finishBody;
    if (phase === "body") {
      f.calls[0].resolve({ status: 200, text: () => new Promise((resolve) => { finishBody = resolve; }) });
      await settle();
    }
    if (phase === "frozen-timer") {
      f.jump(PLACEMENT_REQUEST_TIMEOUT_MS);
      f.calls[0].resolve(response());
    } else f.advance(PLACEMENT_REQUEST_TIMEOUT_MS);
    await pending;
    assert.equal(f.placement.getState().status, "unknown");
    assert.equal(f.placement.getState().pending, false);
    if (phase === "body") finishBody(acceptedRaw());
    else if (phase === "fetch") f.calls[0].resolve(response());
    await settle();
    assert.equal(f.changes.length, 0);
    assert.equal(f.placement.getState().remainingMillis, null);
    assert.equal(f.calls.length, 1);
  });
});

test("읽은429의 본문 기한 초과는 거절을 보존하며 확인 불가로 안내", async (t) => {
  const f = setup(t);
  const pending = f.placement.submit();
  f.calls[0].resolve({ status: 429, text: () => new Promise(() => {}) });
  await settle();
  f.advance(PLACEMENT_REQUEST_TIMEOUT_MS);
  await pending;
  assert.equal(f.placement.getState().status, "cooldown");
  assert.equal(f.placement.getState().remainingMillis, null);
});

test("읽은429의 지연 본문 중 pagehide는 거절을 보존하고 복귀 뒤 새 요청·타이머를 보호", async (t) => {
  const f = setup(t);
  const first = f.placement.submit();
  f.calls[0].resolve(response(429, '{"remainingMillis":1000}'));
  await first;
  const completed = f.placement.getState();
  f.placement.suspend(); f.auth.suspend();
  assert.deepEqual(f.placement.getState(), completed);
  f.jump(1000);
  f.auth.resume(); f.placement.resume();
  assert.equal(f.placement.getState().status, completed.status);
  assert.equal(f.placement.getState().message, completed.message);
  assert.equal(f.placement.getState().cooldownEndsAt, completed.cooldownEndsAt);
  assert.equal(f.placement.getState().remainingMillis, 0);
  assert.equal(f.calls.length, 1);
  const sessionId = f.auth.getSessionId();
  const oldPending = f.placement.submit();
  let finishBody;
  f.calls[1].resolve({ status: 429, text: () => new Promise((resolve) => { finishBody = resolve; }) });
  await settle();
  assert.equal(typeof finishBody, "function");
  assert.equal(f.placement.getState().pending, true);
  f.placement.suspend(); f.auth.suspend();
  assert.equal(await oldPending, false);
  assert.equal(f.calls[1].options.signal.aborted, true);
  const rejected = f.placement.getState();
  assert.equal(rejected.status, "cooldown");
  assert.equal(rejected.cooldownKnown, false);
  assert.equal(rejected.cooldownEndsAt, null);
  assert.equal(rejected.remainingMillis, null);
  assert.equal(rejected.pending, false);
  assert.match(rejected.message, /쿨다운으로 배치가 거절되었습니다.*남은 대기 시간은 확인하지 못했습니다/);
  assert.equal(f.timers.size, 0);
  f.auth.resume(); f.placement.resume();
  f.placement.tick();
  assert.equal(f.auth.getSessionId(), sessionId);
  assert.deepEqual(f.placement.getState(), { ...rejected, canPlace: true });
  assert.equal(f.timers.size, 1); // 복귀한 인증 만료 타이머만 보유
  assert.equal(f.calls.length, 2);
  assert.equal(f.changes.length, 0);

  const newPending = f.placement.submit();
  const current = f.placement.getState();
  const authState = f.auth.getState();
  const timers = [...f.timers];
  finishBody('{"remainingMillis":180000}');
  await settle();
  assert.deepEqual(f.placement.getState(), current);
  assert.equal(current.status, "sending");
  assert.equal(current.pending, true);
  assert.deepEqual(f.auth.getState(), authState);
  assert.deepEqual([...f.timers], timers);
  assert.equal(f.calls[2].options.signal.aborted, false);
  assert.equal(f.changes.length, 0);
  f.calls[2].resolve(response(429, '{"remainingMillis":99999}'));
  await newPending;
  assert.equal(f.placement.getState().remainingMillis, 99999);
  assert.equal(f.timers.size, 1);
  assert.equal(f.calls.length, 3); // 세 번 모두 명시적 submit, 복귀·늦은 본문의 추가 POST 없음
  assert.equal(f.changes.length, 0);
});

test("읽은429의 지연 본문 중 인증 변경은 거절을 보존하고 이전 세션 쿨다운을 비움", async (t) => {
  for (const action of ["logout", "session-change"]) await t.test(action, async (t) => {
    const f = setup(t);
    const first = f.placement.submit();
    f.calls[0].resolve(response(429, '{"remainingMillis":1000}'));
    await first;
    f.jump(1000);
    const oldSessionId = f.auth.getSessionId();
    const oldPending = f.placement.submit();
    let finishBody;
    f.calls[1].resolve({ status: 429, text: () => new Promise((resolve) => { finishBody = resolve; }) });
    await settle();
    assert.equal(typeof finishBody, "function");
    if (action === "logout") f.auth.logout();
    else { f.install("new-synthetic-access"); f.auth.reconcile(); }
    assert.equal(await oldPending, false);
    assert.equal(f.calls[1].options.signal.aborted, true);
    const rejected = f.placement.getState();
    assert.equal(rejected.status, "cooldown");
    assert.equal(rejected.cooldownKnown, false);
    assert.equal(rejected.cooldownEndsAt, null);
    assert.equal(rejected.remainingMillis, null);
    assert.equal(rejected.pending, false);
    assert.match(rejected.message, /쿨다운으로 배치가 거절되었습니다.*남은 대기 시간은 확인하지 못했습니다/);
    assert.equal(f.timers.size, action === "logout" ? 0 : 1);
    if (action === "logout") {
      assert.equal(f.auth.getState().status, "signedOut");
      f.install("new-synthetic-access"); f.auth.reconcile();
      assert.equal(f.placement.getState().status, "idle");
    }
    assert.notEqual(f.auth.getSessionId(), oldSessionId);
    assert.equal(f.auth.getState().status, "authenticated");
    assert.equal(f.placement.getState().cooldownEndsAt, null);
    assert.equal(f.placement.getState().canPlace, true);
    assert.equal(f.calls.length, 2);
    assert.equal(f.changes.length, 0);

    const newPending = f.placement.submit();
    f.calls[2].resolve(response(429, '{"remainingMillis":54321}'));
    await newPending;
    const current = f.placement.getState();
    const authState = f.auth.getState();
    const sessionId = f.auth.getSessionId();
    const timers = [...f.timers];
    finishBody('{"remainingMillis":180000}');
    await settle();
    assert.deepEqual(f.placement.getState(), current);
    assert.equal(current.status, "cooldown");
    assert.equal(current.remainingMillis, 54321);
    assert.deepEqual(f.auth.getState(), authState);
    assert.equal(f.auth.getSessionId(), sessionId);
    assert.deepEqual([...f.timers], timers);
    assert.equal(f.calls.length, 3);
    assert.equal(f.changes.length, 0);
  });
});

test("같은 세션 focus 재검사는 진행 중 요청을 취소하거나 중복 전송하지 않음", async (t) => {
  const f = setup(t);
  const pending = f.placement.submit();
  f.auth.reconcile();
  assert.equal(f.placement.getState().pending, true);
  assert.equal(f.calls[0].options.signal.aborted, false);
  f.calls[0].resolve(response());
  assert.equal(await pending, true);
  assert.equal(f.changes.length, 1);
  assert.equal(f.calls.length, 1);
});

test("logout·새 로그인·pagehide 뒤 늦은200·401·429는 현재 세션·안내·쿨다운을 덮지 않음", async (t) => {
  for (const action of ["logout", "new-login", "pagehide"]) {
    for (const status of [200, 401, 429]) await t.test(`${action} late ${status}`, async (t) => {
      const f = setup(t);
      const pending = f.placement.submit();
      if (action === "pagehide") { f.placement.suspend(); f.auth.suspend(); }
      else f.auth.logout();
      await pending;
      assert.equal(f.placement.getState().status, "unknown");
      assert.equal(f.calls[0].options.signal.aborted, true);
      if (action === "new-login") { f.install("new-synthetic-access"); f.auth.reconcile(); }
      if (action === "pagehide") { f.auth.resume(); f.placement.resume(); }
      const current = f.placement.getState();
      f.calls[0].resolve(response(status, status === 429 ? '{"remainingMillis":100000}' : acceptedRaw()));
      await settle();
      assert.deepEqual(f.placement.getState(), current);
      assert.equal(f.changes.length, 0);
      assert.equal(f.auth.getState().status, action === "logout" ? "signedOut" : "authenticated");
      assert.equal(f.calls.length, 1);
    });
  }
});

test("페이지 복귀의 새 요청 뒤 이전 지연 본문이 도착해도 새 요청 소유권 유지", async (t) => {
  const f = setup(t);
  const oldPending = f.placement.submit();
  let finishBody;
  f.calls[0].resolve({ status: 200, text: () => new Promise((resolve) => { finishBody = resolve; }) });
  await settle();
  f.placement.suspend(); f.auth.suspend();
  await oldPending;
  assert.equal(f.placement.getState().status, "unknown");
  f.auth.resume(); f.placement.resume();
  const newPending = f.placement.submit();
  finishBody(acceptedRaw());
  await settle();
  assert.equal(f.placement.getState().status, "sending");
  assert.equal(f.placement.getState().pending, true);
  assert.equal(f.changes.length, 0);
  f.calls[1].resolve(response(429, '{"remainingMillis":99999}'));
  await newPending;
  assert.equal(f.placement.getState().remainingMillis, 99999);
  assert.equal(f.calls.length, 2);
});

test("만료 타이머가 늦어도 응답 소유권 확인에서 만료 인증의 수락 설치 차단", async (t) => {
  const f = setup(t);
  f.storage.setItem(AUTH_STORAGE_KEYS.session, JSON.stringify({ accessToken: "synthetic-placement-access", expiresAt: START + 1000 }));
  f.auth.reconcile();
  const pending = f.placement.submit();
  f.jump(1001);
  f.calls[0].resolve(response());
  await pending;
  assert.equal(f.auth.getState().status, "expired");
  assert.equal(f.changes.length, 0);
  assert.equal(f.placement.getState().status, "unknown");
  assert.equal(f.placement.getState().remainingMillis, null);
});

test("확인한 서버 수락은 화면 병합 예외로 미확정으로 되돌리지 않고 GET 확인 안내", async (t) => {
  const f = setup(t, { onAccepted: () => { throw new Error("synthetic rendering failure"); } });
  const pending = f.placement.submit();
  f.calls[0].resolve(response());
  assert.equal(await pending, true);
  assert.equal(f.placement.getState().status, "accepted");
  assert.equal(f.placement.getState().remainingMillis, PLACEMENT_COOLDOWN_MS);
  assert.match(f.placement.getState().message, /다시 불러와/);
  assert.equal(f.calls.length, 1);
});
