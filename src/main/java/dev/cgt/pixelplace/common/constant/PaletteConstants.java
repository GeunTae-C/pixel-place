package dev.cgt.pixelplace.common.constant;

import java.util.ArrayList;
import java.util.List;

/*
 * 고정 256색 palette
 * 픽셀 raw byte는 이 palette의 index를 저장하므로, 색상 순서는 저장/렌더링 호환성의 일부
 * BoardConstants.DEFAULT_COLOR_INDEX는 빈 보드의 기본 색을 뜻하므로 반드시 #FFFFFF에 매핑되어야 함
 */
public final class PaletteConstants {

    private static final List<String> PALETTE_HEX = createPalette();

    private PaletteConstants() {
    }

    /*
     * 클라이언트 렌더링에서 사용할 palette를 수정 불가능한 목록으로 반환
     * 외부 코드가 색상 순서를 바꾸면 저장된 palette index 해석이 깨지므로 방어적으로 고정
     */
    public static List<String> paletteHex() {
        return PALETTE_HEX;
    }

    private static List<String> createPalette() {
        List<String> colors = new ArrayList<>(BoardConstants.PALETTE_SIZE);

        colors.addAll(List.of(
                "#000000", "#800000", "#008000", "#808000",
                "#000080", "#800080", "#008080", "#C0C0C0",
                "#808080", "#FF0000", "#00FF00", "#FFFF00",
                "#0000FF", "#FF00FF", "#00FFFF", "#FFFFFF"
        ));

        int[] levels = {0, 95, 135, 175, 215, 255};
        for (int r : levels) {
            for (int g : levels) {
                for (int b : levels) {
                    colors.add(toHex(r, g, b));
                }
            }
        }

        for (int i = 0; i < 24; i++) {
            int value = 8 + (i * 10);
            colors.add(toHex(value, value, value));
        }

        /*
         * palette 길이는 저장 포맷의 해석 범위와 직접 연결
         * 잘못된 상수 조합으로 서버가 뜨면 기존 pixel byte를 다른 색으로 해석할 수 있어 즉시 실패
         */
        if (colors.size() != BoardConstants.PALETTE_SIZE) {
            throw new IllegalStateException("Palette size must be " + BoardConstants.PALETTE_SIZE);
        }
        if (!"#FFFFFF".equals(colors.get(BoardConstants.DEFAULT_COLOR_INDEX))) {
            throw new IllegalStateException("Default color index must point to #FFFFFF.");
        }

        return List.copyOf(colors);
    }

    private static String toHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
