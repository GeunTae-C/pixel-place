package dev.cgt.pixelplace.tile.web;

import dev.cgt.pixelplace.tile.application.TileReadResult;
import dev.cgt.pixelplace.tile.application.TileReadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/*
 * z=0 tile raw bytes를 제공하는 read-only HTTP 진입점
 * 응답 body는 JSON이 아니라 gzip 압축된 256x256 palette index byte 배열
 * tileVersion은 body가 아니라 X-Tile-Version 헤더로 전달해 raw payload와 버전 메타데이터를 분리
 */
@RestController
@RequestMapping("/api/tiles")
public class TileController {

    private static final String TILE_VERSION_HEADER = "X-Tile-Version";
    private static final String GZIP_ENCODING = "gzip";

    private final TileReadService tileReadService;

    public TileController(TileReadService tileReadService) {
        this.tileReadService = tileReadService;
    }

    /*
     * 현재 MVP는 z=0 original tile만 지원
     * z=1/z=2 downsample tile은 이후 단계로 미루고, 현재는 400으로 명확히 거절
     */
    @GetMapping("/{z}/{tx}/{ty}")
    public ResponseEntity<byte[]> getTile(@PathVariable int z, @PathVariable int tx, @PathVariable int ty) {
        TileReadResult result = tileReadService.readTile(z, tx, ty);
        byte[] gzipped = gzip(result.rawBytes());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_ENCODING, GZIP_ENCODING)
                .header(TILE_VERSION_HEADER, Long.toString(result.tileVersion()))
                .body(gzipped);
    }

    /*
     * 좌표와 z level 오류는 클라이언트 요청 범위 오류이므로 400으로 변환
     * 메모리 보드 누락 같은 IllegalStateException은 서버 불변식 위반이라 여기서 400으로 바꾸지 않음
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    private byte[] gzip(byte[] rawBytes) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
                gzipOutputStream.write(rawBytes);
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException exception) {
            /*
             * ByteArrayOutputStream 기반 압축이더라도 GZIPOutputStream API가 IOException을 노출
             * 압축 실패는 클라이언트 요청 오류가 아니므로 400으로 변환하지 않음
             */
            throw new IllegalStateException("Tile gzip compression failed.", exception);
        }
    }

    public record ErrorResponse(String message) {
    }
}
