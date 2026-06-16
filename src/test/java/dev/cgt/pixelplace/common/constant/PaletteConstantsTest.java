package dev.cgt.pixelplace.common.constant;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 고정 palette 저장/렌더링 호환성 검증
// byte 단위 pixel 값은 palette index이므로 순서와 불변성 유지 필요
class PaletteConstantsTest {

    private static final Pattern UPPERCASE_HEX_COLOR = Pattern.compile("#[0-9A-F]{6}");

    @Test
    // 1 byte palette index 전체 범위와 상수 일치
    void paletteSizeIs256() {
        assertEquals(BoardConstants.PALETTE_SIZE, PaletteConstants.paletteHex().size());
    }

    @Test
    // 외부 API 응답에서 사용할 대문자 #RRGGBB 형식 고정
    void allColorsUseUppercaseHexFormat() {
        assertTrue(PaletteConstants.paletteHex().stream()
                .allMatch(color -> UPPERCASE_HEX_COLOR.matcher(color).matches()));
    }

    @Test
    // 빈 보드 기본 색 index가 흰색이라는 초기 상태 계약
    void defaultColorIndexIsWhite() {
        assertEquals("#FFFFFF", PaletteConstants.paletteHex().get(BoardConstants.DEFAULT_COLOR_INDEX));
    }

    @Test
    // 호출자가 palette 순서를 바꾸면 저장된 byte 해석이 깨지므로 수정 차단
    void paletteListCannotBeModifiedByCaller() {
        assertThrows(UnsupportedOperationException.class,
                () -> PaletteConstants.paletteHex().add("#123456"));
    }
}
