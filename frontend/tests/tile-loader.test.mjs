// 지연·실패·취소 합성 응답으로 요청 소유권과 유한 보유 검증. 실제 서버 성공 증거와 분리
import test from "node:test";
import assert from "node:assert/strict";
import { TileLoader } from "../tile-loader.mjs";

const metadata = {
  boardSize: 16,
  tileSize: 2,
  z: 0,
  tileCountX: 8,
  tileCountY: 8,
  paletteSize: 4,
  palette: ["#FFFFFF", "#FF0000", "#00FF00", "#0000FF"],
  overviewRefreshSeconds: 10,
};

function tile(tx, ty = 0) {
  return { tx, ty, key: `${tx},${ty}` };
}

function response(version = "0", bytes = [0, 1, 2, 3], status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => name.toLowerCase() === "x-tile-version" ? version : null },
    arrayBuffer: async () => Uint8Array.from(bytes).buffer,
  };
}

function controlledFetch({ respectAbort = false } = {}) {
  const calls = [];
  const fetchImpl = (url, options = {}) => new Promise((resolve, reject) => {
    const call = { url, options, resolve, reject };
    calls.push(call);
    if (respectAbort) {
      options.signal.addEventListener("abort", () => {
        reject(new DOMException("synthetic aborted request", "AbortError"));
      }, { once: true });
    }
  });
  return { fetchImpl, calls };
}

// fetch→arrayBuffer→finally까지 완료된 상태에서 다음 관찰을 수행하기 위한 이벤트 루프 경계
const settle = () => new Promise((resolve) => setImmediate(resolve));

test("기본 브라우저 fetch는 TileLoader를 receiver로 넘기지 않고 호출", async (t) => {
  const originalFetch = globalThis.fetch;
  let callCount = 0;
  // Node fetch의 느슨한 receiver 허용으로 실제 브라우저 Illegal invocation을 놓치지 않도록 대체
  globalThis.fetch = async function (url) {
    if (this !== undefined && this !== globalThis) throw new TypeError("Illegal invocation");
    assert.equal(url, "http://localhost:8080/api/tiles/0/0/0");
    callCount += 1;
    return response("4");
  };
  const loader = new TileLoader({ metadata });
  t.after(() => {
    loader.dispose();
    globalThis.fetch = originalFetch;
  });
  loader.setVisible([tile(0)]);
  await settle();
  assert.equal(loader.get("0,0").state, "ready");
  assert.equal(String(loader.get("0,0").data.version), "4");
  assert.equal(callCount, 1);
});

test("같은 타일 요청을 합치고 공개 GET의 원본 z=0 바이트·버전 쌍을 설치", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  loader.setVisible([tile(0)]);
  await settle();
  assert.equal(network.calls.length, 1);
  const call = network.calls[0];
  assert.equal(call.url, "http://localhost:8080/api/tiles/0/0/0");
  assert.equal(new Headers(call.options.headers).has("Authorization"), false);
  assert.equal(call.options.method ?? "GET", "GET");
  call.resolve(response("9007199254740993", [1, 2, 3, 0]));
  await settle();
  const entry = loader.get("0,0");
  assert.equal(entry.state, "ready");
  assert.equal(String(entry.data.version), "9007199254740993");
  assert.deepEqual([...entry.data.bytes], [1, 2, 3, 0]);
  loader.setVisible([tile(0)]);
  await settle();
  assert.equal(network.calls.length, 1);
});

test("잘못된 길이·누락 버전·HTTP 실패는 정상 데이터로 설치하지 않으며 무한 재시도 없음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1), tile(2)]);
  await settle();
  network.calls[0].resolve(response("0", [0, 1, 2]));
  network.calls[1].resolve(response(null));
  network.calls[2].resolve(response("0", [0, 1, 2, 3], 503));
  await settle();
  for (const key of ["0,0", "1,0", "2,0"]) {
    assert.equal(loader.get(key).state, "error");
    assert.equal(loader.get(key).data, null);
  }
  loader.setVisible([tile(0), tile(1), tile(2)]);
  await settle();
  assert.equal(network.calls.length, 3);
});

test("일부 실패 뒤 명시적 재조회는 현재 타일을 다시 받아 정상 상태로 복구", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1)]);
  await settle();
  network.calls[0].resolve(response("3"));
  network.calls[1].reject(new TypeError("synthetic network failure"));
  await settle();
  assert.equal(loader.get("0,0").state, "ready");
  assert.equal(loader.get("1,0").state, "error");
  loader.reload();
  await settle();
  assert.equal(network.calls.length, 4);
  assert.equal(loader.get("0,0").state, "loading");
  assert.equal(String(loader.get("0,0").data.version), "3");
  network.calls[2].resolve(response("4"));
  network.calls[3].resolve(response("5"));
  await settle();
  assert.equal(loader.get("0,0").state, "ready");
  assert.equal(loader.get("1,0").state, "ready");
  assert.equal(String(loader.get("1,0").data.version), "5");
});

test("재조회 실패 시 이전 snapshot 보존과 실패 상태를 함께 유지", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  network.calls[0].resolve(response("7", [3, 2, 1, 0]));
  await settle();
  loader.reload();
  await settle();
  network.calls[1].resolve(response(null));
  await settle();
  assert.equal(loader.get("0,0").state, "error");
  assert.equal(String(loader.get("0,0").data.version), "7");
  assert.deepEqual([...loader.get("0,0").data.bytes], [3, 2, 1, 0]);
});

test("슬롯 대기 중인 재조회가 화면 밖으로 나가면 이전 snapshot은 loading 잔여 없이 재사용", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 1 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  network.calls[0].resolve(response("8"));
  await settle();
  loader.setVisible([tile(0), tile(1)]);
  await settle();
  loader.reload();
  await settle();
  assert.equal(loader.get("0,0").state, "loading");
  assert.equal(loader.pending.has("0,0"), true);
  loader.setVisible([tile(1)]);
  assert.equal(loader.get("0,0").state, "ready");
  assert.equal(loader.pending.has("0,0"), false);
  loader.setVisible([tile(0)]);
  assert.equal(loader.get("0,0").state, "ready");
  assert.equal(String(loader.get("0,0").data.version), "8");
  network.calls[1].resolve(response("9"));
  await settle();
  assert.equal(network.calls.length, 2);
  assert.equal(loader.pending.size, 0);
});

test("이전 재조회 응답이 나중에 도착해도 더 최근 snapshot을 덮어쓰지 않음", async (t) => {
  // 취소를 무시하는 transport로 AbortController 이외의 소유권 검증을 강제
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  const oldCall = network.calls[0];
  loader.reload();
  await settle();
  assert.equal(oldCall.options.signal.aborted, true);
  assert.equal(network.calls.length, 2);
  network.calls[1].resolve(response("20", [3, 3, 3, 3]));
  await settle();
  oldCall.resolve(response("19", [1, 1, 1, 1]));
  await settle();
  assert.equal(String(loader.get("0,0").data.version), "20");
  assert.deepEqual([...loader.get("0,0").data.bytes], [3, 3, 3, 3]);
});

test("재조회 실패 후 도착한 과거 성공도 실패 상태와 이전 snapshot을 바꾸지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  loader.reload();
  await settle();
  network.calls[1].reject(new TypeError("synthetic latest failure"));
  await settle();
  network.calls[0].resolve(response("1"));
  await settle();
  assert.equal(loader.get("0,0").state, "error");
  assert.equal(loader.get("0,0").data, null);
});

test("헤더 수신 뒤 지연된 이전 본문도 최신 요청의 바이트·버전 쌍을 바꾸지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  let finishBody;
  const oldResponse = response("10");
  oldResponse.arrayBuffer = () => new Promise((resolve) => { finishBody = resolve; });
  network.calls[0].resolve(oldResponse);
  await settle();
  loader.reload();
  await settle();
  network.calls[1].resolve(response("11", [2, 2, 2, 2]));
  await settle();
  finishBody(Uint8Array.from([1, 1, 1, 1]).buffer);
  await settle();
  assert.equal(String(loader.get("0,0").data.version), "11");
  assert.deepEqual([...loader.get("0,0").data.bytes], [2, 2, 2, 2]);
});

test("동시 요청 수를 제한하고 완료 시 현재 필요 타일의 대기 조회를 진행", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1), tile(2), tile(3)]);
  await settle();
  assert.equal(network.calls.length, 2);
  assert.equal(loader.active.size, 2);
  network.calls[0].resolve(response());
  await settle();
  assert.equal(network.calls.length, 3);
  assert.equal(loader.active.size, 2);
  network.calls[1].resolve(response());
  await settle();
  assert.equal(network.calls.length, 4);
  network.calls[2].resolve(response());
  network.calls[3].resolve(response());
  await settle();
  assert.equal(loader.active.size, 0);
  assert.equal(loader.pending.size, 0);
});

test("빠른 이동은 불필요 요청과 대기를 취소하고 새 화면의 조회를 진행", async (t) => {
  const network = controlledFetch({ respectAbort: true });
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 2, offscreenLimit: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1), tile(2), tile(3)]);
  await settle();
  loader.setVisible([tile(6), tile(7)]);
  await settle();
  assert.equal(network.calls[0].options.signal.aborted, true);
  assert.equal(network.calls[1].options.signal.aborted, true);
  assert.equal(network.calls.length, 4);
  assert.deepEqual(network.calls.slice(2).map(({ url }) => url.split("/").slice(-2).join(",")), ["6,0", "7,0"]);
  network.calls[2].resolve(response());
  network.calls[3].resolve(response());
  await settle();
  assert.equal(loader.get("6,0").state, "ready");
  assert.equal(loader.get("7,0").state, "ready");
  assert.equal(loader.pending.size, 0);
  assert.ok(loader.entries.size <= 4);
});

test("화면 밖 cache는 유한하고 삭제된 타일의 늦은 응답은 다시 적재하지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 4, offscreenLimit: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  const stale = network.calls[0];
  for (let tx = 1; tx < 8; tx++) {
    loader.setVisible([tile(tx)]);
    await settle();
    network.calls.at(-1).resolve(response(String(tx)));
    await settle();
    assert.ok(loader.entries.size <= 3);
  }
  assert.equal(loader.get("0,0"), undefined);
  stale.resolve(response("99"));
  await settle();
  assert.equal(loader.get("0,0"), undefined);
  assert.ok(loader.entries.size <= 3);
  assert.equal(String(loader.get("7,0").data.version), "7");
});

test("화면으로 돌아온 유효 cache는 재사용하며 dispose 뒤 늦은 응답 설치 금지", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, offscreenLimit: 2 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  await settle();
  network.calls[0].resolve(response("1"));
  await settle();
  loader.setVisible([tile(1)]);
  await settle();
  loader.setVisible([tile(0)]);
  await settle();
  assert.equal(network.calls.length, 2);
  assert.equal(loader.get("0,0").state, "ready");
  const pending = network.calls[1];
  loader.dispose();
  assert.equal(pending.options.signal.aborted, true);
  pending.resolve(response("2"));
  await settle();
  assert.equal(loader.entries.size, 0);
  assert.equal(loader.pending.size, 0);
});
