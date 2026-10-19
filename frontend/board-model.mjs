// Canvas 내부 해상도와 분리한 CSS 좌표·원본 타일 해석. 서버 값 검증 뒤에만 화면 모델 생성
export const ZOOM_LEVELS = Object.freeze([0.5, 1, 2, 4, 8, 16, 32]);
export const MOVEMENT_STEP = 64;
export const DRAG_THRESHOLD = 4;

export function validateMetadata(value) {
  const positive = (number) => Number.isSafeInteger(number) && number > 0;
  const fields = ["boardSize", "tileSize", "tileCountX", "tileCountY", "paletteSize", "overviewRefreshSeconds"];
  if (!value || fields.some((field) => !positive(value[field])) || value.z !== 0
      || value.tileSize > value.boardSize || !Number.isSafeInteger(value.tileSize ** 2)
      || value.tileCountX * value.tileSize !== value.boardSize
      || value.tileCountY * value.tileSize !== value.boardSize
      || value.paletteSize > 256 || !Array.isArray(value.palette)
      || value.palette.length !== value.paletteSize
      || value.palette.some((color) => typeof color !== "string" || !/^#[0-9a-f]{6}$/i.test(color))) {
    // 불완전한 메타데이터를 기본값으로 대체하면 조회 범위와 색상 해석이 달라짐
    throw new Error("보드 메타데이터의 크기·타일·팔레트 값이 올바르지 않습니다.");
  }
  return Object.freeze({ ...value, palette: Object.freeze([...value.palette]) });
}

export function validateTileResponse(buffer, version, metadata) {
  if (typeof version !== "string" || !/^(0|[1-9]\d*)$/.test(version)
      || version.length > 19 || BigInt(version) > 9223372036854775807n) {
    // 노출되지 않은 헤더와 Java long 범위 밖 값은 정상 version 0으로 보정 금지
    throw new Error("타일 버전 헤더가 없거나 올바르지 않습니다.");
  }
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength !== metadata.tileSize ** 2) {
    throw new Error("타일 바이트 길이가 보드 설정과 다릅니다.");
  }
  const bytes = new Uint8Array(buffer);
  if (bytes.some((index) => index >= metadata.paletteSize)) {
    throw new Error("타일에 팔레트 범위 밖의 색상이 있습니다.");
  }
  return { bytes, version };
}

// 행 우선 원본 인덱스를 같은 순서의 불투명 RGBA로 변환. 선택·축소 결과를 원본에 쓰지 않음
export function pixelsToRgba(bytes, palette) {
  const colors = palette.map((hex) => Number.parseInt(hex.slice(1), 16));
  const rgba = new Uint8ClampedArray(bytes.length * 4);
  bytes.forEach((index, offset) => {
    const color = colors[index];
    rgba.set([(color >> 16) & 255, (color >> 8) & 255, color & 255, 255], offset * 4);
  });
  return rgba;
}

export class Viewport {
  constructor(metadata, width, height) {
    this.metadata = metadata;
    this.width = width;
    this.height = height;
    this.scale = 8;
    this.centerX = metadata.boardSize / 2;
    this.centerY = metadata.boardSize / 2;
    this.clamp();
  }

  clamp() {
    const clampAxis = (center, extent) => {
      const half = extent / (2 * this.scale);
      return half >= this.metadata.boardSize / 2 ? this.metadata.boardSize / 2
        : Math.max(half, Math.min(this.metadata.boardSize - half, center));
    };
    this.centerX = clampAxis(this.centerX, this.width);
    this.centerY = clampAxis(this.centerY, this.height);
  }

  resize(width, height) {
    this.width = width;
    this.height = height;
    this.clamp();
  }

  pan(dxCss, dyCss) {
    this.centerX += dxCss / this.scale;
    this.centerY += dyCss / this.scale;
    this.clamp();
  }

  zoom(direction, anchorX = this.width / 2, anchorY = this.height / 2) {
    const index = ZOOM_LEVELS.indexOf(this.scale);
    const next = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1, index + Math.sign(direction)))];
    this.centerX += (anchorX - this.width / 2) * (1 / this.scale - 1 / next);
    this.centerY += (anchorY - this.height / 2) * (1 / this.scale - 1 / next);
    this.scale = next;
    this.clamp();
  }

  screenToBoard(x, y) {
    const boardX = this.centerX + (x - this.width / 2) / this.scale;
    const boardY = this.centerY + (y - this.height / 2) / this.scale;
    if (x < 0 || y < 0 || x >= this.width || y >= this.height
        || boardX < 0 || boardY < 0 || boardX >= this.metadata.boardSize || boardY >= this.metadata.boardSize) return null;
    return { x: Math.floor(boardX), y: Math.floor(boardY) };
  }

  boardToScreen(x, y) {
    return { x: (x - this.centerX) * this.scale + this.width / 2,
      y: (y - this.centerY) * this.scale + this.height / 2 };
  }

  visibleTiles() {
    const { tileSize, tileCountX, tileCountY } = this.metadata;
    const firstX = Math.max(0, Math.floor((this.centerX - this.width / (2 * this.scale)) / tileSize));
    const firstY = Math.max(0, Math.floor((this.centerY - this.height / (2 * this.scale)) / tileSize));
    const lastX = Math.min(tileCountX - 1, Math.ceil((this.centerX + this.width / (2 * this.scale)) / tileSize) - 1);
    const lastY = Math.min(tileCountY - 1, Math.ceil((this.centerY + this.height / (2 * this.scale)) / tileSize) - 1);
    const tiles = [];
    for (let ty = firstY; ty <= lastY; ty += 1) {
      for (let tx = firstX; tx <= lastX; tx += 1) tiles.push({ tx, ty, key: `${tx},${ty}` });
    }
    return tiles;
  }
}

// 드래그 판정은 누적 이동 거리가 아닌 시작점과의 CSS 거리. 한번 판정되면 클릭 복귀 금지
export class PointerGesture {
  start(id, x, y) {
    this.current = { id, startX: x, startY: y, x, y, dragging: false };
  }

  move(id, x, y) {
    const gesture = this.current;
    if (!gesture || gesture.id !== id) return null;
    const wasDragging = gesture.dragging;
    gesture.dragging ||= Math.hypot(x - gesture.startX, y - gesture.startY) >= DRAG_THRESHOLD;
    const result = { dx: x - (wasDragging ? gesture.x : gesture.startX),
      dy: y - (wasDragging ? gesture.y : gesture.startY), dragging: gesture.dragging };
    gesture.x = x;
    gesture.y = y;
    return result;
  }

  finish(id, x, y) {
    const movement = this.move(id, x, y);
    if (!movement) return null;
    this.current = null;
    return { click: !movement.dragging, x, y, ...movement };
  }

  cancel(id) {
    if (id === undefined || this.current?.id === id) this.current = null;
  }
}
