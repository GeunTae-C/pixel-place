// 공개 raw WS의 유한 재연결과 페이지 수명 소유권 담당. 타일 조회·인증·POST 결과는 호출부 책임
export const CONNECTION_TIMEOUT_MS = 15000;
export const RETRY_DELAYS_MS = [1000, 2000, 4000, 8000, 15000];
export const STABLE_CONNECTION_MS = 30000;

export class RealtimeConnection {
  constructor({ url, onOpen = () => {}, onMessage = () => {}, onDisconnect = () => {}, onChange = () => {},
    createSocket = (address) => new WebSocket(address), setTimer = (callback, delay) => setTimeout(callback, delay),
    clearTimer = (timer) => clearTimeout(timer), now = () => Date.now() }) {
    this.url = url;
    this.onOpen = onOpen;
    this.onMessage = onMessage;
    this.onDisconnect = onDisconnect;
    this.onChange = onChange;
    this.createSocket = createSocket;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.now = now;
    this.status = "suspended";
    this.active = false;
    this.failureSeen = false;
    this.protocolError = false;
    this.synchronized = false;
    this.retries = 0;
    this.attempt = 0;
    this.epoch = 0;
    this.current = null;
    this.retryTimer = null;
  }

  getState() {
    return { status: this.status, failureSeen: this.failureSeen, protocolError: this.protocolError,
      synchronized: this.synchronized, retries: this.retries, attempt: this.attempt, epoch: this.epoch };
  }

  start() { this.resume(); }

  // 보존 페이지 복귀도 새 페이지 수명으로 시작. 중복 pageshow는 현재 시도를 재사용
  resume() {
    if (this.active) return;
    this.active = true;
    this.failureSeen = false;
    this.protocolError = false;
    this.retries = 0;
    this.attempt = 0;
    this.connect();
  }

  // 연결 중 연속 클릭은 같은 시도 유지. 연결된 상태의 타일 재조회는 호출부에서 수행
  retry() {
    if (!this.active || this.status === "connecting" || this.status === "connected") return;
    this.cancelRetry();
    this.retries = 0;
    this.connect();
  }

  // pagehide 시 먼저 소유권을 폐기해 지연 콜백·타이머의 재연결 금지
  suspend() {
    if (!this.active) return;
    this.active = false;
    this.cancelRetry();
    this.release(this.current);
    this.synchronized = false;
    this.status = "suspended";
    this.notify();
  }

  // 현재 보이는 영역의 snapshot 설치·병합 완료만 안정 조건에 사용
  setSynchronized(value) {
    if (this.status !== "connected" || !this.current) return;
    const next = value === true;
    if (this.synchronized === next) return;
    this.synchronized = next;
    this.resetStableBudget(this.current);
    this.notify();
  }

  // 정상 중복·역순은 병합부에서 처리. wire 검증 실패만 연결 전체를 무효화
  rejectMessage() {
    if (this.status === "connected" && this.current) this.fail(this.current, "protocol");
  }

  owns(connection) { return this.active && this.current === connection; }

  connect() {
    if (!this.active || this.current) return;
    const connection = { epoch: ++this.epoch, socket: null, deadline: null, stability: null,
      openedAt: null, stabilized: false, listeners: null };
    this.current = connection;
    this.attempt += 1;
    this.status = "connecting";
    this.synchronized = false;
    this.notify();
    // 상태 알림에서 페이지가 닫힌 경우에도 새 socket 생성 금지
    if (!this.owns(connection)) return;
    try {
      connection.socket = this.createSocket(this.url);
    } catch {
      // 브라우저의 생성 단계 실패도 동일한 유한 재시도 예산 소비
      this.fail(connection, "creation");
      return;
    }
    const listeners = {
      open: () => this.opened(connection),
      message: (event) => this.received(connection, event),
      error: () => this.fail(connection, "error"),
      close: () => this.fail(connection, "close"),
    };
    connection.listeners = listeners;
    for (const [type, listener] of Object.entries(listeners)) connection.socket.addEventListener(type, listener);
    connection.deadline = this.setTimer(() => {
      if (this.owns(connection) && this.status === "connecting") this.fail(connection, "timeout");
    }, CONNECTION_TIMEOUT_MS);
  }

  opened(connection) {
    if (!this.owns(connection) || this.status !== "connecting") return;
    this.clearTimer(connection.deadline);
    connection.deadline = null;
    connection.openedAt = this.now();
    this.status = "connected";
    this.protocolError = false;
    connection.stability = this.setTimer(() => {
      if (!this.owns(connection)) return;
      connection.stability = null;
      if (this.resetStableBudget(connection)) this.notify();
    }, STABLE_CONNECTION_MS);
    // 이 콜백에서 새 연결 기준의 entry 준비·GET 시작. open만으로 동기화 완료하지 않음
    this.onOpen({ epoch: connection.epoch });
    if (this.owns(connection)) this.notify();
  }

  received(connection, event) {
    if (!this.owns(connection) || this.status !== "connected") return;
    try {
      this.onMessage(event.data, { epoch: connection.epoch });
    } catch {
      // 원문 형식·필드 검증 실패 시 누락 가능성을 감추지 않고 새 연결 snapshot으로 복구
      this.fail(connection, "protocol");
    }
  }

  resetStableBudget(connection) {
    if (!this.owns(connection) || connection.stabilized || !this.synchronized
      || this.now() - connection.openedAt < STABLE_CONNECTION_MS) return false;
    connection.stabilized = true;
    this.retries = 0;
    return true;
  }

  fail(connection, reason) {
    if (!this.owns(connection)) return;
    const wasConnected = this.status === "connected";
    this.release(connection);
    this.synchronized = false;
    this.failureSeen = true;
    if (reason === "protocol") this.protocolError = true;
    if (this.retries < RETRY_DELAYS_MS.length) {
      const delay = RETRY_DELAYS_MS[this.retries++];
      const reservation = { timer: null };
      this.retryTimer = reservation;
      this.status = "retrying";
      reservation.timer = this.setTimer(() => {
        if (!this.active || this.retryTimer !== reservation) return;
        this.retryTimer = null;
        this.connect();
      }, delay);
    } else {
      this.status = "exhausted";
    }
    this.onDisconnect({ epoch: connection.epoch, reason, wasConnected });
    this.notify();
  }

  release(connection) {
    if (!connection || this.current !== connection) return;
    this.current = null;
    if (connection.deadline !== null) this.clearTimer(connection.deadline);
    if (connection.stability !== null) this.clearTimer(connection.stability);
    connection.deadline = null;
    connection.stability = null;
    if (!connection.socket) return;
    if (connection.listeners) {
      for (const [type, listener] of Object.entries(connection.listeners)) {
        connection.socket.removeEventListener(type, listener);
      }
    }
    try { connection.socket.close(); } catch {
      // 닫힘 통지가 실패해도 폐기된 연결의 소유권을 되살리지 않음
    }
  }

  cancelRetry() {
    if (!this.retryTimer) return;
    this.clearTimer(this.retryTimer.timer);
    this.retryTimer = null;
  }

  notify() { this.onChange(this.getState()); }
}
