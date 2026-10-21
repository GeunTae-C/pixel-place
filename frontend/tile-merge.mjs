// HTTP 수락·WS 변경의 공통 병합 경계. 원본 snapshot 쌍과 픽셀별 최신 변경, 화면 bytes를 분리
const MAX_JAVA_LONG = 9223372036854775807n;

const positiveLong = (value) => typeof value === "bigint" && value > 0n && value <= MAX_JAVA_LONG;

export class TileMerge {
  constructor(metadata, tx, ty) {
    this.metadata = metadata;
    this.tx = tx;
    this.ty = ty;
    this.snapshot = null;
    this.changes = new Map();
    this.data = null;
  }

  // 로더가 같은 응답에서 검증한 bytes/version만 입력. B 이후 변경을 새 bytes 위에 다시 병합
  installSnapshot(snapshot) {
    const version = BigInt(snapshot.version);
    this.snapshot = { bytes: snapshot.bytes.slice(), version: snapshot.version };
    const bytes = this.snapshot.bytes.slice();
    for (const [pixel, change] of this.changes) {
      if (change.tileVersion <= version) this.changes.delete(pixel);
      else bytes[pixel] = change.color;
    }
    // Canvas WeakMap 캐시가 이전 bytes의 이미지를 재사용하지 않도록 새 화면 identity 부여
    this.data = { bytes, version: snapshot.version };
  }

  // 정밀도가 보존된 정규화 변경만 수용. 전역 eventSeq·타일 전체 최댓값으로 다른 픽셀 제외 금지
  applyChange(change) {
    const { boardSize, tileSize, paletteSize } = this.metadata;
    if (!change || !Number.isSafeInteger(change.x) || !Number.isSafeInteger(change.y)
        || change.x < 0 || change.y < 0 || change.x >= boardSize || change.y >= boardSize
        || Math.floor(change.x / tileSize) !== this.tx || Math.floor(change.y / tileSize) !== this.ty
        || !Number.isSafeInteger(change.color) || change.color < 0 || change.color >= paletteSize
        || !positiveLong(change.eventSeq) || !positiveLong(change.tileVersion)) return false;
    const version = change.tileVersion;
    if (this.snapshot && version <= BigInt(this.snapshot.version)) return false;
    const pixel = (change.y % tileSize) * tileSize + change.x % tileSize;
    const previous = this.changes.get(pixel);
    if (previous && previous.tileVersion >= version) return false;
    this.changes.set(pixel, { color: change.color, eventSeq: change.eventSeq, tileVersion: version });
    if (this.data) {
      const bytes = this.data.bytes.slice();
      bytes[pixel] = change.color;
      this.data = { bytes, version: this.snapshot.version };
    }
    // snapshot 없는 타일은 최신 변경만 보관하고 정상 타일 전체로 그리지 않음
    return true;
  }

  // 취소된 GET의 closure가 남아 있어도 해제 entry의 변경 기록과 이미지 입력은 즉시 정리
  clear() {
    this.snapshot = null;
    this.changes.clear();
    this.data = null;
  }
}
