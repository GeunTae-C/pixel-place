import { validateMetadata, pixelsToRgba, Viewport, PointerGesture, ZOOM_LEVELS, MOVEMENT_STEP } from "./board-model.mjs";
import { TileLoader, REQUEST_TIMEOUT_MS } from "./tile-loader.mjs";
import { AuthSession } from "./auth-session.mjs";
import { PlacementController } from "./placement.mjs";
import { RealtimeConnection } from "./realtime.mjs";
import { parsePixelEvent } from "./exact-json.mjs";

// 공개 조회·보드 조작·인증·배치 UI 연결. 수락 변경은 loader의 공통 병합 경계로 전달
const API_BASE = "http://localhost:8080";
const authButton = document.querySelector("#auth-button");
const authState = document.querySelector("#auth-state");
const authNotice = document.querySelector("#auth-notice");
let authStatus = "signedOut";
let placement = null;
const auth = new AuthSession({
  apiBase: API_BASE,
  navigate: (url) => window.location.assign(url),
  replaceUrl: (url) => window.history.replaceState(window.history.state, "", url),
  onChange: ({ status, message }) => {
    authStatus = status;
    authState.textContent = status === "authenticated" ? "로그인됨" : status === "checking" ? "로그인 확인 중" : "로그인 전";
    authButton.textContent = status === "authenticated" ? "로그아웃" : "카카오 로그인";
    authButton.disabled = status === "checking";
    authNotice.textContent = message;
    authNotice.closest(".login-area").dataset.state = status;
    placement?.reconcileAuth();
  },
});
authButton.addEventListener("click", () => {
  if (authStatus === "authenticated") auth.logout();
  else auth.beginLogin();
});
const board = document.querySelector("#board");
const viewportElement = board.parentElement;
const context = board.getContext("2d");
const notice = document.querySelector("#connection-notice");
const connectionState = document.querySelector("#connection-state");
const realtimeState = document.querySelector("#realtime-state");
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
let realtime = null;
let pageActive = true;
let queriesAllowed = false;
let fallbackStarted = false;
const placeButton = document.querySelector("#place-button");
const placementNotice = document.querySelector("#placement-notice");
const cooldownNotice = document.querySelector("#cooldown-notice");
placement = new PlacementController({
  auth,
  apiBase: API_BASE,
  getViewGeneration: () => loader?.getGeneration(),
  onAccepted: (change, generation) => loader?.applyAccepted(change, generation),
  onChange: (state) => {
    placeButton.disabled = !state.canPlace;
    placeButton.textContent = state.pending ? "배치 요청 중…" : "픽셀 배치";
    placementNotice.textContent = state.message;
    placementNotice.dataset.state = state.status;
    if (state.remainingMillis > 0) {
      const seconds = Math.ceil(state.remainingMillis / 1000);
      cooldownNotice.textContent = `예상 남은 시간 ${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
    } else {
      cooldownNotice.textContent = state.cooldownKnown
        ? "예상 대기 시간이 끝났습니다. 최종 배치 가능 여부는 서버에서 확인합니다."
        : "남은 대기 시간은 배치 요청 시 서버에서 확인합니다.";
    }
  },
});

function updatePlacementContext() {
  placement.setContext({ metadata, selectedPixel: selected, selectedColor });
}
placeButton.addEventListener("click", () => placement.submit());
let placementTimer = setInterval(() => placement.tick(), 500);
updatePlacementContext();

function message(text, state, queryState = state) {
  notice.textContent = text;
  notice.parentElement.dataset.state = state;
  // 최신성 경고와 GET 결과는 별개. WS 단절 중 성공한 공개 조회를 실패로 표시하지 않음
  connectionState.textContent = queryState === "error" ? "조회 실패" : queryState === "loading" ? "조회 중" : "조회 성공";
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
  if (!loader || !pageActive) return;
  const entries = view.visibleTiles().map((tile) => loader.get(tile.key));
  const failed = entries.filter((entry) => entry?.state === "error");
  const loading = entries.filter((entry) => !entry || entry.state === "loading");
  const ready = entries.length - failed.length - loading.length;
  const state = realtime?.getState();
  const connected = state?.status === "connected";
  realtime?.setSynchronized(connected && entries.length > 0 && !failed.length && !loading.length);
  realtimeState.textContent = connected ? "실시간 연결됨"
    : state?.status === "exhausted" ? "실시간 미연결 · 재시도 소진"
      : state?.failureSeen ? "실시간 연결 끊김 · 재연결 중" : "실시간 연결 중";
  if (!queriesAllowed) {
    message("실시간 연결을 기다리는 중입니다. 연결 실패 확인 후에는 현재 보드를 공개 조회합니다.", "loading");
  } else if (failed.length) {
    message(`최신 상태 확인 필요 · 타일 ${failed.length}개 조회 실패${loading.length ? ` · ${loading.length}개 조회 중` : ""}. 다시 불러오기로 재시도하세요.`, "error");
  } else if (loading.length) {
    message(`${connected ? "화면 동기화 중" : "최신 상태 확인 필요"} · 현재 영역 ${ready}/${entries.length}개 조회 완료.`, "loading");
  } else if (!connected) {
    message(`최신 상태 확인 필요 · 현재 영역 ${ready}개 공개 조회 완료. ${state?.status === "exhausted" ? "자동 재시도가 끝났습니다. 다시 불러오기를 눌러 주세요." : "실시간 재연결을 시도합니다."}`, "error", "ready");
  } else {
    message(`현재 영역 ${ready}개 조회 완료. 좌표와 색상을 선택하세요.`, "ready");
  }
  if (state?.protocolError) notice.textContent = `수신 메시지 확인 실패 · ${notice.textContent}`;
  board.setAttribute("aria-busy", String(loading.length > 0));
  requestDraw();
}

function refreshView() {
  if (!view) { requestDraw(); return; }
  document.querySelector("#zoom-level").textContent = `${view.scale}배`;
  zoomOut.disabled = view.scale === ZOOM_LEVELS[0];
  zoomIn.disabled = view.scale === ZOOM_LEVELS.at(-1);
  if (pageActive && queriesAllowed) loader.setVisible(view.visibleTiles());
  else { updateLoadState(); requestDraw(); }
}

// 새 연결마다 기준을 버리고 현재 영역을 다시 조회. 실패 반복은 fallback GET 반복으로 연결하지 않음
function connectRealtime() {
  realtime = new RealtimeConnection({
    url: "ws://localhost:8080/ws",
    onOpen: () => {
      queriesAllowed = true;
      fallbackStarted = false;
      loader.reset(view.visibleTiles());
    },
    onMessage: (raw) => loader.applyChange(parsePixelEvent(raw, metadata)),
    onDisconnect: () => {
      if (!pageActive || fallbackStarted) return;
      fallbackStarted = true;
      queriesAllowed = true;
      loader.reset(view.visibleTiles());
    },
    onChange: updateLoadState,
  });
  realtime.start();
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
      updatePlacementContext();
    });
    paletteElement.append(button);
  });
}

async function loadMetadata() {
  if (!pageActive || metadataRequest) return;
  const controller = new AbortController();
  metadataRequest = controller;
  reloadButton.disabled = true;
  message("보드 정보를 불러오는 중입니다.", "loading");
  board.setAttribute("aria-busy", "true");
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE}/api/board`, { signal: controller.signal, credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error(`보드 조회 실패 (HTTP ${response.status})`);
    const result = validateMetadata(await response.json());
    if (!pageActive || metadataRequest !== controller || controller.signal.aborted) return;
    metadata = result;
    const { width, height } = viewportElement.getBoundingClientRect();
    view = new Viewport(metadata, width, height);
    loader = new TileLoader({ metadata, apiBase: API_BASE, onChange: updateLoadState });
    buildPalette();
    updatePlacementContext();
    document.querySelector("#board-description").textContent = `${metadata.boardSize.toLocaleString()} × ${metadata.boardSize.toLocaleString()} · 원본 타일 조회`;
    document.querySelectorAll("[data-move]").forEach((button) => { button.disabled = false; });
    resizeBoard();
    connectRealtime();
  } catch (error) {
    if (!pageActive || metadataRequest !== controller) return;
    const detail = error.name === "AbortError" ? "응답 시간이 초과되었습니다." : error.message;
    message(`${detail} API 실행·접속 상태를 확인한 뒤 다시 불러오세요.`, "error");
    board.setAttribute("aria-busy", "false");
    requestDraw();
  } finally {
    clearTimeout(timer);
    if (metadataRequest === controller) {
      metadataRequest = null;
      reloadButton.disabled = false;
    }
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
      updatePlacementContext();
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
  if (!pageActive) return;
  if (!metadata) { loadMetadata(); return; }
  const state = realtime.getState();
  if (state.status === "connected" || state.failureSeen) loader.reload({ coalesce: true });
  realtime.retry();
});

new ResizeObserver(resizeBoard).observe(viewportElement);
window.addEventListener("resize", resizeBoard);
window.addEventListener("pagehide", (event) => {
  pageActive = false;
  queriesAllowed = false;
  realtime?.suspend();
  loader?.invalidate();
  metadataRequest?.abort();
  metadataRequest = null;
  reloadButton.disabled = false;
  cancelGesture();
  clearInterval(placementTimer);
  placement.suspend();
  auth.suspend();
  if (!event.persisted) loader?.dispose();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted && !pageActive) {
    pageActive = true;
    fallbackStarted = false;
    auth.resume();
    placement.resume();
    clearInterval(placementTimer);
    placementTimer = setInterval(() => placement.tick(), 500);
    resizeBoard();
    if (metadata) realtime.resume();
    else loadMetadata();
  }
});
function reconcilePage() {
  if (!pageActive) return;
  auth.reconcile();
  placement.tick();
}
window.addEventListener("focus", reconcilePage);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") reconcilePage();
});
// 인증 실패와 무관하게 공개 보드 초기화 진행. 두 상태 안내의 수명도 분리
auth.initialize(window.location.href);
resizeBoard();
loadMetadata();
