// 네트워크와 무관한 병합 계약 검증. 실제 WS 연결 없이 HTTP·합성 이벤트를 같은 경계로 전달
import test from "node:test";
import assert from "node:assert/strict";
import { TileMerge } from "../tile-merge.mjs";
import { pixelsToRgba, validateTileResponse } from "../board-model.mjs";

const metadata = { boardSize: 8, tileSize: 2, paletteSize: 4 };
const palette = ["#FFFFFF", "#FF0000", "#00FF00", "#0000FF"];
const change = (x, y, color, tileVersion, eventSeq = tileVersion) => ({ x, y, color, tileVersion, eventSeq });
const snapshot = (version = "10", bytes = [0, 0, 0, 0]) =>
  validateTileResponse(Uint8Array.from(bytes).buffer, version, metadata);

test("B10 이후 같은 픽셀 V12 뒤 V11은 제외하고 다른 픽셀 V11은 반영", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.installSnapshot(snapshot());
  assert.equal(tile.applyChange(change(0, 0, 3, 12n, 100n)), true);
  assert.equal(tile.applyChange(change(0, 0, 1, 11n, 98n)), false);
  assert.equal(tile.applyChange(change(1, 0, 2, 11n, 98n)), true);
  assert.equal(tile.applyChange(change(0, 1, 2, 10n, 200n)), false);
  assert.deepEqual([...tile.data.bytes], [3, 2, 0, 0]);
  assert.equal(tile.changes.size, 2);
});

test("HTTP와 합성 WS 중복·양방향 도착 순서는 같은 픽셀 결과로 수렴", () => {
  const http = change(0, 1, 1, 11n, 21n);
  const ws = change(0, 1, 2, 12n, 22n);
  const neighbor = change(1, 1, 3, 13n, 30n);
  for (const events of [[http, ws, http, neighbor, ws], [neighbor, ws, http, ws, http]]) {
    const tile = new TileMerge(metadata, 0, 0);
    tile.installSnapshot(snapshot());
    for (const event of events) tile.applyChange(event);
    assert.deepEqual([...tile.data.bytes], [0, 0, 2, 3]);
    assert.equal(tile.changes.size, 2);
  }
});

test("snapshot 없는 변경은 정상 데이터로 만들지 않고 늦은 B10 위에 V12만 병합", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.applyChange(change(0, 0, 2, 9n));
  tile.applyChange(change(1, 1, 3, 12n));
  assert.equal(tile.data, null);
  assert.equal(tile.snapshot, null);
  tile.installSnapshot(snapshot("10", [1, 1, 1, 1]));
  assert.deepEqual([...tile.data.bytes], [1, 1, 1, 3]);
  assert.equal(tile.changes.size, 1);
});

test("새 snapshot 설치는 이미 보이는 최신 변경을 재병합하고 포함된 변경 기록만 정리", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.installSnapshot(snapshot());
  tile.applyChange(change(0, 0, 1, 11n));
  tile.applyChange(change(1, 1, 3, 13n));
  tile.installSnapshot(snapshot("12", [1, 2, 2, 2]));
  assert.deepEqual([...tile.data.bytes], [1, 2, 2, 3]);
  assert.equal(tile.changes.size, 1);
  assert.equal(tile.applyChange(change(0, 0, 3, 11n)), false);
  tile.installSnapshot(snapshot("13", [1, 2, 2, 3]));
  assert.equal(tile.changes.size, 0);
});

test("원본 bytes·B 쌍과 기존 화면 데이터는 보존하고 새 identity로 Canvas RGBA 갱신", () => {
  const tile = new TileMerge(metadata, 0, 0);
  const original = snapshot();
  tile.installSnapshot(original);
  const base = tile.snapshot;
  const first = tile.data;
  const cache = new WeakMap([[first, pixelsToRgba(first.bytes, palette)]]);
  tile.applyChange(change(0, 0, 1, 12n));
  assert.equal(tile.snapshot, base);
  assert.equal(tile.snapshot.version, "10");
  assert.deepEqual([...tile.snapshot.bytes], [0, 0, 0, 0]);
  assert.deepEqual([...original.bytes], [0, 0, 0, 0]);
  assert.deepEqual([...first.bytes], [0, 0, 0, 0]);
  assert.notEqual(tile.data, first);
  assert.equal(cache.has(tile.data), false);
  assert.deepEqual([...pixelsToRgba(tile.data.bytes, palette).slice(0, 4)], [255, 0, 0, 255]);
});

test("snapshot 입력의 외부 bytes 변경은 보존한 원본과 화면에 영향 없음", () => {
  const tile = new TileMerge(metadata, 0, 0);
  const source = snapshot();
  tile.installSnapshot(source);
  source.bytes[0] = 3;
  assert.equal(tile.snapshot.bytes[0], 0);
  assert.equal(tile.data.bytes[0], 0);
});

test("같은 색도 최신 버전을 보관하여 더 오래된 다른 색으로 되돌리지 않음", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.installSnapshot(snapshot());
  assert.equal(tile.applyChange(change(0, 0, 0, 12n)), true);
  assert.equal(tile.applyChange(change(0, 0, 3, 11n)), false);
  assert.equal(tile.data.bytes[0], 0);
  assert.equal(tile.changes.get(0).tileVersion, 12n);
});

test("Java long 상한과 2^53 초과 버전은 손실 없이 비교", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.installSnapshot(snapshot("9007199254740992"));
  assert.equal(tile.applyChange(change(0, 0, 1, 9007199254740993n)), true);
  assert.equal(tile.applyChange(change(0, 0, 2, 9007199254740992n)), false);
  assert.equal(tile.applyChange(change(1, 0, 3, 9223372036854775807n)), true);
  tile.installSnapshot(snapshot("9007199254740993", [1, 0, 0, 0]));
  assert.deepEqual([...tile.data.bytes], [1, 3, 0, 0]);
  assert.equal(tile.changes.size, 1);
});

test("잘못된 좌표·팔레트·정규화되지 않은 순서 값은 상태 변경 없이 거부", () => {
  const tile = new TileMerge(metadata, 1, 1);
  tile.installSnapshot(snapshot());
  const valid = change(2, 2, 1, 11n);
  for (const event of [null, { ...valid, x: 0 }, { ...valid, x: -1 }, { ...valid, y: 8 },
    { ...valid, y: 2.5 }, { ...valid, x: "2" }, { ...valid, color: -1 }, { ...valid, color: 4 },
    { ...valid, color: null }, { ...valid, tileVersion: 0n }, { ...valid, tileVersion: 11 },
    { ...valid, tileVersion: "11" }, { ...valid, tileVersion: 9223372036854775808n },
    { ...valid, eventSeq: 0n }, { ...valid, eventSeq: 9223372036854775808n }]) {
    assert.equal(tile.applyChange(event), false);
  }
  assert.equal(tile.changes.size, 0);
  assert.deepEqual([...tile.data.bytes], [0, 0, 0, 0]);
  assert.equal(tile.applyChange(change(3, 3, 2, 11n)), true);
  assert.deepEqual([...tile.data.bytes], [0, 0, 0, 2]);
});

test("여러 변경을 받아도 픽셀당 최신 하나만 보유하고 clear에서 모두 해제", () => {
  const tile = new TileMerge(metadata, 0, 0);
  tile.installSnapshot(snapshot("0"));
  for (let version = 1n; version <= 20n; version++) {
    for (let pixel = 0; pixel < 4; pixel++) {
      tile.applyChange(change(pixel % 2, Math.floor(pixel / 2), Number(version % 4n), version));
    }
    assert.equal(tile.changes.size, 4);
  }
  tile.clear();
  assert.equal(tile.changes.size, 0);
  assert.equal(tile.snapshot, null);
  assert.equal(tile.data, null);
});
