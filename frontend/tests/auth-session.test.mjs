// 합성 storage·시계·transport로 인증 경계 검증. 실제 카카오·브라우저 BFCache 성공 증거와 분리
import test from "node:test";
import assert from "node:assert/strict";
import { AuthSession, AUTH_STORAGE_KEYS, AUTH_REQUEST_TIMEOUT_MS } from "../auth-session.mjs";

const START = 1000000;
const SYNTHETIC_ACCESS = "synthetic-service-access";
const settle = () => new Promise((resolve) => setImmediate(resolve));

function storageFixture(values = {}) {
  const valuesByKey = new Map(Object.entries(values));
  return {
    values: valuesByKey, failRead: false, failWrite: false, failRemove: new Set(), removed: [],
    getItem(key) {
      if (this.failRead) throw new Error("synthetic storage read failure");
      return this.values.get(key) ?? null;
    },
    setItem(key, value) {
      if (this.failWrite) throw new Error("synthetic storage write failure");
      this.values.set(key, value);
    },
    removeItem(key) {
      this.removed.push(key);
      if (this.failRemove.has(key)) throw new Error("synthetic storage removal failure");
      this.values.delete(key);
    },
  };
}

function clockFixture() {
  let time = START;
  let nextId = 0;
  const timers = new Map();
  return {
    now: () => time,
    setTimer: (callback, delay) => {
      const id = ++nextId;
      timers.set(id, { callback, at: time + delay });
      return id;
    },
    clearTimer: (id) => timers.delete(id),
    jump: (ms) => { time += ms; },
    advance(ms) {
      const target = time + ms;
      while (true) {
        const next = [...timers].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        time = next[1].at;
        timers.delete(next[0]);
        next[1].callback();
      }
      time = target;
    },
    timers,
  };
}

function networkFixture() {
  const calls = [];
  const fetchImpl = (url, options) => new Promise((resolve, reject) => calls.push({ url, options, resolve, reject }));
  return { fetchImpl, calls };
}

function response(body = { accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: 899 }, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function storedSession(expiresAt = START + 600000, accessToken = SYNTHETIC_ACCESS) {
  return JSON.stringify({ accessToken, expiresAt });
}

function setup(t, { values = {}, options = {} } = {}) {
  const storage = storageFixture({ unrelated: "keep", ...values });
  const clock = clockFixture();
  const network = networkFixture();
  const snapshots = [];
  const navigations = [];
  const replacements = [];
  const auth = new AuthSession({
    getStorage: () => storage, fetchImpl: network.fetchImpl, now: clock.now,
    setTimer: clock.setTimer, clearTimer: clock.clearTimer,
    onChange: (snapshot) => snapshots.push(snapshot),
    navigate: (url) => navigations.push(url), replaceUrl: (url) => replacements.push(url), ...options,
  });
  t.after(() => auth.suspend());
  return { auth, storage, clock, network, snapshots, navigations, replacements };
}

function pendingSetup(t, options = {}) {
  return setup(t, { ...options, values: { [AUTH_STORAGE_KEYS.pending]: "pending", ...options.values } });
}

function assertSignedOut(fixture, status = "error") {
  assert.equal(fixture.auth.getState().status, status);
  assert.equal(fixture.auth.getState().expiresAt, null);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), null);
  assert.equal(fixture.storage.getItem("unrelated"), "keep");
}

test("일반 보드 방문은 교환하지 않고 기존 절대 만료로 복원하며 새로고침 수명 연장 없음", async (t) => {
  const originalExpiry = START + 300000;
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession(originalExpiry) } });
  fixture.clock.jump(120000);
  await fixture.auth.initialize("http://localhost:3000/?x=12#board");
  assert.equal(fixture.network.calls.length, 0);
  assert.equal(fixture.auth.getState().status, "authenticated");
  assert.equal(fixture.auth.getState().expiresAt, originalExpiry);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), storedSession(originalExpiry));
  assert.equal([...fixture.clock.timers.values()][0].at, originalExpiry);
  const fresh = setup(t, { values: Object.fromEntries(fixture.storage.values) });
  fresh.clock.jump(180000);
  await fresh.auth.initialize("http://localhost:3000/");
  assert.equal(fresh.auth.getState().expiresAt, originalExpiry);
  assert.equal(fresh.network.calls.length, 0);
});

test("새 방문은 비로그인이며 로그인 클릭은 기존 인증만 지우고 표식 저장 후 같은 탭 이동", async (t) => {
  const fixture = setup(t);
  await fixture.auth.initialize("http://localhost:3000/");
  assertSignedOut(fixture, "signedOut");
  fixture.storage.setItem(AUTH_STORAGE_KEYS.session, storedSession());
  fixture.auth.navigate = (url) => {
    assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
    assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), "pending");
    fixture.navigations.push(url);
  };
  assert.equal(fixture.auth.beginLogin(), true);
  assert.equal(fixture.auth.getState().status, "checking");
  assert.deepEqual(fixture.navigations, ["http://localhost:8080/oauth2/authorization/kakao"]);
  assert.equal(fixture.auth.beginLogin(), false);
  assert.equal(fixture.navigations.length, 1);
  assert.equal(fixture.network.calls.length, 0);
  assert.equal(fixture.storage.getItem("unrelated"), "keep");
});

test("진행 표식 저장·기존 인증 삭제·navigation 실패는 이동 성공이나 로그인 성공으로 표시하지 않음", async (t) => {
  for (const failure of ["write", "remove", "navigate"]) {
    await t.test(failure, async (t) => {
      const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
      await fixture.auth.initialize("http://localhost:3000/");
      if (failure === "write") fixture.storage.failWrite = true;
      if (failure === "remove") fixture.storage.failRemove.add(AUTH_STORAGE_KEYS.session);
      if (failure === "navigate") fixture.auth.navigate = () => { throw new Error("synthetic navigation failure"); };
      assert.equal(fixture.auth.beginLogin(), false);
      assert.equal(fixture.auth.getState().status, "error");
      assert.equal(fixture.auth.getState().expiresAt, null);
      assert.equal(fixture.navigations.length, 0);
      assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), null);
      assert.equal(fixture.storage.getItem("unrelated"), "keep");
    });
  }
});

test("카카오 실패는 교환 없이 인증 정리하고 처리한 error만 URL에서 제거", async (t) => {
  const fixture = pendingSetup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  await fixture.auth.initialize("http://localhost:3000/?x=12&error=oauth_login_failed&zoom=8#board");
  assertSignedOut(fixture);
  assert.equal(fixture.network.calls.length, 0);
  assert.equal(fixture.replacements.length, 1);
  const url = new URL(fixture.replacements[0]);
  assert.equal(url.searchParams.has("error"), false);
  assert.equal(url.searchParams.get("x"), "12");
  assert.equal(url.searchParams.get("zoom"), "8");
  assert.equal(url.hash, "#board");
  assert.match(fixture.auth.getState().message, /카카오 로그인에 실패/);
});

test("실패 URL 정리가 거부되어도 교환 금지와 인증 정리는 유지", async (t) => {
  const fixture = pendingSetup(t, { options: { replaceUrl: () => { throw new Error("synthetic history failure"); } } });
  await fixture.auth.initialize("http://localhost:3000/?error=oauth_login_failed");
  assertSignedOut(fixture);
  assert.equal(fixture.network.calls.length, 0);
  assert.match(fixture.auth.getState().message, /오류 표시를 정리하지 못/);
});

test("손상된 진행 표식과 소비 실패는 handoff 교환을 시작하지 않음", async (t) => {
  for (const failure of ["corrupt", "remove"]) {
    await t.test(failure, async (t) => {
      const fixture = pendingSetup(t);
      if (failure === "corrupt") fixture.storage.setItem(AUTH_STORAGE_KEYS.pending, "not-pending");
      else fixture.storage.failRemove.add(AUTH_STORAGE_KEYS.pending);
      await fixture.auth.initialize("http://localhost:3000/");
      assert.equal(fixture.network.calls.length, 0);
      assert.equal(fixture.auth.getState().status, "error");
      assert.equal(fixture.auth.getState().expiresAt, null);
    });
  }
});

test("복귀 표식을 먼저 소비하고 반복 초기화도 교환 한 번만 수행하며 쿠키 포함·Authorization 없음", async (t) => {
  const fixture = pendingSetup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  const initial = fixture.auth.initialize("http://localhost:3000/");
  const duplicate = fixture.auth.initialize("http://localhost:3000/");
  assert.equal(initial, duplicate);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), null);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
  fixture.auth.reconcile();
  assert.equal(fixture.network.calls.length, 1);
  const call = fixture.network.calls[0];
  assert.equal(call.url, "http://localhost:8080/api/auth/token");
  assert.equal(call.options.method, "POST");
  assert.equal(call.options.credentials, "include");
  assert.equal(new Headers(call.options.headers).has("Authorization"), false);
  fixture.clock.jump(700);
  call.resolve(response());
  await initial;
  assert.equal(fixture.auth.getState().status, "authenticated");
  assert.deepEqual(JSON.parse(fixture.storage.getItem(AUTH_STORAGE_KEYS.session)), {
    accessToken: SYNTHETIC_ACCESS, expiresAt: START + 899000,
  });
  assert.equal(JSON.stringify(fixture.snapshots).includes(SYNTHETIC_ACCESS), false);
  assert.equal(fixture.auth.getState().expiresAt, START + 899000);
});

test("production 수명 1·899·900초 수용과 실제 요청 시작 기준 만료", async (t) => {
  for (const seconds of [1, 899, 900]) {
    await t.test(`${seconds} seconds`, async (t) => {
      const fixture = pendingSetup(t);
      const initial = fixture.auth.initialize("http://localhost:3000/");
      fixture.clock.jump(250);
      fixture.network.calls[0].resolve(response({ accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: seconds }));
      await initial;
      assert.equal(fixture.auth.getState().status, "authenticated");
      assert.equal(fixture.auth.getState().expiresAt, START + seconds * 1000);
    });
  }
});

test("tokenType·빈 token·TTL 형식/범위 오류와 JSON 실패는 저장하지 않음", async (t) => {
  const valid = { accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: 899 };
  const invalid = [
    null, {}, { ...valid, accessToken: " \t" }, { ...valid, accessToken: 7 },
    { ...valid, tokenType: "bearer" }, { ...valid, tokenType: null },
    ...[0, -1, 901, 21600, 1.5, "899", null].map((expiresInSeconds) => ({ ...valid, expiresInSeconds })),
  ];
  for (const [index, body] of invalid.entries()) {
    await t.test(`invalid field case ${index + 1}`, async (t) => {
      const fixture = pendingSetup(t);
      const initial = fixture.auth.initialize("http://localhost:3000/");
      fixture.network.calls[0].resolve(response(body));
      await initial;
      assertSignedOut(fixture);
      assert.equal(fixture.network.calls.length, 1);
    });
  }
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  fixture.network.calls[0].resolve({ ok: true, status: 200, json: async () => { throw new Error(SYNTHETIC_ACCESS); } });
  await initial;
  assertSignedOut(fixture);
  assert.equal(JSON.stringify(fixture.snapshots).includes(SYNTHETIC_ACCESS), false);
});

test("401·읽을 수 있는 403·5xx 안내를 구분하고 HTTP 실패 뒤 자동 재시도 없음", async (t) => {
  for (const [status, message] of [[401, /없거나 유효하지/], [403, /접속 주소·Origin 설정/], [503, /서버에서/]]) {
    await t.test(`HTTP ${status}`, async (t) => {
      const fixture = pendingSetup(t);
      const initial = fixture.auth.initialize("http://localhost:3000/");
      fixture.network.calls[0].resolve({ ok: false, status, json: () => assert.fail("실패 응답 본문은 읽지 않음") });
      await initial;
      assertSignedOut(fixture);
      assert.match(fixture.auth.getState().message, message);
      fixture.auth.reconcile();
      await fixture.auth.initialize("http://localhost:3000/");
      fixture.clock.advance(30000);
      assert.equal(fixture.network.calls.length, 1);
      assert.equal(fixture.auth.beginLogin(), true);
    });
  }
});

test("상태를 읽을 수 없는 fetch 실패는 네트워크·주소·Origin을 안내하며 원문 예외를 노출하지 않음", async (t) => {
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  fixture.network.calls[0].reject(new TypeError(SYNTHETIC_ACCESS));
  await initial;
  assertSignedOut(fixture);
  assert.match(fixture.auth.getState().message, /네트워크·접속 주소·Origin/);
  assert.equal(JSON.stringify(fixture.snapshots).includes(SYNTHETIC_ACCESS), false);
});

test("fetch와 JSON 본문 모두 유한 기한 적용, timeout 뒤 늦은 성공은 로그인으로 설치하지 않음", async (t) => {
  for (const delayedPart of ["fetch", "json"]) {
    await t.test(delayedPart, async (t) => {
      const fixture = pendingSetup(t);
      const initial = fixture.auth.initialize("http://localhost:3000/");
      let finishBody;
      if (delayedPart === "json") {
        fixture.network.calls[0].resolve({ ok: true, status: 200, json: () => new Promise((resolve) => { finishBody = resolve; }) });
        await settle();
      }
      fixture.clock.advance(AUTH_REQUEST_TIMEOUT_MS);
      await initial;
      assertSignedOut(fixture);
      assert.match(fixture.auth.getState().message, /시간이 초과/);
      assert.equal(fixture.network.calls[0].options.signal.aborted, true);
      if (delayedPart === "fetch") fixture.network.calls[0].resolve(response());
      else finishBody({ accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: 899 });
      await settle();
      assertSignedOut(fixture);
      assert.match(fixture.auth.getState().message, /시간이 초과/);
      assert.equal(fixture.network.calls.length, 1);
      assert.equal(fixture.clock.timers.size, 0);
    });
  }
});

test("브라우저 timer가 지연되어도 요청 절대 기한을 지난 성공은 거부", async (t) => {
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  fixture.clock.jump(AUTH_REQUEST_TIMEOUT_MS + 1);
  fixture.network.calls[0].resolve(response());
  await initial;
  assertSignedOut(fixture);
  assert.match(fixture.auth.getState().message, /시간이 초과/);
});

test("응답 처리 시 이미 만료된 짧은 수명은 저장하지 않음", async (t) => {
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  fixture.clock.jump(1000);
  fixture.network.calls[0].resolve(response({ accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: 1 }));
  await initial;
  assertSignedOut(fixture, "expired");
});

test("Access 저장 실패는 메모리 성공을 만들지 않으며 다음 로그인 재시도 가능", async (t) => {
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  fixture.storage.failWrite = true;
  fixture.network.calls[0].resolve(response());
  await initial;
  assertSignedOut(fixture);
  assert.match(fixture.auth.getState().message, /저장하지 못/);
  fixture.storage.failWrite = false;
  assert.equal(fixture.auth.beginLogin(), true);
});

test("저장소 getter와 getItem 예외도 초기화를 중단하지 않고 안전한 실패 상태로 반환", async (t) => {
  for (const location of ["getter", "getItem"]) {
    await t.test(location, async (t) => {
      const fixture = setup(t);
      if (location === "getter") fixture.auth.getStorage = () => { throw new Error("synthetic blocked storage"); };
      else fixture.storage.failRead = true;
      await fixture.auth.initialize("http://localhost:3000/");
      assert.equal(fixture.auth.getState().status, "error");
      assert.equal(fixture.auth.getState().expiresAt, null);
      assert.equal(fixture.network.calls.length, 0);
      assert.equal(fixture.auth.beginLogin(), location === "getItem");
    });
  }
});

test("손상·만료 저장값은 인증 키만 정리하고 잘못된 수명은 복원하지 않음", async (t) => {
  const invalid = ["{", "null", "{}", JSON.stringify({ accessToken: "", expiresAt: START + 10 }),
    JSON.stringify({ accessToken: SYNTHETIC_ACCESS, expiresAt: "1600000" }),
    storedSession(START + 900001), storedSession(START), storedSession(START - 1)];
  for (const [index, value] of invalid.entries()) {
    await t.test(`stored case ${index + 1}`, async (t) => {
      const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: value } });
      await fixture.auth.initialize("http://localhost:3000/");
      assertSignedOut(fixture, index >= 6 ? "expired" : "error");
      assert.equal(fixture.network.calls.length, 0);
    });
  }
});

test("만료 timer와 탭 활성화 재검사 모두 인증을 지우고 재로그인 허용", async (t) => {
  for (const event of ["timer", "focus-or-visible"]) {
    await t.test(event, async (t) => {
      const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession(START + 1000) } });
      await fixture.auth.initialize("http://localhost:3000/");
      if (event === "timer") fixture.clock.advance(1000);
      else { fixture.clock.jump(1001); fixture.auth.reconcile(); }
      assertSignedOut(fixture, "expired");
      assert.equal(fixture.clock.timers.size, 0);
      assert.equal(fixture.auth.beginLogin(), true);
    });
  }
});

test("정상 logout은 인증과 timer만 지우며 새로고침과 보존 페이지 복원에서도 비로그인", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  await fixture.auth.initialize("http://localhost:3000/");
  assert.equal(fixture.auth.logout(), true);
  assertSignedOut(fixture, "signedOut");
  assert.equal(fixture.clock.timers.size, 0);
  fixture.auth.suspend();
  fixture.auth.resume();
  assertSignedOut(fixture, "signedOut");
  const fresh = setup(t, { values: Object.fromEntries(fixture.storage.values) });
  await fresh.auth.initialize("http://localhost:3000/");
  assertSignedOut(fresh, "signedOut");
  assert.equal(fresh.auth.beginLogin(), true);
});

test("logout 중 삭제 실패는 성공으로 숨기지 않고 focus·pageshow에서도 잔여 Access 재설치 금지", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  await fixture.auth.initialize("http://localhost:3000/");
  fixture.storage.failRemove.add(AUTH_STORAGE_KEYS.session);
  assert.equal(fixture.auth.logout(), false);
  assert.equal(fixture.auth.getState().status, "error");
  assert.match(fixture.auth.getState().message, /지우지 못/);
  assert.doesNotMatch(fixture.auth.getState().message, /로그아웃되었습니다/);
  assert.equal(fixture.auth.getState().expiresAt, null);
  fixture.auth.reconcile();
  fixture.auth.suspend();
  fixture.auth.resume();
  assert.equal(fixture.auth.getState().status, "error");
  assert.equal(fixture.auth.getState().expiresAt, null);
  assert.notEqual(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
  assert.equal(fixture.storage.removed.includes(AUTH_STORAGE_KEYS.pending), true);
  fixture.storage.failRemove.clear();
  fixture.auth.reconcile();
  assertSignedOut(fixture);
  assert.equal(fixture.auth.beginLogin(), true);
});

test("만료 정리 삭제 실패 뒤에도 남은 저장값을 로그인으로 재사용하지 않음", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession(START + 1000) } });
  await fixture.auth.initialize("http://localhost:3000/");
  fixture.storage.failRemove.add(AUTH_STORAGE_KEYS.session);
  fixture.clock.advance(1000);
  assert.equal(fixture.auth.getState().status, "error");
  assert.match(fixture.auth.getState().message, /만료.*지우지 못/);
  fixture.clock.jump(-500);
  fixture.auth.reconcile();
  assert.equal(fixture.auth.getState().status, "error");
  assert.equal(fixture.auth.getState().expiresAt, null);
});

test("logout·새 시도·pagehide 뒤 늦은 응답은 현재 인증을 덮지 않음", async (t) => {
  for (const action of ["logout", "new-login", "pagehide"]) {
    await t.test(action, async (t) => {
      const fixture = pendingSetup(t);
      const initial = fixture.auth.initialize("http://localhost:3000/");
      const old = fixture.network.calls[0];
      if (action === "logout") fixture.auth.logout();
      if (action === "new-login") fixture.auth.beginLogin();
      if (action === "pagehide") fixture.auth.suspend();
      await initial;
      assert.equal(old.options.signal.aborted, true);
      old.resolve(response());
      await settle();
      assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
      assert.notEqual(fixture.auth.getState().status, "authenticated");
      if (action === "new-login") {
        assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), "pending");
        assert.equal(fixture.navigations.length, 1);
        assert.equal(fixture.auth.getState().status, "checking");
      }
      if (action === "pagehide") {
        fixture.auth.resume();
        assertSignedOut(fixture, "signedOut");
        assert.equal(fixture.auth.beginLogin(), true);
      }
    });
  }
});

test("logout 뒤 늦은 JSON 본문도 실패 상태나 새 로그인 진행을 바꾸지 않음", async (t) => {
  const fixture = pendingSetup(t);
  const initial = fixture.auth.initialize("http://localhost:3000/");
  let finishBody;
  fixture.network.calls[0].resolve({ ok: true, status: 200, json: () => new Promise((resolve) => { finishBody = resolve; }) });
  await settle();
  fixture.auth.logout();
  await initial;
  fixture.auth.beginLogin();
  finishBody({ accessToken: SYNTHETIC_ACCESS, tokenType: "Bearer", expiresInSeconds: 899 });
  await settle();
  assert.equal(fixture.auth.getState().status, "checking");
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.session), null);
  assert.equal(fixture.storage.getItem(AUTH_STORAGE_KEYS.pending), "pending");
});

test("pagehide는 timer를 정리하며 pageshow는 기존 절대 만료를 재검사하고 새 시도 가능", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession(START + 1000) } });
  await fixture.auth.initialize("http://localhost:3000/");
  fixture.auth.suspend();
  assert.equal(fixture.clock.timers.size, 0);
  fixture.clock.jump(500);
  fixture.auth.resume();
  assert.equal(fixture.auth.getState().status, "authenticated");
  assert.equal(fixture.auth.getState().expiresAt, START + 1000);
  fixture.auth.suspend();
  fixture.clock.jump(501);
  fixture.auth.resume();
  assertSignedOut(fixture, "expired");
  assert.equal(fixture.auth.beginLogin(), true);
});

test("후속 보호 요청 401 경계는 저장·메모리·timer 정리만 수행하고 네트워크 요청 없음", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  await fixture.auth.initialize("http://localhost:3000/");
  fixture.auth.handleUnauthorized();
  assertSignedOut(fixture, "expired");
  assert.equal(fixture.network.calls.length, 0);
  assert.equal(fixture.clock.timers.size, 0);
});

test("기본 브라우저 fetch는 AuthSession을 receiver로 넘기지 않고 호출", async (t) => {
  const originalFetch = globalThis.fetch;
  let callCount = 0;
  // Node의 느슨한 receiver로 브라우저 Illegal invocation 회귀를 숨기지 않도록 대체
  globalThis.fetch = async function (url) {
    if (this !== undefined && this !== globalThis) throw new TypeError("Illegal invocation");
    assert.equal(url, "http://localhost:8080/api/auth/token");
    callCount += 1;
    return response();
  };
  t.after(() => { globalThis.fetch = originalFetch; });
  const fixture = pendingSetup(t, { options: { fetchImpl: undefined } });
  await fixture.auth.initialize("http://localhost:3000/");
  assert.equal(fixture.auth.getState().status, "authenticated");
  assert.equal(callCount, 1);
});

test("보호 Access와 UI 상태를 분리하고 같은 focus·저장값·보존 페이지에서는 세션 소유권 유지", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
  await fixture.auth.initialize("http://localhost:3000/");
  const first = fixture.auth.acquireAccess();
  assert.equal(first.accessToken, SYNTHETIC_ACCESS);
  assert.equal(Object.hasOwn(fixture.auth.getState(), "accessToken"), false);
  fixture.auth.reconcile();
  assert.equal(fixture.auth.acquireAccess().sessionId, first.sessionId);
  fixture.auth.suspend();
  assert.equal(fixture.auth.acquireAccess(), null);
  assert.equal(fixture.auth.isCurrentSession(first.sessionId), false);
  fixture.auth.resume();
  assert.equal(fixture.auth.acquireAccess().sessionId, first.sessionId);
});

test("저장 token 또는 만료 변경·삭제 뒤 복원은 새 세션이며 이전401은 새 로그인을 지우지 않음", async (t) => {
  for (const mutation of ["token", "expiry", "delete-restore", "logout-restore"]) {
    await t.test(mutation, async (t) => {
      const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession() } });
      await fixture.auth.initialize("http://localhost:3000/");
      const old = fixture.auth.acquireAccess();
      if (mutation === "token") fixture.storage.setItem(AUTH_STORAGE_KEYS.session, storedSession(START + 600000, "new-synthetic-access"));
      if (mutation === "expiry") fixture.storage.setItem(AUTH_STORAGE_KEYS.session, storedSession(START + 500000));
      if (mutation === "delete-restore") {
        fixture.storage.removeItem(AUTH_STORAGE_KEYS.session);
        fixture.auth.reconcile();
        fixture.storage.setItem(AUTH_STORAGE_KEYS.session, storedSession());
      }
      if (mutation === "logout-restore") {
        fixture.auth.logout();
        fixture.storage.setItem(AUTH_STORAGE_KEYS.session, storedSession());
      }
      const fresh = fixture.auth.acquireAccess();
      assert.notEqual(fresh.sessionId, old.sessionId);
      assert.equal(fixture.auth.handleUnauthorized(old.sessionId), false);
      assert.equal(fixture.auth.getState().status, "authenticated");
      assert.equal(fixture.auth.getSessionId(), fresh.sessionId);
    });
  }
});

test("보호 요청 Access 취득과 소유권 확인은 지연 timer와 무관하게 절대 만료를 차단", async (t) => {
  const fixture = setup(t, { values: { [AUTH_STORAGE_KEYS.session]: storedSession(START + 1000) } });
  await fixture.auth.initialize("http://localhost:3000/");
  const old = fixture.auth.acquireAccess();
  fixture.clock.jump(1001);
  assert.equal(fixture.auth.acquireAccess(), null);
  assert.equal(fixture.auth.isCurrentSession(old.sessionId), false);
  assertSignedOut(fixture, "expired");
});
