// 격자 임계값·보이는 좌표·선택 대비의 렌더 호출 검증. 실제 픽셀 출력은 브라우저 증거에서 확인
import test from "node:test";
import assert from "node:assert/strict";
import { Viewport } from "../board-model.mjs";
import { drawBoardOverlays } from "../board-overlays.mjs";

const metadata = { boardSize: 8192, tileSize: 256, tileCountX: 32, tileCountY: 32 };
function capture(view, { enabled = true, selected = null, raster = { a: 1, d: 1 } } = {}) {
  const lines = [];
  const rectangles = [];
  const order = [];
  let start;
  let clip;
  const context = {
    save() {}, restore() {}, beginPath() {}, clip() {},
    getTransform() { return raster; },
    rect(...bounds) { clip = bounds; },
    moveTo(x, y) { start = { x, y }; },
    lineTo(x, y) { lines.push({ start, end: { x, y } }); },
    stroke() { order.push("grid"); assert.equal(this.lineWidth, 1); },
    strokeRect(...bounds) {
      order.push("selection");
      rectangles.push({ bounds, width: this.lineWidth, color: this.strokeStyle });
    },
  };
  drawBoardOverlays(context, view, selected, enabled);
  return { lines, rectangles, order, clip };
}

test("격자는 OFF와 8 CSS px 미만에서 숨기고 정확한 8부터 표시", () => {
  const view = new Viewport(metadata, 400, 300);
  for (const scale of [0.5, 1, 4, 7.999, 8, 16, 32]) {
    view.scale = scale;
    assert.equal(capture(view).lines.length > 0, scale >= 8);
    assert.equal(capture(view, { enabled: false }).lines.length, 0);
  }
});

test("이동·휠 확대·패널 폭 변경 뒤 격자는 보드 경계에서 반 래스터 픽셀 이내이며 클릭과 정렬", () => {
  const view = new Viewport(metadata, 800, 600);
  view.pan(13.5, -21.25);
  for (const width of [800, 1080, 800]) {
    view.resize(width, 561.5);
    for (const scale of [8, 16, 32]) {
      while (view.scale !== scale) view.zoom(Math.sign(scale - view.scale), 123.25, 215.5);
      const { lines, clip } = capture(view);
      assert.deepEqual(clip, [0, 0, width, view.height]);
      const vertical = lines.filter(({ start, end }) => start.x === end.x);
      const horizontal = lines.filter(({ start, end }) => start.y === end.y);
      assert.ok(lines.length <= Math.ceil(width / scale) + Math.ceil(view.height / scale) + 2);
      for (const [axis, values, extent, center] of [
        ["x", vertical, width, view.centerX], ["y", horizontal, view.height, view.centerY],
      ]) {
        for (const { start } of values) {
          // 화면 끝의 반올림된 중심까지 허용하되 실제 그리기 범위는 위 clip으로 제한
          assert.ok(start[axis] >= 0 && start[axis] <= extent + 0.5);
          const coordinate = center + (start[axis] - extent / 2) / scale;
          assert.ok(Math.abs(coordinate - Math.round(coordinate)) * scale <= 0.5 + 1e-9);
          assert.equal(start[axis], Math.round(start[axis]));
        }
        assert.equal(new Set(values.map(({ start }) => start[axis])).size, values.length);
        for (let i = 1; i < values.length; i += 1) assert.equal(values[i].start[axis] - values[i - 1].start[axis], scale);
      }
      const x = vertical[2].start.x + scale / 2;
      const y = horizontal[2].start.y + scale / 2;
      const selected = view.screenToBoard(x, y);
      const position = view.boardToScreen(selected.x, selected.y);
      const selection = capture(view, { selected }).rectangles[0];
      assert.deepEqual(selection.bounds, [position.x, position.y, scale, scale]);
      assert.ok(Math.abs(position.x - vertical[2].start.x) <= 0.5);
      assert.ok(Math.abs(position.y - horizontal[2].start.y) <= 0.5);
    }
  }
});

test("소수 CSS 크기와 DPR에서도 격자 중심은 내부 픽셀 경계에 맞추고 선 간격과 선택 좌표 보존", () => {
  for (const dpr of [1, 1.25, 2]) {
    const view = new Viewport(metadata, 1159.5, 814.5);
    const raster = { a: Math.round(view.width * dpr) / view.width, d: Math.round(view.height * dpr) / view.height };
    const selected = { x: 4096, y: 4096 };
    for (const scale of [8, 16, 32]) {
      view.scale = scale;
      for (const offset of [0, 0.25, -0.375]) {
        view.centerX = 4096 + offset;
        view.centerY = 4096 - offset;
        const result = capture(view, { raster, selected });
        for (const [axis, factor, center, extent] of [
          ["x", raster.a, view.centerX, view.width], ["y", raster.d, view.centerY, view.height],
        ]) {
          const values = result.lines.filter(({ start, end }) => start[axis] === end[axis]).map(({ start }) => start[axis]);
          const first = Math.ceil(center - extent / (2 * scale));
          const last = Math.floor(center + extent / (2 * scale));
          assert.equal(values.length, last - first + 1);
          for (let i = 0; i < values.length; i += 1) {
            const actual = values[i] * factor;
            const ideal = ((first + i - center) * scale + extent / 2) * factor;
            assert.ok(Math.abs(actual - Math.round(actual)) < 1e-9);
            assert.ok(Math.abs(actual - ideal) <= 0.5 + 1e-9);
            if (i) assert.ok(Math.abs(values[i] - values[i - 1] - scale) <= 1 / factor + 1e-9);
          }
          assert.equal(new Set(values).size, values.length);
        }
        const position = view.boardToScreen(selected.x, selected.y);
        assert.deepEqual(result.rectangles[0].bounds, [position.x, position.y, scale, scale]);
        assert.deepEqual(view.screenToBoard(position.x + scale / 2, position.y + scale / 2), selected);
      }
    }
  }
});

test("타일 경계는 한 번만 그리고 보드 가장자리와 화면 밖 선택은 잘라냄", () => {
  const view = new Viewport(metadata, 800, 600);
  view.centerX = 256;
  view.centerY = 256;
  const { lines } = capture(view);
  assert.equal(lines.filter(({ start, end }) => start.x === 400 && end.x === 400).length, 1);
  assert.equal(lines.filter(({ start, end }) => start.y === 300 && end.y === 300).length, 1);
  for (const edge of [0, metadata.boardSize]) {
    view.centerX = edge;
    view.centerY = edge;
    view.clamp();
    const result = capture(view);
    for (const { start, end } of result.lines) {
      assert.ok(start.x >= 0 && start.y >= 0 && end.x <= view.width && end.y <= view.height);
    }
  }
  assert.equal(capture(view, { selected: { x: 0, y: 0 } }).rectangles.length, 0);
  const small = new Viewport({ ...metadata, boardSize: 16 }, 800, 600);
  assert.deepEqual(capture(small).clip, [336, 236, 128, 128]);
});

test("격자 뒤에 밝고 어두운 선택 테두리를 그리며 격자 OFF에서도 유지", () => {
  const view = new Viewport(metadata, 800, 600);
  const selected = { x: 4096, y: 4096 };
  const enabled = capture(view, { selected });
  const disabled = capture(view, { selected, enabled: false });
  assert.deepEqual(enabled.order, ["grid", "selection", "selection"]);
  assert.deepEqual(disabled.order, ["selection", "selection"]);
  assert.deepEqual(disabled.rectangles, enabled.rectangles);
  assert.deepEqual(enabled.rectangles.map(({ width, color }) => ({ width, color })),
    [{ width: 3, color: "#fff" }, { width: 1, color: "#173b64" }]);
});
