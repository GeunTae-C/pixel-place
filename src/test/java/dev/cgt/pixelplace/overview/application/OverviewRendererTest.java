package dev.cgt.pixelplace.overview.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.common.constant.PaletteConstants;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverviewRendererTest {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Test
    void rendersFullBoardWithTopLeftSamplingTileBoundariesAndAllUnsignedPaletteIndexes()
            throws IOException {
        InMemoryTileBoard board = new InMemoryTileBoard();
        CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();

        for (int paletteIndex = 0; paletteIndex < BoardConstants.PALETTE_SIZE; paletteIndex++) {
            int overviewX = paletteIndex % 16;
            int overviewY = paletteIndex / 16;
            board.applyPixel(
                    overviewX * BoardConstants.OVERVIEW_SCALE,
                    overviewY * BoardConstants.OVERVIEW_SCALE,
                    paletteIndex
            );
        }

        // 좌상단 표본이 아닌 변경은 같은 4x4 overview block의 대표색을 바꾸면 안 됨
        board.applyPixel(3, 3, 200);
        board.applyPixel(252, 0, 9);
        board.applyPixel(256, 0, 10);
        board.applyPixel(0, 252, 11);
        board.applyPixel(0, 256, 12);
        board.applyPixel(40, 80, 42);
        board.applyPixel(8188, 8188, 255);

        TileKey firstKey = new TileKey(BoardConstants.Z0_LEVEL, 0, 0);
        TileState before = board.getRequired(firstKey);
        byte[] beforePixels = before.pixels();

        byte[] png = new OverviewRenderer(board, canonicalKeys).render();

        assertFalse(png.length == 0);
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(png, PNG_SIGNATURE.length));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded);
        assertEquals(BoardConstants.OVERVIEW_SIZE, decoded.getWidth());
        assertEquals(BoardConstants.OVERVIEW_SIZE, decoded.getHeight());

        List<String> palette = PaletteConstants.paletteHex();
        for (int paletteIndex = 0; paletteIndex < BoardConstants.PALETTE_SIZE; paletteIndex++) {
            int overviewX = paletteIndex % 16;
            int overviewY = paletteIndex / 16;
            assertEquals(expectedRgb(palette, paletteIndex), rgb(decoded, overviewX, overviewY));
        }

        assertEquals(expectedRgb(palette, 0), rgb(decoded, 0, 0));
        assertEquals(expectedRgb(palette, 17), rgb(decoded, 1, 1));
        assertEquals(expectedRgb(palette, 9), rgb(decoded, 63, 0));
        assertEquals(expectedRgb(palette, 10), rgb(decoded, 64, 0));
        assertEquals(expectedRgb(palette, 11), rgb(decoded, 0, 63));
        assertEquals(expectedRgb(palette, 12), rgb(decoded, 0, 64));
        assertEquals(expectedRgb(palette, 42), rgb(decoded, 10, 20));
        assertEquals(
                expectedRgb(palette, Byte.toUnsignedInt(BoardConstants.DEFAULT_COLOR_INDEX)),
                rgb(decoded, 20, 10)
        );
        assertEquals(expectedRgb(palette, 128), rgb(decoded, 0, 8));
        assertEquals(expectedRgb(palette, 255), rgb(decoded, 15, 15));
        assertEquals(expectedRgb(palette, 255), rgb(decoded, 2047, 2047));

        TileState after = board.getRequired(firstKey);
        assertEquals(before.tileVersion(), after.tileVersion());
        assertArrayEquals(beforePixels, after.pixels());
    }

    @Test
    void failsWhenCanonicalTileIsMissing() {
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        IllegalStateException missing = new IllegalStateException("missing tile");
        when(board.getRequired(any(TileKey.class))).thenThrow(missing);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new OverviewRenderer(board, new CanonicalZ0TileKeys()).render()
        );

        assertSame(missing, thrown);
    }

    @Test
    void failsWhenTileSnapshotLengthIsInvalid() {
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        TileState invalidTile = mock(TileState.class);
        when(invalidTile.pixels()).thenReturn(new byte[1]);
        when(board.getRequired(any(TileKey.class))).thenReturn(invalidTile);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new OverviewRenderer(board, new CanonicalZ0TileKeys()).render()
        );

        assertEquals("Overview tile pixels must match tile size.", thrown.getMessage());
    }

    @Test
    void rejectsPaletteWithWrongLengthBeforeReadingTiles() {
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        OverviewRenderer renderer = rendererWith(
                board,
                List::of,
                ImageIO::write
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, renderer::render);

        assertEquals("Overview palette size must be 256.", thrown.getMessage());
    }

    @Test
    void rejectsMalformedPaletteBeforeReadingTiles() {
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        List<String> malformed = new ArrayList<>(PaletteConstants.paletteHex());
        malformed.set(128, "not-rgb");
        OverviewRenderer renderer = rendererWith(
                board,
                () -> malformed,
                ImageIO::write
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, renderer::render);

        assertEquals("Overview palette contains an invalid RGB value.", thrown.getMessage());
    }

    @Test
    void pngWriterFalseIsGenerationFailureEvenWhenOutputContainsBytes() {
        InMemoryTileBoard board = whiteBoard();
        OverviewRenderer renderer = rendererWith(board, PaletteConstants::paletteHex, (image, format, output) -> {
            output.write(1);
            return false;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, renderer::render);

        assertEquals("PNG writer is not available.", thrown.getMessage());
    }

    @Test
    void pngWriterCheckedFailureIsConvertedWithOriginalCause() {
        InMemoryTileBoard board = whiteBoard();
        IOException encodingFailure = new IOException("encoding failed");
        OverviewRenderer renderer = rendererWith(
                board,
                PaletteConstants::paletteHex,
                (image, format, output) -> {
                    throw encodingFailure;
                }
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, renderer::render);

        assertEquals("Failed to encode overview PNG.", thrown.getMessage());
        assertSame(encodingFailure, thrown.getCause());
    }

    @Test
    void pngWriterTrueWithEmptyOutputIsGenerationFailure() {
        OverviewRenderer renderer = rendererWith(
                whiteBoard(),
                PaletteConstants::paletteHex,
                (image, format, output) -> true
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, renderer::render);

        assertEquals("PNG writer returned an empty image.", thrown.getMessage());
    }

    private OverviewRenderer rendererWith(
            InMemoryTileBoard board,
            java.util.function.Supplier<List<String>> paletteSource,
            OverviewRenderer.PngWriter pngWriter
    ) {
        return new OverviewRenderer(board, new CanonicalZ0TileKeys(), paletteSource, pngWriter);
    }

    private InMemoryTileBoard whiteBoard() {
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        TileState whiteTile = TileState.allWhite();
        when(board.getRequired(any(TileKey.class))).thenReturn(whiteTile);
        return board;
    }

    private int expectedRgb(List<String> palette, int index) {
        return Integer.parseInt(palette.get(index).substring(1), 16);
    }

    private int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0x00FF_FFFF;
    }
}
