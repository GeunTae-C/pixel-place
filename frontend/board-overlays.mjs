// 타일 원본과 분리한 격자·선택 표시. 클릭과 같은 CSS 좌표 변환으로 보이는 경계만 그림
export const GRID_MIN_SCALE = 8;

export function drawBoardOverlays(context, view, selected, gridEnabled) {
  const origin = view.boardToScreen(0, 0);
  const end = view.boardToScreen(view.metadata.boardSize, view.metadata.boardSize);
  const left = Math.max(0, origin.x);
  const top = Math.max(0, origin.y);
  const right = Math.min(view.width, end.x);
  const bottom = Math.min(view.height, end.y);
  if (right <= left || bottom <= top) return;

  context.save();
  context.beginPath();
  context.rect(left, top, right - left, bottom - top);
  context.clip();
  if (gridEnabled && view.scale >= GRID_MIN_SCALE) {
    // 타일별로 그리지 않아 이웃 타일의 공유 경계가 중복되지 않음. DPR 변환은 상위 draw가 담당
    // 내부 픽셀 경계에 1 CSS px 선의 중심을 맞춰, CSS 최근접 축소가 한 행·열을 건너뛰어도 선 유지
    const raster = context.getTransform();
    const firstX = Math.max(0, Math.ceil(view.centerX - view.width / (2 * view.scale)));
    const lastX = Math.min(view.metadata.boardSize, Math.floor(view.centerX + view.width / (2 * view.scale)));
    const firstY = Math.max(0, Math.ceil(view.centerY - view.height / (2 * view.scale)));
    const lastY = Math.min(view.metadata.boardSize, Math.floor(view.centerY + view.height / (2 * view.scale)));
    context.beginPath();
    for (let x = firstX; x <= lastX; x += 1) {
      const position = Math.round(view.boardToScreen(x, 0).x * raster.a) / raster.a;
      context.moveTo(position, top);
      context.lineTo(position, bottom);
    }
    for (let y = firstY; y <= lastY; y += 1) {
      const position = Math.round(view.boardToScreen(0, y).y * raster.d) / raster.d;
      context.moveTo(left, position);
      context.lineTo(right, position);
    }
    context.lineWidth = 1;
    context.strokeStyle = "rgba(94, 108, 122, .28)";
    context.stroke();
  }
  if (selected) {
    const position = view.boardToScreen(selected.x, selected.y);
    if (position.x + view.scale > left && position.y + view.scale > top
        && position.x < right && position.y < bottom) {
      context.lineWidth = 3;
      context.strokeStyle = "#fff";
      context.strokeRect(position.x, position.y, view.scale, view.scale);
      context.lineWidth = 1;
      context.strokeStyle = "#173b64";
      context.strokeRect(position.x, position.y, view.scale, view.scale);
    }
  }
  context.restore();
}
