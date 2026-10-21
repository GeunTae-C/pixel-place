import { validateTileResponse } from "./board-model.mjs";
import { TileMerge } from "./tile-merge.mjs";

// 현재 화면 요청만 대기. 동시 GET 4개, 화면 밖 snapshot 24개, 요청 기한 15초로 보유량 제한
export const TILE_CONCURRENCY = 4;
export const OFFSCREEN_TILE_LIMIT = 24;
export const REQUEST_TIMEOUT_MS = 15000;

export class TileLoader {
  constructor({ metadata, fetchImpl = (...args) => fetch(...args), apiBase = "http://localhost:8080", onChange = () => {},
    concurrency = TILE_CONCURRENCY, offscreenLimit = OFFSCREEN_TILE_LIMIT }) {
    this.metadata = metadata;
    this.fetchImpl = fetchImpl;
    this.apiBase = apiBase;
    this.onChange = onChange;
    this.concurrency = concurrency;
    this.offscreenLimit = offscreenLimit;
    this.entries = new Map();
    this.pending = new Map();
    this.active = new Set();
    this.visible = new Set();
    this.generation = 0;
    this.clock = 0;
    this.disposed = false;
  }

  setVisible(tiles) {
    if (this.disposed) return;
    this.visible = new Set(tiles.map((tile) => tile.key));
    for (const [key, entry] of this.entries) {
      if (!this.visible.has(key)) {
        const wasPending = this.pending.has(key);
        this.pending.delete(key);
        if (entry.request) {
          entry.generation += 1;
          this.cancel(entry.request);
          entry.request = null;
          entry.state = entry.data ? "ready" : "loading";
        }
        if (wasPending && entry.data) entry.state = "ready";
        // 과거 세대 HTTP 수락의 필수 GET이 끝나기 전에는 이전 화면을 정상 cache로 재사용 금지
        if (!entry.data || entry.requiredRefresh) this.release(entry);
      }
    }
    for (const tile of tiles) {
      let entry = this.entries.get(tile.key);
      if (!entry) {
        entry = { ...tile, state: "loading", data: null, error: null, generation: 0, request: null, requiredRefresh: false,
          merge: new TileMerge(this.metadata, tile.tx, tile.ty) };
        this.entries.set(tile.key, entry);
        this.pending.set(tile.key, entry);
      }
      entry.used = ++this.clock;
    }
    this.prune();
    this.pump();
    this.onChange();
  }

  get(key) { return this.entries.get(key); }

  getGeneration() { return this.generation; }

  // 현재 보이는 entry만 판정. 과거 화면 밖 요청의 종료가 새 영역의 완료를 선언하면 안 됨
  getVisibleState() {
    let ready = 0;
    let failed = 0;
    for (const key of this.visible) {
      const entry = this.entries.get(key);
      if (entry?.state === "error") failed += 1;
      else if (entry?.state === "ready" && !entry.requiredRefresh && entry.data && entry.merge.snapshot) ready += 1;
    }
    const total = this.visible.size;
    return { total, ready, failed, loading: total - ready - failed, complete: total > 0 && ready === total };
  }

  // 새 연결은 낮은 정상 snapshot도 새 기준으로 수용. 이전 journal·화면 밖 cache 재사용 금지
  reset(tiles) {
    if (this.disposed) return;
    this.invalidate();
    this.setVisible(tiles);
    return this.generation;
  }

  // 단절·페이지 이탈 경계. 실제 GET 슬롯은 반환까지 보유하고 이전 응답 설치·타이머만 무효화
  invalidate() {
    if (this.disposed) return;
    this.generation += 1;
    this.pending.clear();
    this.visible.clear();
    for (const request of this.active) this.cancel(request);
    for (const entry of this.entries.values()) this.release(entry);
    this.onChange();
  }

  // 현재 보유 entry에만 변경 연결. 화면 밖으로 해제된 타일은 다시 방문할 때 GET으로 확인
  applyChange(change) {
    if (this.disposed || !change || !Number.isSafeInteger(change.x) || !Number.isSafeInteger(change.y)) return false;
    const { tileSize } = this.metadata;
    const key = `${Math.floor(change.x / tileSize)},${Math.floor(change.y / tileSize)}`;
    const entry = this.entries.get(key);
    if (!entry || !entry.merge.applyChange(change)) return false;
    entry.data = entry.merge.data;
    this.onChange();
    return true;
  }

  // HTTP 수락의 인증 소유권은 호출자가 검증. 과거 화면 세대의 수락은 새 journal 대신 수락 이후 GET으로 확인
  applyAccepted(change, generation) {
    if (generation === this.generation) return this.applyChange(change);
    if (this.disposed || !change || !Number.isSafeInteger(change.x) || !Number.isSafeInteger(change.y)) return false;
    const { tileSize } = this.metadata;
    const key = `${Math.floor(change.x / tileSize)},${Math.floor(change.y / tileSize)}`;
    const entry = this.entries.get(key);
    if (!entry) return false;
    if (this.visible.has(key)) {
      entry.requiredRefresh = true;
      this.queueReload(entry);
      this.pump();
    } else {
      this.release(entry);
    }
    this.onChange();
    return true;
  }

  // 같은 연결의 journal 보존. 사용자 연속 재조회는 coalesce로 진행·대기 GET을 합침
  reload({ coalesce = false } = {}) {
    if (this.disposed) return;
    for (const key of this.visible) {
      const entry = this.entries.get(key);
      if (coalesce && (entry.request || this.pending.has(key))) continue;
      this.queueReload(entry);
    }
    this.pump();
    this.onChange();
  }

  queueReload(entry) {
    entry.generation += 1;
    if (entry.request) this.cancel(entry.request);
    entry.request = null;
    entry.state = "loading";
    entry.error = null;
    this.pending.set(entry.key, entry);
  }

  cancel(request) {
    clearTimeout(request.timer);
    request.timer = null;
    request.controller.abort();
  }

  prune() {
    const outside = [...this.entries.values()].filter((entry) => !this.visible.has(entry.key))
      .sort((left, right) => right.used - left.used);
    for (const entry of outside.slice(this.offscreenLimit)) this.release(entry);
  }

  release(entry) {
    if (entry.request) this.cancel(entry.request);
    entry.request = null;
    if (this.entries.get(entry.key) === entry) this.entries.delete(entry.key);
    this.pending.delete(entry.key);
    entry.merge.clear();
    entry.data = null;
  }

  owns(request) {
    const { entry, generation } = request;
    return !this.disposed && request.viewGeneration === this.generation
      && this.visible.has(entry.key) && this.entries.get(entry.key) === entry
      && entry.generation === generation && entry.request === request;
  }

  pump() {
    while (!this.disposed && this.active.size < this.concurrency && this.pending.size) {
      const [key, entry] = this.pending.entries().next().value;
      this.pending.delete(key);
      if (!this.visible.has(key) || this.entries.get(key) !== entry) continue;
      const request = { entry, generation: entry.generation, viewGeneration: this.generation,
        controller: new AbortController(), timer: null };
      entry.request = request;
      this.active.add(request);
      this.load(request);
    }
  }

  async load(request) {
    const { entry, controller } = request;
    try {
      const timeout = new Promise((_, reject) => {
        request.timer = setTimeout(() => {
          controller.abort();
          reject(new Error("타일 조회 시간이 초과되었습니다."));
        }, REQUEST_TIMEOUT_MS);
      });
      const read = async () => {
        const response = await this.fetchImpl(`${this.apiBase}/api/tiles/0/${entry.tx}/${entry.ty}`, {
          signal: controller.signal, credentials: "omit", cache: "no-store",
        });
        if (!response.ok) throw new Error(`타일 조회 실패 (HTTP ${response.status})`);
        // Fetch가 Content-Encoding을 처리한 원본 bytes와 같은 응답 header를 함께 검증
        const version = response.headers.get("X-Tile-Version");
        return validateTileResponse(await response.arrayBuffer(), version, this.metadata);
      };
      const data = await Promise.race([read(), timeout]);
      // abort가 늦거나 무시되어도 세대·entry identity·현재 필요 범위가 모두 일치할 때만 설치
      if (this.owns(request)) {
        entry.merge.installSnapshot(data);
        entry.data = entry.merge.data;
        entry.requiredRefresh = false;
        entry.state = "ready";
        entry.error = null;
      }
    } catch (error) {
      if (this.owns(request)) {
        entry.state = "error";
        entry.error = error instanceof Error ? error.message : "타일 조회 실패";
      }
    } finally {
      clearTimeout(request.timer);
      request.timer = null;
      if (entry.request === request) entry.request = null;
      this.active.delete(request);
      this.prune();
      this.pump();
      if (!this.disposed) this.onChange();
    }
  }

  dispose() {
    this.disposed = true;
    this.pending.clear();
    for (const request of this.active) this.cancel(request);
    for (const entry of this.entries.values()) this.release(entry);
    this.entries.clear();
    this.visible.clear();
  }
}
