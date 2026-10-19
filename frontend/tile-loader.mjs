import { validateTileResponse } from "./board-model.mjs";

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
          entry.request.controller.abort();
          entry.request = null;
          entry.state = entry.data ? "ready" : "loading";
        }
        if (wasPending && entry.data) entry.state = "ready";
        if (!entry.data) this.entries.delete(key);
      }
    }
    for (const tile of tiles) {
      let entry = this.entries.get(tile.key);
      if (!entry) {
        entry = { ...tile, state: "loading", data: null, error: null, generation: 0, request: null };
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

  reload() {
    if (this.disposed) return;
    for (const key of this.visible) {
      const entry = this.entries.get(key);
      entry.generation += 1;
      entry.request?.controller.abort();
      entry.request = null;
      entry.state = "loading";
      entry.error = null;
      this.pending.set(key, entry);
    }
    this.pump();
    this.onChange();
  }

  prune() {
    const outside = [...this.entries.values()].filter((entry) => !this.visible.has(entry.key))
      .sort((left, right) => right.used - left.used);
    for (const entry of outside.slice(this.offscreenLimit)) this.entries.delete(entry.key);
  }

  owns(request) {
    const { entry, generation } = request;
    return !this.disposed && this.visible.has(entry.key) && this.entries.get(entry.key) === entry
      && entry.generation === generation && entry.request === request;
  }

  pump() {
    while (!this.disposed && this.active.size < this.concurrency && this.pending.size) {
      const [key, entry] = this.pending.entries().next().value;
      this.pending.delete(key);
      if (!this.visible.has(key) || this.entries.get(key) !== entry) continue;
      const request = { entry, generation: entry.generation, controller: new AbortController() };
      entry.request = request;
      this.active.add(request);
      this.load(request);
    }
  }

  async load(request) {
    const { entry, controller } = request;
    let timer;
    try {
      const timeout = new Promise((_, reject) => {
        timer = setTimeout(() => {
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
        entry.data = data;
        entry.state = "ready";
        entry.error = null;
      }
    } catch (error) {
      if (this.owns(request)) {
        entry.state = "error";
        entry.error = error instanceof Error ? error.message : "타일 조회 실패";
      }
    } finally {
      clearTimeout(timer);
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
    for (const request of this.active) request.controller.abort();
    this.entries.clear();
    this.visible.clear();
  }
}
