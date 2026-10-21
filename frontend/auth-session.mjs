// 이 탭의 OAuth 복귀·Access 보관·만료 경계. UI에는 token 없는 상태만 전달하고 공개 GET에는 관여하지 않음
export const AUTH_STORAGE_KEYS = Object.freeze({
  session: "pixel-place.auth.session",
  pending: "pixel-place.auth.pending",
});
export const AUTH_REQUEST_TIMEOUT_MS = 15000;
const MAX_ACCESS_SECONDS = 900;
const STORAGE_WARNING = "저장된 로그인 정보를 지우지 못했습니다. 이 탭을 닫거나 브라우저의 이 사이트 저장소 설정을 확인해 주세요.";
const EXPIRED_MESSAGE = "로그인이 만료되었습니다. 카카오로 다시 로그인해 주세요.";
const NETWORK_MESSAGE = "로그인 확인 요청에 실패했습니다. 네트워크·접속 주소·Origin 설정을 확인한 뒤 다시 로그인해 주세요.";

class AuthFailure extends Error {
  constructor(message, status = "error") {
    super(message);
    this.status = status;
  }
}

function validToken(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function responseFailure(status) {
  if (status === 0) return NETWORK_MESSAGE;
  if (status === 401) return "로그인 확인 정보가 없거나 유효하지 않습니다. 카카오로 다시 로그인해 주세요.";
  if (status === 403) return "로그인 확인 요청이 거부되었습니다. 접속 주소·Origin 설정을 확인한 뒤 다시 로그인해 주세요.";
  if (status >= 500) return "서버에서 로그인을 확인하지 못했습니다. 잠시 후 다시 로그인해 주세요.";
  return "로그인 확인에 실패했습니다. 카카오로 다시 로그인해 주세요.";
}

export class AuthSession {
  constructor({ apiBase = "http://localhost:8080", onChange = () => {},
    getStorage = () => globalThis.sessionStorage, fetchImpl = (...args) => fetch(...args),
    navigate = (url) => globalThis.location.assign(url),
    replaceUrl = (url) => globalThis.history.replaceState(globalThis.history.state, "", url),
    now = () => Date.now(), setTimer = (...args) => setTimeout(...args),
    clearTimer = (...args) => clearTimeout(...args) } = {}) {
    this.apiBase = apiBase;
    this.onChange = onChange;
    this.getStorage = getStorage;
    this.fetchImpl = fetchImpl;
    this.navigate = navigate;
    this.replaceUrl = replaceUrl;
    this.now = now;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.session = null;
    this.sessionId = null;
    this.request = null;
    this.expiryTimer = null;
    this.generation = 0;
    this.active = true;
    this.redirecting = false;
    this.needsCleanup = false;
    this.initialization = null;
    this.state = Object.freeze({ status: "signedOut", message: "로그인 전", expiresAt: null });
  }

  getState() { return this.state; }

  // token 없는 세션 소유권. OAuth 요청 generation과 달리 같은 저장값 재검사에서는 유지
  getSessionId() {
    return this.active && this.session && this.session.expiresAt > this.now() ? this.sessionId : null;
  }

  installSession(session) {
    if (!this.session || this.session.accessToken !== session.accessToken
        || this.session.expiresAt !== session.expiresAt) this.sessionId = Object.freeze({});
    this.session = session;
  }

  // 보호 요청을 시작할 때 저장 상태·절대 만료 재검사 후에만 Access와 소유권 함께 고정
  acquireAccess() {
    this.reconcile();
    const sessionId = this.getSessionId();
    return sessionId ? Object.freeze({ accessToken: this.session.accessToken, sessionId }) : null;
  }

  isCurrentSession(sessionId) {
    this.reconcile();
    return sessionId !== null && sessionId === this.getSessionId();
  }

  publish(status, message) {
    this.state = Object.freeze({ status, message, expiresAt: this.session?.expiresAt ?? null });
    this.onChange(this.state);
    return this.state;
  }

  stopExpiryTimer() {
    if (this.expiryTimer !== null) this.clearTimer(this.expiryTimer);
    this.expiryTimer = null;
  }

  invalidate() {
    this.generation += 1;
    this.stopExpiryTimer();
    const request = this.request;
    this.request = null;
    if (request) {
      this.clearTimer(request.timer);
      request.cancel();
      request.controller.abort();
    }
  }

  clearStorage() {
    let success = true;
    try {
      const storage = this.getStorage();
      // 한 키의 삭제 실패가 다른 인증 키 정리까지 막지 않도록 각각 시도. 전체 storage.clear() 금지
      for (const key of Object.values(AUTH_STORAGE_KEYS)) {
        try { storage.removeItem(key); } catch { success = false; }
      }
    } catch {
      success = false;
    }
    this.needsCleanup = !success;
    return success;
  }

  clearAuthentication(message, status = "error") {
    this.invalidate();
    this.session = null;
    this.sessionId = null;
    this.redirecting = false;
    const cleared = this.clearStorage();
    const failureMessage = status === "signedOut" ? STORAGE_WARNING : `${message} ${STORAGE_WARNING}`;
    this.publish(cleared ? status : "error", cleared ? message : failureMessage);
    return cleared;
  }

  // 문서당 한 번만 진행 표식을 소비. 일반 방문·focus·pageshow는 handoff 교환 시작점이 아님
  initialize(url) {
    if (!this.initialization) this.initialization = this.initializeReturn(url);
    return this.initialization;
  }

  async initializeReturn(url) {
    const currentUrl = new URL(url);
    if (currentUrl.searchParams.has("error")) {
      currentUrl.searchParams.delete("error");
      let message = "카카오 로그인에 실패했습니다. 카카오로 다시 로그인해 주세요.";
      try {
        this.replaceUrl(currentUrl.href);
      } catch {
        message += " 주소의 로그인 오류 표시를 정리하지 못했습니다.";
      }
      this.clearAuthentication(message);
      return;
    }
    let pending;
    try {
      const storage = this.getStorage();
      pending = storage.getItem(AUTH_STORAGE_KEYS.pending);
      if (pending !== null) storage.removeItem(AUTH_STORAGE_KEYS.pending);
    } catch {
      // 소비를 확인하지 못한 표식으로 요청하면 새로고침 때 중복 교환될 수 있으므로 시작 금지
      this.clearAuthentication("로그인 진행 정보를 읽거나 정리하지 못했습니다. 브라우저 저장소 설정을 확인해 주세요.");
      return;
    }
    if (pending === "pending") {
      await this.exchange();
    } else if (pending !== null) {
      this.clearAuthentication("로그인 진행 정보가 손상되었습니다. 카카오로 다시 로그인해 주세요.");
    } else {
      this.reconcile();
    }
  }

  // 저장 가능한 복귀 표식을 먼저 준비한 뒤에만 같은 탭 이동. 명시적 새 시도는 이전 교환의 소유권 폐기
  beginLogin() {
    if (!this.active || this.redirecting) return false;
    if (!this.clearAuthentication("로그인 준비 중", "signedOut")) return false;
    try {
      this.getStorage().setItem(AUTH_STORAGE_KEYS.pending, "pending");
    } catch {
      this.clearAuthentication("로그인 진행 정보를 저장하지 못했습니다. 브라우저 저장소 설정을 확인한 뒤 다시 시도해 주세요.");
      return false;
    }
    this.redirecting = true;
    this.publish("checking", "카카오 로그인 페이지로 이동 중");
    try {
      this.navigate(`${this.apiBase}/oauth2/authorization/kakao`);
      return true;
    } catch {
      this.clearAuthentication("카카오 로그인 페이지를 열지 못했습니다. 다시 시도해 주세요.");
      return false;
    }
  }

  owns(request) {
    return this.active && this.request === request && this.generation === request.generation;
  }

  async exchange() {
    this.invalidate();
    this.session = null;
    this.sessionId = null;
    if (!this.clearStorage()) {
      this.publish("error", STORAGE_WARNING);
      return;
    }
    const request = { generation: this.generation, controller: new AbortController(), startedAt: this.now() };
    this.request = request;
    this.publish("checking", "로그인 확인 중");
    const cancelled = new Promise((_, reject) => {
      request.cancel = () => reject(new AuthFailure("로그인 확인이 중단되었습니다."));
    });
    const timeout = new Promise((_, reject) => {
      request.timer = this.setTimer(() => {
        reject(new AuthFailure("로그인 확인 시간이 초과되었습니다. 다시 로그인해 주세요."));
        request.controller.abort();
      }, AUTH_REQUEST_TIMEOUT_MS);
    });
    try {
      const read = async () => {
        const response = await this.fetchImpl(`${this.apiBase}/api/auth/token`, {
          method: "POST", credentials: "include", cache: "no-store", signal: request.controller.signal,
        });
        if (!response.ok) throw new AuthFailure(responseFailure(response.status));
        let body;
        try { body = await response.json(); } catch {
          throw new AuthFailure("로그인 응답 형식이 올바르지 않습니다. 다시 로그인해 주세요.");
        }
        if (!body || !validToken(body.accessToken) || body.tokenType !== "Bearer"
          || !Number.isInteger(body.expiresInSeconds) || body.expiresInSeconds < 1
          || body.expiresInSeconds > MAX_ACCESS_SECONDS) {
          // 측정용 6시간 수명이나 비정상 필드를 production Access로 설치하면 안 됨
          throw new AuthFailure("로그인 응답 정보가 올바르지 않습니다. 다시 로그인해 주세요.");
        }
        return { accessToken: body.accessToken, expiresAt: request.startedAt + body.expiresInSeconds * 1000 };
      };
      const session = await Promise.race([read(), timeout, cancelled]);
      // abort 무시·지연된 JSON·동결된 timer 모두 현재 요청 소유권과 절대 시간으로 재확인
      if (!this.owns(request)) return;
      if (this.now() >= request.startedAt + AUTH_REQUEST_TIMEOUT_MS) {
        throw new AuthFailure("로그인 확인 시간이 초과되었습니다. 다시 로그인해 주세요.");
      }
      if (session.expiresAt <= this.now()) throw new AuthFailure(EXPIRED_MESSAGE, "expired");
      try {
        // token과 기존 절대 만료를 한 값으로 저장하여 부분 쌍·새로고침 수명 연장 방지
        this.getStorage().setItem(AUTH_STORAGE_KEYS.session, JSON.stringify(session));
      } catch {
        throw new AuthFailure("로그인 정보를 저장하지 못했습니다. 브라우저 저장소 설정을 확인한 뒤 다시 로그인해 주세요.");
      }
      this.installSession(session);
      this.scheduleExpiry();
      this.publish("authenticated", "로그인됨");
    } catch (error) {
      if (this.owns(request)) {
        // 응답 원문·provider 오류·네트워크 예외 메시지는 화면이나 로그로 전달하지 않음
        this.clearAuthentication(error instanceof AuthFailure ? error.message : NETWORK_MESSAGE,
          error instanceof AuthFailure ? error.status : "error");
      }
    } finally {
      this.clearTimer(request.timer);
      if (this.request === request) this.request = null;
    }
  }

  scheduleExpiry() {
    this.stopExpiryTimer();
    if (this.active && this.session) {
      this.expiryTimer = this.setTimer(() => this.reconcile(), Math.max(0, this.session.expiresAt - this.now()));
    }
  }

  // 최초 진입·focus·visible·pageshow에서 같은 저장값과 절대 만료 재검사. 삭제 실패 잔여값은 복원 금지
  reconcile() {
    if (!this.active || this.request || this.redirecting) return this.state;
    if (this.needsCleanup) {
      this.clearAuthentication("로그인 정보 정리를 다시 시도했습니다. 카카오로 다시 로그인해 주세요.");
      return this.state;
    }
    let raw;
    try { raw = this.getStorage().getItem(AUTH_STORAGE_KEYS.session); } catch {
      this.clearAuthentication("로그인 정보를 읽지 못했습니다. 브라우저 저장소 설정을 확인해 주세요.");
      return this.state;
    }
    if (raw === null) {
      this.session = null;
      this.sessionId = null;
      this.stopExpiryTimer();
      if (this.state.status === "authenticated" || this.state.status === "checking") this.publish("signedOut", "로그인 전");
      else this.onChange(this.state);
      return this.state;
    }
    let session;
    try { session = JSON.parse(raw); } catch { session = null; }
    if (!session || !validToken(session.accessToken) || !Number.isSafeInteger(session.expiresAt)
      || session.expiresAt <= 0 || session.expiresAt > this.now() + MAX_ACCESS_SECONDS * 1000) {
      this.clearAuthentication("저장된 로그인 정보가 손상되었습니다. 카카오로 다시 로그인해 주세요.");
    } else if (session.expiresAt <= this.now()) {
      this.clearAuthentication(EXPIRED_MESSAGE, "expired");
    } else {
      this.installSession({ accessToken: session.accessToken, expiresAt: session.expiresAt });
      this.scheduleExpiry();
      this.publish("authenticated", "로그인됨");
    }
    return this.state;
  }

  // 로컬 로그아웃만 담당. 서버 token 폐기·카카오 계정 logout·다른 용도의 저장값 삭제는 수행하지 않음
  logout() {
    return this.clearAuthentication("로그아웃되었습니다. 카카오로 다시 로그인할 수 있습니다.", "signedOut");
  }

  // 이전 요청의 401은 새 로그인 정리 금지. 보호 요청 호출자는 고정한 세션 소유권 전달
  handleUnauthorized(sessionId = this.getSessionId()) {
    if (!this.isCurrentSession(sessionId)) return false;
    this.clearAuthentication("로그인 정보가 유효하지 않습니다. 카카오로 다시 로그인해 주세요.", "expired");
    return true;
  }

  // pagehide는 현재 응답만 폐기. BFCache pageshow에서 같은 객체를 다시 사용할 수 있도록 영구 dispose 금지
  suspend() {
    this.active = false;
    this.invalidate();
    // 같은 보존 페이지 복귀는 같은 세션. 비활성 동안 Access 취득은 getSessionId에서 차단
  }

  resume() {
    this.active = true;
    this.redirecting = false;
    return this.reconcile();
  }
}
