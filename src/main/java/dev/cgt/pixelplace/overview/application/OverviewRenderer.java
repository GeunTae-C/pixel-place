package dev.cgt.pixelplace.overview.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.common.constant.PaletteConstants;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/*
 * recovery 완료 memory z=0 board를 탐색용 2048x2048 PNG로 변환하는 순수 renderer
 * 타일별 방어 복사만 사용하며 DB/WAL/dirty/flush 경계와 전역 board lock에는 관여하지 않음
 */
@Component
public class OverviewRenderer {

    private static final String PNG_FORMAT = "png";

    private final InMemoryTileBoard board;
    private final CanonicalZ0TileKeys canonicalZ0TileKeys;
    private final Supplier<List<String>> paletteSource;
    private final PngWriter pngWriter;

    @Autowired
    public OverviewRenderer(
            InMemoryTileBoard board,
            CanonicalZ0TileKeys canonicalZ0TileKeys
    ) {
        this(
                board,
                canonicalZ0TileKeys,
                PaletteConstants::paletteHex,
                ImageIO::write
        );
    }

    OverviewRenderer(
            InMemoryTileBoard board,
            CanonicalZ0TileKeys canonicalZ0TileKeys,
            Supplier<List<String>> paletteSource,
            PngWriter pngWriter
    ) {
        this.board = Objects.requireNonNull(board, "board must not be null");
        this.canonicalZ0TileKeys = Objects.requireNonNull(
                canonicalZ0TileKeys,
                "canonicalZ0TileKeys must not be null"
        );
        this.paletteSource = Objects.requireNonNull(paletteSource, "paletteSource must not be null");
        this.pngWriter = Objects.requireNonNull(pngWriter, "pngWriter must not be null");
    }

    /*
     * canonical 1,024개 tile을 각각 한 번 snapshot하고 4x4 블록 좌상단 표본만 RGB로 변환
     * 모든 tile 처리와 PNG writer 성공 뒤에만 수정되지 않는 완성 byte 배열 반환
     */
    public byte[] render() {
        validateGeometry();
        int[] paletteRgb = buildPaletteRgb();
        List<TileKey> orderedKeys = canonicalZ0TileKeys.orderedKeys();
        if (!canonicalZ0TileKeys.exactlyMatches(orderedKeys)) {
            // 일부 key만 그린 정상 크기 PNG가 게시되면 recovery board 누락을 숨길 수 있음
            throw new IllegalStateException("Canonical z=0 tile keys are incomplete.");
        }

        BufferedImage image = new BufferedImage(
                BoardConstants.OVERVIEW_SIZE,
                BoardConstants.OVERVIEW_SIZE,
                BufferedImage.TYPE_INT_RGB
        );
        int[] overviewPixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        for (TileKey key : orderedKeys) {
            renderTile(key, paletteRgb, overviewPixels);
        }

        return encodePng(image);
    }

    private void validateGeometry() {
        if (BoardConstants.BOARD_SIZE % BoardConstants.OVERVIEW_SCALE != 0
                || BoardConstants.TILE_SIZE % BoardConstants.OVERVIEW_SCALE != 0
                || BoardConstants.OVERVIEW_SIZE
                != BoardConstants.BOARD_SIZE / BoardConstants.OVERVIEW_SCALE
                || BoardConstants.OVERVIEW_TILE_SIZE
                != BoardConstants.TILE_SIZE / BoardConstants.OVERVIEW_SCALE) {
            // board와 tile 축소 비율이 다르면 tile 경계에서 누락·중복 overview pixel 발생 가능
            throw new IllegalStateException("Overview geometry is inconsistent.");
        }
    }

    private int[] buildPaletteRgb() {
        List<String> palette = Objects.requireNonNull(
                paletteSource.get(),
                "palette must not be null"
        );
        if (palette.size() != BoardConstants.PALETTE_SIZE) {
            // 1 byte 전체 범위를 변환할 수 없는 palette로 부분 PNG 생성 금지
            throw new IllegalStateException(
                    "Overview palette size must be " + BoardConstants.PALETTE_SIZE + "."
            );
        }

        int[] paletteRgb = new int[BoardConstants.PALETTE_SIZE];
        for (int index = 0; index < palette.size(); index++) {
            String hex = palette.get(index);
            if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
                // 잘못된 색 문자열을 black 등으로 보정하면 저장된 palette index 의미가 달라짐
                throw new IllegalStateException("Overview palette contains an invalid RGB value.");
            }
            try {
                paletteRgb[index] = Integer.parseInt(hex.substring(1), 16);
            } catch (NumberFormatException invalidHex) {
                throw new IllegalStateException(
                        "Overview palette contains an invalid RGB value.",
                        invalidHex
                );
            }
        }
        return paletteRgb;
    }

    private void renderTile(TileKey key, int[] paletteRgb, int[] overviewPixels) {
        if (!canonicalZ0TileKeys.contains(key)) {
            // 범위 밖 key를 output 좌표로 사용하면 다른 tile 영역 또는 배열 범위 침범 가능
            throw new IllegalStateException("Overview tile key is outside the canonical z=0 board.");
        }

        TileState tileState = Objects.requireNonNull(
                board.getRequired(key),
                "required tile state must not be null"
        );
        byte[] tilePixels = Objects.requireNonNull(
                tileState.pixels(),
                "required tile pixels must not be null"
        );
        if (tilePixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            // 손상 snapshot을 기본색이나 잘린 배열로 정상화하지 않는 generation 실패 경계
            throw new IllegalStateException("Overview tile pixels must match tile size.");
        }

        int overviewTileX = key.tx() * BoardConstants.OVERVIEW_TILE_SIZE;
        int overviewTileY = key.ty() * BoardConstants.OVERVIEW_TILE_SIZE;
        for (int localOverviewY = 0;
             localOverviewY < BoardConstants.OVERVIEW_TILE_SIZE;
             localOverviewY++) {
            int sourceRow = localOverviewY
                    * BoardConstants.OVERVIEW_SCALE
                    * BoardConstants.TILE_SIZE;
            int overviewRow = (overviewTileY + localOverviewY)
                    * BoardConstants.OVERVIEW_SIZE
                    + overviewTileX;
            for (int localOverviewX = 0;
                 localOverviewX < BoardConstants.OVERVIEW_TILE_SIZE;
                 localOverviewX++) {
                int sourceIndex = sourceRow
                        + localOverviewX * BoardConstants.OVERVIEW_SCALE;
                int paletteIndex = Byte.toUnsignedInt(tilePixels[sourceIndex]);
                overviewPixels[overviewRow + localOverviewX] = paletteRgb[paletteIndex];
            }
        }
    }

    private byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!pngWriter.write(image, PNG_FORMAT, output)) {
                // writer 미발견 시 output에 일부 bytes가 있어도 완전한 PNG로 게시 불가
                throw new IllegalStateException("PNG writer is not available.");
            }
            byte[] png = output.toByteArray();
            if (png.length == 0) {
                throw new IllegalStateException("PNG writer returned an empty image.");
            }
            return png;
        } catch (IOException encodingFailure) {
            // checked encoding 실패를 공통 service generation 실패 경계로 전달
            throw new IllegalStateException("Failed to encode overview PNG.", encodingFailure);
        }
    }

    @FunctionalInterface
    interface PngWriter {

        boolean write(RenderedImage image, String formatName, OutputStream output) throws IOException;
    }
}
