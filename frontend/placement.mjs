// 사용자 클릭 하나의 인증·응답 소유권과 예상 대기 시간 담당. 자동 POST 재시도·저장 복원 책임 없음
import { validateMetadata } from "./board-model.mjs";
import { parsePixelJson } from "./exact-json.mjs";

export const PLACEMENT_REQUEST_TIMEOUT_MS = 15000;
export const PLACEMENT_COOLDOWN_MS = 180000;
const UNKNOWN_MESSAGE = "배치 요청의 처리 여부를 확인하지 못했습니다. 보드를 다시 불러와 현재 상태를 확인하세요. 같은 색으로 보여도 이 요청의 성공을 확정할 수 없습니다. 다시 배치하려면 결과를 확인한 뒤 직접 버튼을 눌러 주세요.";
const INITIAL_MESSAGE = "로그인한 뒤 좌표와 색상을 선택해 픽셀을 배치하세요.";
const COOLDOWN_UNKNOWN = "쿨다운으로 배치가 거절되었습니다. 남은 대기 시간은 확인하지 못했습니다. 잠시 후 직접 다시 요청해 주세요.";

function validSelection(metadata, pixel, color) {
  return Boolean(metadata && pixel && Number.isInteger(pixel.x) && pixel.x >= 0 && pixel.x < metadata.boardSize
    && Number.isInteger(pixel.y) && pixel.y >= 0 && pixel.y < metadata.boardSize
    && Number.isInteger(color) && color >= 0 && color < metadata.paletteSize);
}

export function parseAcceptedPlacement(raw, payload, metadata) {
  const body = parsePixelJson(raw);
  if (!body || body.accepted !== true || !validSelection(metadata, body, body.color)
      || body.x !== payload.x || body.y !== payload.y || body.color !== payload.color
      || typeof body.eventSeq !== "bigint" || typeof body.tileVersion !== "bigint") {
    // 요청과 대조되지 않은 성공은 실제 픽셀이나 예상 쿨다운으로 설치 금지
    throw new Error("배치 수락 응답 확인 불가");
  }
  return Object.freeze({ x: body.x, y: body.y, color: body.color,
    eventSeq: body.eventSeq, tileVersion: body.tileVersion });
}

export class PlacementController {
  constructor({ auth, apiBase = "http://localhost:8080", onChange = () => {}, onAccepted = () => {},
    fetchImpl = (...args) => fetch(...args), now = () => Date.now(), getViewGeneration = () => null,
    setTimer = (...args) => setTimeout(...args), clearTimer = (...args) => clearTimeout(...args) } = {}) {
    this.auth = auth;
    this.apiBase = apiBase;
    this.onChange = onChange;
    this.onAccepted = onAccepted;
    this.getViewGeneration = getViewGeneration;
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.metadata = null;
    this.selectedPixel = null;
    this.selectedColor = null;
    this.request = null;
    this.nextRequestId = 0;
    this.sessionId = auth.getSessionId();
    this.cooldownEndsAt = null;
    this.active = true;
    this.status = "idle";
    this.message = INITIAL_MESSAGE;
  }

  getState() {
    const remainingMillis = this.cooldownEndsAt === null ? null : Math.max(0, this.cooldownEndsAt - this.now());
    return Object.freeze({ status: this.status, message: this.message, pending: Boolean(this.request),
      cooldownKnown: this.cooldownEndsAt !== null, cooldownEndsAt: this.cooldownEndsAt, remainingMillis,
      canPlace: this.active && !this.request && Boolean(this.auth.getSessionId())
        && validSelection(this.metadata, this.selectedPixel, this.selectedColor) && !(remainingMillis > 0) });
  }

  publish(status = this.status, message = this.message) {
    this.status = status;
    this.message = message;
    const state = this.getState();
    this.onChange(state);
    return state;
  }

  setContext({ metadata, selectedPixel, selectedColor }) {
    try { this.metadata = validateMetadata(metadata); } catch { this.metadata = null; }
    this.selectedPixel = selectedPixel ? { x: selectedPixel.x, y: selectedPixel.y } : null;
    this.selectedColor = selectedColor;
    return this.publish();
  }

  // 인증 onChange에서 호출 가능. Access 재검사를 다시 호출하지 않아 publish 재귀 방지
  reconcileAuth() {
    if (!this.active) return this.getState();
    const sessionId = this.auth.getSessionId();
    if (sessionId !== this.sessionId) {
      const wasPending = Boolean(this.request);
      const wasCooldown = this.request?.responseStatus === 429;
      this.cancelRequest();
      this.sessionId = sessionId;
      this.cooldownEndsAt = null;
      if (wasCooldown) return this.publish("cooldown", COOLDOWN_UNKNOWN);
      return this.publish(wasPending ? "unknown" : "idle", wasPending ? UNKNOWN_MESSAGE : INITIAL_MESSAGE);
    }
    return this.publish();
  }

  // 표시값은 종료 시각으로 재계산. 0초나 focus가 POST 시작점이 되지 않음
  tick() { return this.publish(); }

  owns(request) {
    if (!this.active || this.request !== request) return false;
    const current = this.auth.isCurrentSession(request.sessionId);
    return current && this.active && this.request === request;
  }

  cancelRequest() {
    const request = this.request;
    this.request = null;
    if (request) {
      this.clearTimer(request.timer);
      request.cancel();
      request.controller.abort();
    }
  }

  // 선택·Access·세션을 클릭 시점에 고정. 전송 완료까지 단일 소유자만 응답과 타이머 갱신 가능
  async submit() {
    if (!this.active || this.request) return false;
    const access = this.auth.acquireAccess();
    this.reconcileAuth();
    if (!access) {
      this.publish("invalid", "픽셀을 배치하려면 카카오로 로그인해 주세요.");
      return false;
    }
    if (!validSelection(this.metadata, this.selectedPixel, this.selectedColor)) {
      this.publish("invalid", "정상 보드 조회 후 좌표와 색상을 모두 선택해 주세요.");
      return false;
    }
    if (this.cooldownEndsAt > this.now()) {
      this.publish("cooldown", "예상 대기 시간이 남아 있습니다. 타이머가 끝난 뒤 직접 배치해 주세요.");
      return false;
    }
    const request = { id: ++this.nextRequestId, payload: Object.freeze({ ...this.selectedPixel, color: this.selectedColor }),
      metadata: this.metadata, viewGeneration: this.getViewGeneration(), sessionId: access.sessionId, controller: new AbortController(), startedAt: this.now() };
    this.request = request;
    const cancelled = new Promise((_, reject) => { request.cancel = () => reject(new Error("요청 소유권 해제")); });
    const timeout = new Promise((_, reject) => {
      request.timer = this.setTimer(() => {
        reject(new Error("배치 확인 기한 초과"));
        request.controller.abort();
      }, PLACEMENT_REQUEST_TIMEOUT_MS);
    });
    this.publish("sending", "선택한 픽셀을 서버에 배치 요청 중입니다.");
    try {
      const read = async () => {
        const response = await this.fetchImpl(`${this.apiBase}/api/pixels`, {
          method: "POST", credentials: "omit", cache: "no-store", signal: request.controller.signal,
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${access.accessToken}` },
          body: JSON.stringify(request.payload),
        });
        request.responseStatus = response.status;
        let raw = null;
        if (response.status === 200 || response.status === 429) raw = await response.text();
        return { status: response.status, raw };
      };
      const result = await Promise.race([read(), timeout, cancelled]);
      if (!this.owns(request)) return false;
      if (this.now() >= request.startedAt + PLACEMENT_REQUEST_TIMEOUT_MS) throw new Error("배치 확인 기한 초과");
      if (result.status === 200) {
        const change = parseAcceptedPlacement(result.raw, request.payload, request.metadata);
        if (!this.owns(request)) return false;
        if (this.now() >= request.startedAt + PLACEMENT_REQUEST_TIMEOUT_MS) throw new Error("배치 확인 기한 초과");
        this.cooldownEndsAt = this.now() + PLACEMENT_COOLDOWN_MS;
        try { this.onAccepted(change, request.viewGeneration); } catch {
          // 화면 병합 실패는 확인한 서버 수락을 되돌리지 않음. 현재 보드 확인은 별도 GET으로 안내
          this.publish("accepted", "서버가 픽셀 배치를 수락했으나 화면을 갱신하지 못했습니다. 보드를 다시 불러와 현재 상태를 확인해 주세요.");
          return true;
        }
        this.publish("accepted", `서버가 (${change.x}, ${change.y})의 ${change.color}번 색상 배치를 수락했습니다. 예상 대기 시간이 끝난 뒤 다시 요청할 수 있습니다.`);
        return true;
      }
      if (result.status === 429) {
        let remainingMillis;
        try { remainingMillis = parsePixelJson(result.raw)?.remainingMillis; } catch { remainingMillis = null; }
        if (Number.isInteger(remainingMillis) && remainingMillis > 0 && remainingMillis <= PLACEMENT_COOLDOWN_MS) {
          this.cooldownEndsAt = this.now() + remainingMillis;
          this.publish("cooldown", "쿨다운으로 배치가 거절되었습니다. 서버가 알려 준 남은 시간으로 예상 타이머를 보정했습니다.");
        } else {
          this.cooldownEndsAt = null;
          this.publish("cooldown", COOLDOWN_UNKNOWN);
        }
      } else if (result.status === 401) {
        // 정리 callback이 요청을 무효화하기 전에 같은 요청의 명시적 거절을 확정
        this.request = null;
        this.auth.handleUnauthorized(request.sessionId);
        this.publish("unauthorized", "로그인 정보가 유효하지 않아 배치가 거절되었습니다. 카카오로 다시 로그인해 주세요.");
      } else if (result.status === 400) {
        this.publish("rejected", "좌표 또는 색상 입력 오류로 배치가 거절되었습니다. 선택을 확인해 주세요.");
      } else if (result.status === 403) {
        this.publish("forbidden", "배치 요청이 거부되었습니다. 권한·접속 주소·Origin 설정을 확인해 주세요.");
      } else {
        this.publish("unknown", result.status >= 500 ? `서버 오류로 ${UNKNOWN_MESSAGE}` : UNKNOWN_MESSAGE);
      }
    } catch {
      if (this.owns(request)) {
        if (request.responseStatus === 429) {
          this.cooldownEndsAt = null;
          this.publish("cooldown", COOLDOWN_UNKNOWN);
        } else {
          this.publish("unknown", UNKNOWN_MESSAGE);
        }
      }
    } finally {
      this.clearTimer(request.timer);
      if (this.request === request) {
        this.request = null;
        this.publish();
      }
    }
    return false;
  }

  // pagehide 전에 확인한429 거절은 보존. 미확정 요청의 abort는 서버 쓰기 취소 증명이 아님
  suspend() {
    this.active = false;
    const wasPending = Boolean(this.request);
    const wasCooldown = this.request?.responseStatus === 429;
    this.cancelRequest();
    if (wasCooldown) {
      this.cooldownEndsAt = null;
      this.publish("cooldown", COOLDOWN_UNKNOWN);
      return;
    }
    this.publish(wasPending ? "unknown" : this.status, wasPending ? UNKNOWN_MESSAGE : this.message);
  }

  resume() {
    this.active = true;
    return this.reconcileAuth();
  }
}
