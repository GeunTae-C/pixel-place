import { validateMetadata, pixelsToRgba, Viewport, PointerGesture, ZOOM_LEVELS, MOVEMENT_STEP } from "./board-model.mjs";
import { TileLoader, REQUEST_TIMEOUT_MS } from "./tile-loader.mjs";

// 공개 GET·탐색·선택만 연결. 로그인·배치·WS와 원본 snapshot 수정 책임 없음
const API_BASE = "http://localhost:8080";
const board = document.querySelector("#board");
const viewportElement = board.parentElement;
const context = board.getContext("2d");
const notice = document.querySelector("#connection-notice");
const connectionState = document.querySelector("#connection-state");
const reloadButton = document.querySelector("#reload");
const zoomIn = document.querySelector("#zoom-in");
const zoomOut = document.querySelector("#zoom-out");
const paletteElement = document.querySelector("#palette");
const gesture = new PointerGesture();
const images = new WeakMap();
let metadata = null;
let view = null;
let loader = null;
let selected = null;
let selectedColor = null;
let metadataRequest = null;
let frame = 0;

function message(text, state) {
  notice.textContent = text;
  notice.parentElement.dataset.state = state;
  connectionState.textContent = state === "error" ? "조회 실패" : state === "loading" ? "조회 중" : "조회 성공";
}

function requestDraw() {
  if (!frame) frame = requestAnimationFrame(() => { frame = 0; draw(); });
}

function tileImage(data) {
  if (!images.has(data)) {
    const image = document.createElement("canvas");
    image.width = metadata.tileSize;
    image.height = metadata.tileSize;
    image.getContext("2d").putImageData(new ImageData(pixelsToRgba(data.bytes, metadata.palette), image.width, image.height), 0, 0);
    images.set(data, image);
  }
  return images.get(data);
}

function draw() {
  const { width, height } = viewportElement.getBoundingClientRect();
  if (width <= 0 || height <= 0) return;
  context.setTransform(board.width / width, 0, 0, board.height / height, 0, 0);
  context.imageSmoothingEnabled = false;
  context.fillStyle = "#cbd2d9";
  context.fillRect(0, 0, width, height);
  if (!view) {
    context.fillStyle = "#596570";
    context.font = "14px system-ui";
    context.textAlign = "center";
    context.fillText("보드 조회를 기다리는 중", width / 2, height / 2);
    return;
  }
  for (const tile of view.visibleTiles()) {
    const entry = loader.get(tile.key);
    const position = view.boardToScreen(tile.tx * metadata.tileSize, tile.ty * metadata.tileSize);
    const size = metadata.tileSize * view.scale;
    if (entry?.data) {
      context.drawImage(tileImage(entry.data), position.x, position.y, size, size);
    } else {
      // 미조회·실패 영역을 기본 흰색으로 오인하지 않도록 채움과 대각선 표시
      context.fillStyle = entry?.state === "error" ? "#f4dad9" : "#e1e6ec";
      context.fillRect(position.x, position.y, size, size);
      context.strokeStyle = entry?.state === "error" ? "#c6908d" : "#c0cad4";
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(position.x, position.y);
      context.lineTo(position.x + size, position.y + size);
      context.moveTo(position.x + size, position.y);
      context.lineTo(position.x, position.y + size);
      context.stroke();
      context.strokeRect(position.x, position.y, size, size);
    }
    if (entry?.data && entry.state !== "ready") {
      context.fillStyle = entry.state === "error" ? "rgba(180, 40, 40, .13)" : "rgba(52, 104, 158, .1)";
      context.fillRect(position.x, position.y, size, size);
    }
  }
  if (selected) {
    const position = view.boardToScreen(selected.x, selected.y);
    if (position.x + view.scale > 0 && position.y + view.scale > 0 && position.x < width && position.y < height) {
      context.lineWidth = 3;
      context.strokeStyle = "#fff";
      context.strokeRect(position.x, position.y, view.scale, view.scale);
      context.lineWidth = 1;
      context.strokeStyle = "#174e96";
      context.strokeRect(position.x, position.y, view.scale, view.scale);
    }
  }
}

function updateLoadState() {
  if (!loader) return;
  const entries = view.visibleTiles().map((tile) => loader.get(tile.key));
  const failed = entries.filter((entry) => entry?.state === "error");
  const loading = entries.filter((entry) => !entry || entry.state === "loading");
  const ready = entries.length - failed.length - loading.length;
  if (failed.length) {
    message(`타일 ${failed.length}개 조회 실패${loading.length ? ` · ${loading.length}개 조회 중` : ""}. ${failed[0].error} 다시 불러오기로 재시도하세요. 기존 화면이 남은 영역은 최신 조회에 실패했습니다.`, "error");
  } else if (loading.length) {
    message(`현재 영역 ${ready}/${entries.length}개 조회 완료 · ${loading.length}개 불러오는 중.`, "loading");
  } else {
    message(`현재 영역 ${ready}개 조회 완료. 좌표와 색상을 선택하세요.`, "ready");
  }
  board.setAttribute("aria-busy", String(loading.length > 0));
  requestDraw();
}

function refreshView() {
  if (!view) { requestDraw(); return; }
  document.querySelector("#zoom-level").textContent = `${view.scale}배`;
  zoomOut.disabled = view.scale === ZOOM_LEVELS[0];
  zoomIn.disabled = view.scale === ZOOM_LEVELS.at(-1);
  loader.setVisible(view.visibleTiles());
}

function resizeBoard() {
  const { width, height } = viewportElement.getBoundingClientRect();
  if (width <= 0 || height <= 0) return;
  const ratio = window.devicePixelRatio || 1;
  board.width = Math.max(1, Math.round(width * ratio));
  board.height = Math.max(1, Math.round(height * ratio));
  view?.resize(width, height);
  refreshView();
}

function buildPalette() {
  paletteElement.replaceChildren();
  paletteElement.className = "palette-grid";
  metadata.palette.forEach((color, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "palette-color";
    button.style.backgroundColor = color;
    button.style.setProperty("--color", color);
    button.title = `${index}번 ${color}`;
    button.setAttribute("aria-label", `${index}번 색상 ${color}`);
    button.setAttribute("aria-pressed", "false");
    button.addEventListener("click", () => {
      if (selectedColor !== null) paletteElement.children[selectedColor].setAttribute("aria-pressed", "false");
      selectedColor = index;
      button.setAttribute("aria-pressed", "true");
      const chip = document.createElement("span");
      chip.className = "color-chip";
      chip.style.backgroundColor = color;
      document.querySelector("#selected-color").replaceChildren(chip, `${index} · ${color}`);
    });
    paletteElement.append(button);
  });
}

async function loadMetadata() {
  if (metadataRequest) return;
  const controller = new AbortController();
  metadataRequest = controller;
  reloadButton.disabled = true;
  message("보드 정보를 불러오는 중입니다.", "loading");
  board.setAttribute("aria-busy", "true");
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE}/api/board`, { signal: controller.signal, credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error(`보드 조회 실패 (HTTP ${response.status})`);
    metadata = validateMetadata(await response.json());
    const { width, height } = viewportElement.getBoundingClientRect();
    view = new Viewport(metadata, width, height);
    loader = new TileLoader({ metadata, apiBase: API_BASE, onChange: updateLoadState });
    buildPalette();
    document.querySelector("#board-description").textContent = `${metadata.boardSize.toLocaleString()} × ${metadata.boardSize.toLocaleString()} · 원본 타일 조회`;
    document.querySelectorAll("[data-move]").forEach((button) => { button.disabled = false; });
    resizeBoard();
  } catch (error) {
    const detail = error.name === "AbortError" ? "응답 시간이 초과되었습니다." : error.message;
    message(`${detail} API 실행·접속 상태를 확인한 뒤 다시 불러오세요.`, "error");
    board.setAttribute("aria-busy", "false");
    requestDraw();
  } finally {
    clearTimeout(timer);
    metadataRequest = null;
    reloadButton.disabled = false;
  }
}

function point(event) {
  const rect = board.getBoundingClientRect();
  return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

function cancelGesture() {
  const id = gesture.current?.id;
  gesture.cancel();
  board.classList.remove("dragging");
  if (id !== undefined && board.hasPointerCapture(id)) board.releasePointerCapture(id);
}

board.addEventListener("pointerdown", (event) => {
  if (!view || event.button !== 0 || !event.isPrimary) return;
  cancelGesture();
  board.focus({ preventScroll: true });
  const position = point(event);
  gesture.start(event.pointerId, position.x, position.y);
  board.setPointerCapture(event.pointerId);
  event.preventDefault();
});
board.addEventListener("pointermove", (event) => {
  const position = point(event);
  const movement = gesture.move(event.pointerId, position.x, position.y);
  if (movement?.dragging) {
    board.classList.add("dragging");
    view.pan(-movement.dx, -movement.dy);
    refreshView();
  }
});
board.addEventListener("pointerup", (event) => {
  const position = point(event);
  const result = gesture.finish(event.pointerId, position.x, position.y);
  if (!result) return;
  if (result.click) {
    const coordinate = view.screenToBoard(result.x, result.y);
    if (coordinate) {
      selected = coordinate;
      document.querySelector("#selected-coordinate").textContent = `(${selected.x}, ${selected.y})`;
    }
  } else {
    view.pan(-result.dx, -result.dy);
  }
  board.classList.remove("dragging");
  if (board.hasPointerCapture(event.pointerId)) board.releasePointerCapture(event.pointerId);
  refreshView();
});
board.addEventListener("pointercancel", cancelGesture);
board.addEventListener("lostpointercapture", cancelGesture);
window.addEventListener("blur", cancelGesture);
board.addEventListener("blur", cancelGesture);

const directions = { left: [-1, 0], up: [0, -1], down: [0, 1], right: [1, 0] };
function move(direction) {
  if (!view) return;
  cancelGesture();
  const [x, y] = directions[direction];
  view.pan(x * MOVEMENT_STEP, y * MOVEMENT_STEP);
  refreshView();
}
document.querySelectorAll("[data-move]").forEach((button) => button.addEventListener("click", () => move(button.dataset.move)));
board.addEventListener("keydown", (event) => {
  const direction = { ArrowLeft: "left", ArrowUp: "up", ArrowDown: "down", ArrowRight: "right" }[event.key];
  if (document.activeElement === board && view && direction && !event.altKey && !event.ctrlKey && !event.metaKey) {
    event.preventDefault();
    move(direction);
  }
});

function zoom(direction, x, y) {
  if (!view) return;
  cancelGesture();
  view.zoom(direction, x, y);
  refreshView();
}
zoomIn.addEventListener("click", () => zoom(1));
zoomOut.addEventListener("click", () => zoom(-1));
board.addEventListener("wheel", (event) => {
  if (!view || event.deltaY === 0) return;
  event.preventDefault();
  const position = point(event);
  zoom(event.deltaY < 0 ? 1 : -1, position.x, position.y);
}, { passive: false });
reloadButton.addEventListener("click", () => {
  if (metadata) loader.reload();
  else loadMetadata();
});

new ResizeObserver(resizeBoard).observe(viewportElement);
window.addEventListener("resize", resizeBoard);
window.addEventListener("pagehide", (event) => {
  cancelGesture();
  // 이력 캐시에 보존되는 페이지는 같은 조회기로 복귀하므로 영구 폐기하지 않음
  if (event.persisted) return;
  metadataRequest?.abort();
  loader?.dispose();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) resizeBoard();
});
resizeBoard();
loadMetadata();
