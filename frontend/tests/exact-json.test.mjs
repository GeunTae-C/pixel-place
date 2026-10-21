// 표준 JSON 원문 기반 순서 값과 공개 WS 단건 계약 검증. 실제 프레임 수신 증거와 분리
import test from "node:test";
import assert from "node:assert/strict";
import { parsePixelJson, parsePixelEvent } from "../exact-json.mjs";

const metadata = { boardSize: 8, paletteSize: 3 };
const event = '{"type":"pixel","x":1,"y":2,"color":1,"eventSeq":11,"tileVersion":12}';

test("WS 단건의 2^53+1과 Java long 최대값을 원문에서 정확하게 보존", () => {
  for (const exact of ["1", "9007199254740993", "9223372036854775807"]) {
    const raw = `{"type":"pixel","x":0,"y":7,"color":2,"eventSeq":${exact},"tileVersion":${exact}}`;
    const change = parsePixelEvent(raw, metadata);
    assert.deepEqual(change, { x: 0, y: 7, color: 2, eventSeq: BigInt(exact), tileVersion: BigInt(exact) });
    assert.equal(Object.isFrozen(change), true);
    assert.equal(parsePixelJson(raw).eventSeq, BigInt(exact));
  }
});

test("공개 WS는 정확히 pixel 단건만 수용하며 누락·배치·잘못된 JSON과 필드 타입을 거부", () => {
  const cases = ["{", "null", "[]", "{}", '"pixel"',
    event.replace('"pixel"', '"pixels"'), event.replace('"pixel"', '"PIXEL"'),
    event.replace('"pixel"', "null"), event.replace('"pixel"', "1"),
    '{"type":"pixels","pixels":[]}',
    event.replace('"x":1', '"x":"1"'), event.replace('"y":2', '"y":null'),
    event.replace('"color":1', '"color":true')];
  for (const field of ["type", "x", "y", "color", "eventSeq", "tileVersion"]) {
    const body = JSON.parse(event);
    delete body[field];
    cases.push(JSON.stringify(body));
  }
  for (const raw of cases) assert.throws(() => parsePixelEvent(raw, metadata), undefined, raw);
});

test("text가 아닌 입력과 표준 reviver source 미지원은 정상 이벤트로 적용하지 않음", () => {
  for (const value of [null, undefined, 1, {}, [], Uint8Array.from([123, 125]), new String(event)]) {
    assert.throws(() => parsePixelEvent(value, metadata));
  }
  const originalParse = JSON.parse;
  try {
    JSON.parse = (raw, reviver) => originalParse(raw, (key, value) => reviver(key, value));
    assert.throws(() => parsePixelEvent(event, metadata));
  } finally {
    JSON.parse = originalParse;
  }
});

test("각 long 필드의 범위·문자열·소수·지수·반올림 원문을 독립적으로 거부", () => {
  for (const field of ["eventSeq", "tileVersion"]) {
    const original = field === "eventSeq" ? '"eventSeq":11' : '"tileVersion":12';
    for (const invalid of ["0", "-1", "9223372036854775808", "99999999999999999999", '"12"',
      "null", "true", "1.5", "1.0000000000000001", "1e3", "1E0"]) {
      assert.throws(() => parsePixelEvent(event.replace(original, `"${field}":${invalid}`), metadata),
        undefined, `${field}=${invalid}`);
    }
  }
});

test("좌표와 색상의 보드·팔레트 경계 및 정수 원문을 검증", () => {
  for (const [field, original, invalidValues] of [
    ["x", 1, ["-1", "8", "1.5", "1.0000000000000001", "1e0", "9007199254740993"]],
    ["y", 2, ["-1", "8", "2.5", "2.0000000000000001", "2e0"]],
    ["color", 1, ["-1", "3", "1.5", "1.0000000000000001", "1e0"]],
  ]) {
    for (const invalid of invalidValues) {
      assert.throws(() => parsePixelEvent(event.replace(`"${field}":${original}`, `"${field}":${invalid}`), metadata),
        undefined, `${field}=${invalid}`);
    }
  }
  assert.deepEqual(parsePixelEvent(event.replace('"x":1', '"x":7').replace('"y":2', '"y":0')
    .replace('"color":1', '"color":0'), metadata), { x: 7, y: 0, color: 0, eventSeq: 11n, tileVersion: 12n });
});
