// 서버 계약·CSS 좌표·입력 경계를 작은 합성 입력으로 검증. 실제 API 연결 증거 대체 금지
import test from "node:test";
import assert from "node:assert/strict";
import {
  PointerGesture,
  Viewport,
  pixelsToRgba,
  validateMetadata,
  validateTileResponse,
} from "../board-model.mjs";

const metadata = {
  boardSize: 8192,
  tileSize: 256,
  z: 0,
  tileCountX: 32,
  tileCountY: 32,
  paletteSize: 4,
  palette: ["#FFFFFF", "#FF0000", "#00FF00", "#0000FF"],
  overviewRefreshSeconds: 10,
};

test("메타데이터의 크기와 팔레트 순서를 서버 값 그대로 수용", () => {
  const result = validateMetadata(metadata);
  assert.equal(result.boardSize, 8192);
  assert.equal(result.tileSize, 256);
  assert.deepEqual(result.palette, metadata.palette);
});

test("누락·잘못된 메타데이터와 서로 맞지 않는 타일 개수를 거부", () => {
  const invalid = [
    null,
    {},
    { ...metadata, boardSize: 0 },
    { ...metadata, tileSize: 2.5 },
    { ...metadata, z: 1 },
    { ...metadata, tileCountX: 31 },
    { ...metadata, tileCountY: 0 },
    { ...metadata, paletteSize: 3 },
    { ...metadata, palette: ["#FFFFFF", "red", "#00FF00", "#0000FF"] },
    { ...metadata, overviewRefreshSeconds: undefined },
  ];
  for (const input of invalid) assert.throws(() => validateMetadata(input));
});

test("타일 바이트와 같은 응답의 Java long 버전을 정밀도 손실 없이 보존", () => {
  const small = { ...metadata, tileSize: 2 };
  for (const version of ["0", "9007199254740993", "9223372036854775807"]) {
    const result = validateTileResponse(Uint8Array.from([0, 1, 2, 3]).buffer, version, small);
    assert.deepEqual([...result.bytes], [0, 1, 2, 3]);
    assert.equal(String(result.version), version);
  }
});

test("잘못된 바이트 길이·팔레트 인덱스·누락 또는 비정수 버전은 정상 타일 설치 금지", () => {
  const small = { ...metadata, tileSize: 2 };
  const valid = Uint8Array.from([0, 1, 2, 3]).buffer;
  for (const version of [null, "", "-1", "1.5", "1e3", "NaN", "9223372036854775808"]) {
    assert.throws(() => validateTileResponse(valid, version, small));
  }
  assert.throws(() => validateTileResponse(new ArrayBuffer(3), "0", small));
  assert.throws(() => validateTileResponse(new ArrayBuffer(5), "0", small));
  assert.throws(() => validateTileResponse(Uint8Array.from([0, 1, 4, 3]).buffer, "0", small));
});

test("서로 다른 색의 합성 2×2 타일을 행 우선 팔레트 인덱스로 해석", () => {
  const bytes = Uint8Array.from([1, 2, 3, 0]);
  const before = bytes.slice();
  const rgba = pixelsToRgba(bytes, metadata.palette);
  assert.deepEqual([...rgba], [
    255, 0, 0, 255, 0, 255, 0, 255,
    0, 0, 255, 255, 255, 255, 255, 255,
  ]);
  assert.deepEqual(bytes, before);
});

test("첫 뷰포트는 보드 중앙 8배이며 좌표 변환은 CSS 표시 크기 기준", () => {
  const view = new Viewport(metadata, 800, 600);
  assert.equal(view.scale, 8);
  assert.equal(view.centerX, 4096);
  assert.equal(view.centerY, 4096);
  assert.deepEqual(view.screenToBoard(400, 300), { x: 4096, y: 4096 });
  assert.deepEqual(view.screenToBoard(0, 0), { x: 4046, y: 4058 });
  // DPR=2인 내부 1600×1200 Canvas여도 모델 입력은 동일한 CSS 800×600 좌표
  assert.deepEqual(view.screenToBoard(408, 308), { x: 4097, y: 4097 });
  assert.deepEqual(view.boardToScreen(4097, 4097), { x: 408, y: 308 });
});

test("방향 이동의 64 CSS px는 현재 배율에 따른 보드 좌표 이동량", () => {
  const view = new Viewport(metadata, 800, 600);
  view.pan(64, -64);
  assert.equal(view.centerX, 4104);
  assert.equal(view.centerY, 4088);
  assert.deepEqual(view.screenToBoard(400, 300), { x: 4104, y: 4088 });
  view.zoom(-1);
  view.pan(64, 64);
  assert.equal(view.centerX, 4120);
  assert.equal(view.centerY, 4104);
});

test("현재 표시 범위의 일부 타일만 포함하고 정확한 끝 경계의 다음 타일은 제외", () => {
  const view = new Viewport(metadata, 256, 256);
  view.scale = 1;
  view.centerX = 128;
  view.centerY = 128;
  assert.deepEqual(view.visibleTiles().map(({ key }) => key), ["0,0"]);
  view.pan(1, 1);
  assert.deepEqual(new Set(view.visibleTiles().map(({ key }) => key)), new Set(["0,0", "1,0", "0,1", "1,1"]));
  assert.ok(view.visibleTiles().length < 1024);
});

test("보드 네 모서리와 타일 경계는 유효한 정수 좌표이며 범위 밖 타일 요청 없음", () => {
  for (const [dx, dy, x, y, sx, sy] of [
    [-1e6, -1e6, 0, 0, 0, 0],
    [1e6, -1e6, 8191, 0, 799, 0],
    [-1e6, 1e6, 0, 8191, 0, 599],
    [1e6, 1e6, 8191, 8191, 799, 599],
  ]) {
    const view = new Viewport(metadata, 800, 600);
    view.pan(dx, dy);
    assert.deepEqual(view.screenToBoard(sx, sy), { x, y });
    for (const tile of view.visibleTiles()) {
      assert.ok(tile.tx >= 0 && tile.tx < 32 && tile.ty >= 0 && tile.ty < 32);
    }
  }
  const view = new Viewport(metadata, 800, 600);
  view.pan(-1e6, -1e6);
  view.zoom(-1);
  view.zoom(-1);
  view.zoom(-1);
  assert.deepEqual(view.screenToBoard(255, 255), { x: 255, y: 255 });
  assert.deepEqual(view.screenToBoard(256, 256), { x: 256, y: 256 });
});

test("표시 영역보다 작은 보드 축은 가운데 배치하며 여백 클릭은 무시", () => {
  const small = { ...metadata, boardSize: 32, tileSize: 16, tileCountX: 2, tileCountY: 2 };
  const view = new Viewport(small, 400, 300);
  view.pan(1e6, -1e6);
  assert.equal(view.centerX, 16);
  assert.equal(view.centerY, 16);
  assert.deepEqual(view.screenToBoard(72, 22), { x: 0, y: 0 });
  assert.deepEqual(view.screenToBoard(327, 277), { x: 31, y: 31 });
  for (const [x, y] of [[71, 22], [328, 22], [72, 21], [72, 278]]) {
    assert.equal(view.screenToBoard(x, y), null);
  }
});

test("배율은 0.5→1→2→4→8→16→32 단계와 상하한을 유지", () => {
  const view = new Viewport(metadata, 800, 600);
  const descending = [];
  for (let i = 0; i < 5; i++) {
    view.zoom(-1);
    descending.push(view.scale);
  }
  assert.deepEqual(descending, [4, 2, 1, 0.5, 0.5]);
  const ascending = [];
  for (let i = 0; i < 7; i++) {
    view.zoom(1);
    ascending.push(view.scale);
  }
  assert.deepEqual(ascending, [1, 2, 4, 8, 16, 32, 32]);
});

test("동일 CSS 크기에서 0.5배는 1배의 가로·세로 각각 2배 범위", () => {
  const view = new Viewport(metadata, 800, 600);
  view.scale = 1;
  const start1 = view.screenToBoard(0, 0);
  const end1 = view.screenToBoard(799, 599);
  view.zoom(-1);
  const startHalf = view.screenToBoard(0, 0);
  const endHalf = view.screenToBoard(799, 599);
  assert.equal(endHalf.x - startHalf.x, 2 * (end1.x - start1.x));
  assert.equal(endHalf.y - startHalf.y, 2 * (end1.y - start1.y));
});

test("휠은 마우스 위치, 버튼은 화면 중앙을 기준으로 배율 변경", () => {
  const view = new Viewport(metadata, 800, 600);
  const underPointer = view.screenToBoard(128, 112);
  view.zoom(1, 128, 112);
  assert.deepEqual(view.screenToBoard(128, 112), underPointer);
  view.zoom(-1, 128, 112);
  assert.deepEqual(view.screenToBoard(128, 112), underPointer);
  const center = { x: view.centerX, y: view.centerY };
  view.zoom(1);
  assert.equal(view.centerX, center.x);
  assert.equal(view.centerY, center.y);
});

test("상단 높이 또는 창 크기 변경 뒤 배율·중앙·선택한 실제 좌표 보존", () => {
  const view = new Viewport(metadata, 800, 600);
  view.pan(64, 64);
  const selection = view.screenToBoard(416, 316);
  view.resize(1024, 400);
  assert.equal(view.scale, 8);
  assert.equal(view.centerX, 4104);
  assert.equal(view.centerY, 4104);
  assert.deepEqual(view.boardToScreen(selection.x, selection.y), { x: 528, y: 216 });
  view.pan(2000, 2000);
  const hidden = view.boardToScreen(selection.x, selection.y);
  assert.ok(hidden.x < 0 && hidden.y < 0);
  view.pan(-2000, -2000);
  assert.deepEqual(view.boardToScreen(selection.x, selection.y), { x: 528, y: 216 });
});

test("경계에서 배율 변경과 resize는 보드 경계 제한을 우선", () => {
  const view = new Viewport(metadata, 800, 600);
  view.pan(-1e6, -1e6);
  view.zoom(-1, 799, 599);
  assert.deepEqual(view.screenToBoard(0, 0), { x: 0, y: 0 });
  view.resize(1000, 800);
  assert.deepEqual(view.screenToBoard(0, 0), { x: 0, y: 0 });
});

test("4 CSS px 미만은 클릭이며 정확한 4px와 대각선 3·3 이동은 드래그", () => {
  for (const [x, y, click] of [[3.99, 0, true], [4, 0, false], [3, 3, false]]) {
    const gesture = new PointerGesture();
    gesture.start(1, 0, 0);
    gesture.move(1, x, y);
    assert.equal(gesture.finish(1, x, y).click, click);
  }
});

test("한 번 드래그가 되면 시작점으로 돌아와도 선택 클릭 발생 금지", () => {
  const gesture = new PointerGesture();
  gesture.start(7, 20, 20);
  gesture.move(7, 24, 20);
  gesture.move(7, 20, 20);
  assert.equal(gesture.finish(7, 20, 20).click, false);
});

test("마지막 pointermove가 생략돼도 pointerup 위치가 4px 이상이면 드래그", () => {
  const gesture = new PointerGesture();
  gesture.start(1, 10, 10);
  assert.equal(gesture.finish(1, 14, 10).click, false);
});

test("Canvas 밖 종료·취소·다른 pointer는 잔여 드래그나 선택을 만들지 않음", () => {
  const gesture = new PointerGesture();
  gesture.start(1, 10, 10);
  assert.equal(gesture.move(2, 100, 100), null);
  assert.equal(gesture.finish(2, 100, 100), null);
  gesture.move(1, -100, -100);
  assert.equal(gesture.finish(1, -100, -100).click, false);
  assert.equal(gesture.move(1, 50, 50), null);
  gesture.start(3, 10, 10);
  gesture.cancel(3);
  assert.equal(gesture.finish(3, 10, 10), null);
  gesture.start(4, 10, 10);
  assert.equal(gesture.finish(4, 10, 10).click, true);
});
