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
const change = (x, y, color, tileVersion = 12n, eventSeq = tileVersion) =>
  ({ x, y, color, tileVersion, eventSeq });

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

test("초기 GET 중 수락된 V12는 미조회 상태를 유지하고 늦은 B10에 재병합", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  assert.equal(loader.applyChange(change(1, 0, 3)), true);
  assert.equal(loader.get("0,0").data, null);
  assert.equal(loader.get("0,0").state, "loading");
  network.calls[0].resolve(response("10", [1, 1, 1, 1]));
  await settle();
  const entry = loader.get("0,0");
  assert.deepEqual([...entry.data.bytes], [1, 3, 1, 1]);
  assert.deepEqual([...entry.merge.snapshot.bytes], [1, 1, 1, 1]);
  assert.equal(entry.merge.snapshot.version, "10");
  assert.equal(entry.state, "ready");
});

test("수락으로 갱신한 화면 identity와 journal은 재조회·실패 중에도 보존", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  network.calls[0].resolve(response("10", [0, 0, 0, 0]));
  await settle();
  const entry = loader.get("0,0");
  const original = entry.data;
  loader.applyChange(change(1, 1, 3));
  assert.notEqual(entry.data, original);
  assert.equal(entry.data.bytes[3], 3);
  loader.reload();
  loader.applyChange(change(0, 1, 2, 11n));
  network.calls[1].resolve(response("10", [0, 0, 0, 0]));
  await settle();
  assert.deepEqual([...entry.data.bytes], [0, 0, 2, 3]);
  loader.reload();
  network.calls[2].reject(new TypeError("synthetic network failure"));
  await settle();
  assert.equal(entry.state, "error");
  loader.applyChange(change(0, 0, 1, 13n));
  assert.equal(entry.state, "error");
  assert.deepEqual([...entry.data.bytes], [1, 0, 2, 3]);
});

test("최신 GET만 수락 변경을 재병합하며 이전 generation은 원본 쌍도 변경하지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  loader.applyChange(change(0, 0, 3));
  loader.reload();
  network.calls[1].resolve(response("11", [1, 1, 1, 1]));
  await settle();
  network.calls[0].resolve(response("99", [2, 2, 2, 2]));
  await settle();
  const entry = loader.get("0,0");
  assert.equal(entry.merge.snapshot.version, "11");
  assert.deepEqual([...entry.data.bytes], [3, 1, 1, 1]);
});

test("해제된 미조회 entry의 변경은 정리하고 같은 타일 새 entry에 이전 GET 설치 금지", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  const released = loader.get("0,0");
  loader.applyChange(change(0, 0, 3));
  loader.setVisible([]);
  assert.equal(released.merge.changes.size, 0);
  assert.equal(loader.applyChange(change(0, 0, 2, 13n)), false);
  assert.equal(loader.entries.size, 0);
  loader.setVisible([tile(0)]);
  network.calls[1].resolve(response("13", [2, 0, 0, 0]));
  await settle();
  network.calls[0].resolve(response("99", [3, 3, 3, 3]));
  await settle();
  assert.notEqual(loader.get("0,0"), released);
  assert.deepEqual([...loader.get("0,0").data.bytes], [2, 0, 0, 0]);
  assert.equal(loader.get("0,0").merge.changes.size, 0);
});

test("기본 동시 GET4와 화면 밖 snapshot24를 유지하며 퇴출·dispose에서 journal 해제", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1), tile(2), tile(3), tile(4)]);
  assert.equal(network.calls.length, 4);
  for (const call of network.calls) call.resolve(response("10"));
  await settle();
  network.calls[4].resolve(response("10"));
  await settle();
  const first = loader.get("0,0");
  loader.applyChange(change(0, 0, 3));
  for (let index = 5; index < 30; index++) {
    const tx = index % 8;
    const ty = Math.floor(index / 8);
    loader.setVisible([tile(tx, ty)]);
    network.calls.at(-1).resolve(response("10"));
    await settle();
    loader.applyChange(change(tx * 2, ty * 2, 3));
    assert.ok(loader.entries.size <= 25);
  }
  assert.equal(loader.entries.size, 25);
  assert.equal(loader.get("0,0"), undefined);
  assert.equal(first.merge.changes.size, 0);
  assert.equal(first.merge.snapshot, null);
  assert.equal(first.data, null);
  assert.equal(loader.applyChange(change(0, 0, 2, 99n)), false);
  const last = loader.get("5,3");
  assert.equal(last.merge.changes.size, 1);
  loader.dispose();
  assert.equal(last.merge.changes.size, 0);
  assert.equal(last.data, null);
  assert.equal(loader.applyChange(change(10, 6, 2, 99n)), false);
  assert.equal(loader.entries.size, 0);
});

test("새 연결은 이전 journal과 화면 밖 cache를 해제하고 낮은 정상 snapshot 위에 현재 변경만 병합", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1)]);
  network.calls[0].resolve(response("20", [3, 3, 3, 3]));
  network.calls[1].resolve(response("20", [3, 3, 3, 3]));
  await settle();
  loader.applyChange(change(0, 0, 3, 25n));
  const previous = loader.get("0,0");
  const outside = loader.get("1,0");
  const oldGeneration = loader.getGeneration();
  loader.setVisible([tile(0)]);
  assert.equal(loader.reset([tile(0)]), oldGeneration + 1);
  assert.equal(previous.merge.changes.size, 0);
  assert.equal(outside.data, null);
  assert.equal(loader.get("1,0"), undefined);
  assert.equal(loader.get("0,0").data, null);
  assert.equal(loader.getVisibleState().complete, false);
  loader.applyChange(change(1, 0, 2, 2n));
  network.calls[2].resolve(response("1", [1, 1, 1, 1]));
  await settle();
  assert.deepEqual([...loader.get("0,0").data.bytes], [1, 2, 1, 1]);
  assert.equal(loader.get("0,0").merge.snapshot.version, "1");
  assert.equal(loader.getVisibleState().complete, true);
  loader.setVisible([tile(1)]);
  assert.equal(network.calls.length, 4);
  assert.equal(loader.get("1,0").data, null);
  network.calls[3].resolve(response("0", [0, 0, 0, 0]));
  await settle();
  assert.deepEqual([...loader.get("1,0").data.bytes], [0, 0, 0, 0]);
});

test("취소를 무시한 이전 연결 GET은 실제 슬롯 반환까지 보유하고 새 entry나 타이머를 덮지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 1 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0)]);
  const previousRequest = loader.get("0,0").request;
  loader.applyChange(change(0, 0, 3, 100n));
  loader.reset([tile(0)]);
  assert.equal(previousRequest.controller.signal.aborted, true);
  assert.equal(previousRequest.timer, null);
  assert.equal(loader.active.size, 1);
  assert.equal(loader.pending.size, 1);
  assert.equal(network.calls.length, 1);
  network.calls[0].resolve(response("99", [3, 3, 3, 3]));
  await settle();
  assert.equal(network.calls.length, 2);
  assert.equal(loader.get("0,0").data, null);
  assert.equal(loader.get("0,0").request.controller.signal.aborted, false);
  assert.equal(loader.active.size, 1);
  network.calls[1].resolve(response("0", [0, 0, 0, 0]));
  await settle();
  assert.deepEqual([...loader.get("0,0").data.bytes], [0, 0, 0, 0]);
  assert.equal(loader.active.size, 0);
});

test("페이지 무효화는 대기·현재 영역·journal·기한을 정리하고 늦은 GET에서 조회를 다시 시작하지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 1 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1)]);
  loader.applyChange(change(0, 0, 3));
  const previous = loader.get("0,0");
  const request = previous.request;
  const generation = loader.getGeneration();
  loader.invalidate();
  assert.equal(loader.getGeneration(), generation + 1);
  assert.equal(request.timer, null);
  assert.equal(request.controller.signal.aborted, true);
  assert.equal(previous.merge.changes.size, 0);
  assert.equal(loader.pending.size, 0);
  assert.equal(loader.entries.size, 0);
  assert.deepEqual(loader.getVisibleState(), { total: 0, ready: 0, failed: 0, loading: 0, complete: false });
  network.calls[0].resolve(response("99"));
  await settle();
  assert.equal(network.calls.length, 1);
  assert.equal(loader.entries.size, 0);
  assert.equal(loader.active.size, 0);
});

test("이전 화면 세대 HTTP 수락은 현재 보이는 해당 타일만 수락 이후 GET으로 교체하고 journal에 섞지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  const submittedGeneration = loader.getGeneration();
  loader.reset([tile(0), tile(1)]);
  loader.applyChange(change(0, 1, 2, 5n));
  assert.equal(loader.applyAccepted(change(0, 0, 3, 99n), submittedGeneration), true);
  assert.equal(network.calls.length, 3);
  assert.equal(network.calls[0].options.signal.aborted, true);
  assert.equal(network.calls[1].options.signal.aborted, false);
  assert.equal(network.calls[2].url.endsWith("/0/0"), true);
  assert.equal(loader.get("0,0").merge.changes.size, 1);
  network.calls[2].resolve(response("1", [1, 1, 1, 1]));
  network.calls[1].resolve(response("2"));
  await settle();
  network.calls[0].resolve(response("99", [3, 3, 3, 3]));
  await settle();
  assert.deepEqual([...loader.get("0,0").data.bytes], [1, 1, 2, 1]);
  assert.equal(loader.get("0,0").merge.snapshot.version, "1");
  assert.equal(loader.getVisibleState().complete, true);
  assert.equal(network.calls.length, 3);
});

test("이전 세대 HTTP 수락의 화면 밖 타일은 재방문 GET을 요구하고 미보유 타일은 만들지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  const submittedGeneration = loader.getGeneration();
  loader.reset([tile(0), tile(1)]);
  network.calls[0].resolve(response("1"));
  network.calls[1].resolve(response("1"));
  await settle();
  loader.setVisible([tile(0)]);
  const outside = loader.get("1,0");
  assert.equal(loader.applyAccepted(change(2, 0, 3), submittedGeneration), true);
  assert.equal(outside.data, null);
  assert.equal(loader.get("1,0"), undefined);
  assert.equal(loader.applyAccepted(change(14, 0, 3), submittedGeneration), false);
  assert.equal(loader.get("7,0"), undefined);
  assert.equal(network.calls.length, 2);
  loader.setVisible([tile(1)]);
  assert.equal(network.calls.length, 3);
  assert.equal(loader.get("1,0").state, "loading");
  assert.equal(loader.get("1,0").data, null);
  network.calls[2].resolve(response("12", [3, 1, 2, 3]));
  await settle();
  assert.equal(loader.get("1,0").data.bytes[0], 3);
});

test("같은 세대 HTTP와 WS는 공통 병합하며 정상 변경의 추가 GET 없이 Canvas 입력 identity 갱신", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.reset([tile(0)]);
  const generation = loader.getGeneration();
  network.calls[0].resolve(response("10", [0, 0, 0, 0]));
  await settle();
  const before = loader.get("0,0").data;
  assert.equal(loader.applyAccepted(change(0, 0, 3, 12n, 100n), generation), true);
  assert.equal(loader.applyChange(change(0, 0, 3, 12n, 100n)), false);
  assert.equal(loader.applyChange(change(1, 0, 2, 11n, 90n)), true);
  assert.equal(loader.applyAccepted(change(1, 0, 2, 11n, 90n), generation), false);
  assert.notEqual(loader.get("0,0").data, before);
  assert.deepEqual([...loader.get("0,0").data.bytes], [3, 2, 0, 0]);
  assert.equal(loader.get("0,0").merge.snapshot.version, "10");
  assert.equal(network.calls.length, 1);
});

test("사용자 연속 재조회는 진행·대기 GET을 합치고 같은 연결 journal을 보존", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: 1 });
  t.after(() => loader.dispose());
  loader.setVisible([tile(0), tile(1)]);
  loader.reload({ coalesce: true });
  loader.reload({ coalesce: true });
  assert.equal(network.calls.length, 1);
  assert.equal(network.calls[0].options.signal.aborted, false);
  assert.equal(loader.pending.size, 1);
  network.calls[0].resolve(response("10"));
  await settle();
  network.calls[1].resolve(response("10"));
  await settle();
  loader.applyChange(change(0, 0, 3));
  const generation = loader.getGeneration();
  loader.reload({ coalesce: true });
  loader.reload({ coalesce: true });
  assert.equal(loader.getGeneration(), generation);
  assert.equal(network.calls.length, 3);
  assert.equal(network.calls[2].options.signal.aborted, false);
  assert.equal(loader.pending.size, 1);
  network.calls[2].resolve(response("10"));
  await settle();
  network.calls[3].resolve(response("10"));
  await settle();
  assert.equal(loader.get("0,0").data.bytes[0], 3);
  assert.equal(loader.getVisibleState().complete, true);
  assert.equal(network.calls.length, 4);
});

test("현재 영역의 일부 실패·새 영역 로딩은 완료가 아니며 화면 밖 이전 완료로 상태를 바꾸지 않음", async (t) => {
  const network = controlledFetch();
  const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl });
  t.after(() => loader.dispose());
  loader.reset([tile(0), tile(1)]);
  network.calls[0].resolve(response("1"));
  network.calls[1].reject(new Error("synthetic tile failure"));
  await settle();
  assert.deepEqual(loader.getVisibleState(), { total: 2, ready: 1, failed: 1, loading: 0, complete: false });
  loader.reload({ coalesce: true });
  loader.setVisible([tile(2)]);
  assert.deepEqual(loader.getVisibleState(), { total: 1, ready: 0, failed: 0, loading: 1, complete: false });
  network.calls[2].resolve(response("2"));
  network.calls[3].resolve(response("2"));
  await settle();
  assert.equal(loader.getVisibleState().complete, false);
  network.calls[4].resolve(response("1"));
  await settle();
  assert.deepEqual(loader.getVisibleState(), { total: 1, ready: 1, failed: 0, loading: 0, complete: true });
});

for (const stage of ["진행 중", "슬롯 대기", "실패 후"]) {
  test(`과거 세대 수락의 필수 GET ${stage} 이동은 cache를 해제하고 재방문 GET 뒤에만 완료`, async (t) => {
    const network = controlledFetch({ respectAbort: true });
    const loader = new TileLoader({ metadata, fetchImpl: network.fetchImpl, concurrency: stage === "슬롯 대기" ? 1 : 2 });
    t.after(() => loader.dispose());
    const submittedGeneration = loader.getGeneration();
    loader.reset([tile(0)]);
    network.calls[0].resolve(response("10", [0, 0, 0, 0]));
    await settle();
    const previous = loader.get("0,0");
    loader.applyChange(change(1, 0, 2, 15n));
    if (stage === "슬롯 대기") loader.setVisible([tile(1), tile(0)]);
    loader.applyAccepted(change(0, 0, 3, 12n), submittedGeneration);
    if (stage === "실패 후") {
      network.calls[1].reject(new Error("synthetic mandatory refresh failure"));
      await settle();
      assert.equal(previous.state, "error");
    } else if (stage === "슬롯 대기") {
      assert.equal(loader.pending.has("0,0"), true);
      assert.equal(previous.request, null);
    } else {
      assert.notEqual(previous.request, null);
    }
    assert.equal(loader.getVisibleState().complete, false);
    assert.notEqual(previous.data, null);

    // 수락 이후 snapshot을 설치하지 못한 entry는 정상 cache로 되돌릴 수 없음
    loader.setVisible([tile(1)]);
    await settle();
    assert.equal(loader.get("0,0"), undefined);
    assert.equal(previous.data, null);
    assert.equal(previous.merge.changes.size, 0);
    network.calls.find((call) => call.url.endsWith("/1/0")).resolve(response("10"));
    await settle();
    const beforeRevisit = network.calls.length;
    loader.setVisible([tile(0)]);
    await settle();
    assert.equal(network.calls.length, beforeRevisit + 1);
    assert.equal(loader.get("0,0").data, null);
    assert.equal(loader.getVisibleState().complete, false);
    network.calls.at(-1).resolve(response("12", [3, 0, 0, 0]));
    await settle();
    assert.equal(loader.get("0,0").data.bytes[0], 3);
    assert.equal(loader.getVisibleState().complete, true);

    // 필수 snapshot 성공 뒤에는 기존 같은 연결의 화면 밖 cache 재사용 유지
    const afterSuccess = network.calls.length;
    loader.setVisible([tile(1)]);
    loader.setVisible([tile(0)]);
    await settle();
    assert.equal(network.calls.length, afterSuccess);
    assert.equal(loader.get("0,0").data.bytes[0], 3);
    assert.equal(loader.getVisibleState().complete, true);
  });
}
