// HTTP 수락과 WS 단건 메시지의 숫자 원문 검증 경계. 반올림된 Number로 버전 복원 금지
const LONG_MAX = 9223372036854775807n;

export function parsePixelJson(raw) {
  if (typeof raw !== "string") throw new Error("JSON text 확인 불가");
  return JSON.parse(raw, (key, value, context) => {
    if (["eventSeq", "tileVersion"].includes(key)) {
      if (typeof value !== "number" || !context?.source || !/^[1-9]\d{0,18}$/.test(context.source)) {
        throw new Error("정확한 순서 값 확인 불가");
      }
      const exact = BigInt(context.source);
      if (exact > LONG_MAX) throw new Error("Java long 범위 초과");
      return exact;
    }
    if (["x", "y", "color", "remainingMillis"].includes(key)) {
      if (typeof value !== "number" || !context?.source || !/^(0|[1-9]\d*)$/.test(context.source)
          || !Number.isSafeInteger(value)) throw new Error("정수 필드 확인 불가");
    }
    return value;
  });
}

export function parsePixelEvent(raw, metadata) {
  const body = parsePixelJson(raw);
  if (!body || body.type !== "pixel"
      || !Number.isSafeInteger(body.x) || body.x < 0 || body.x >= metadata.boardSize
      || !Number.isSafeInteger(body.y) || body.y < 0 || body.y >= metadata.boardSize
      || !Number.isSafeInteger(body.color) || body.color < 0 || body.color >= metadata.paletteSize
      || typeof body.eventSeq !== "bigint" || typeof body.tileVersion !== "bigint") {
    // 누락 가능성이 생긴 수신은 정상 중복과 달리 연결 복구가 필요함
    throw new Error("단건 pixel 메시지 확인 불가");
  }
  return Object.freeze({ x: body.x, y: body.y, color: body.color,
    eventSeq: body.eventSeq, tileVersion: body.tileVersion });
}
