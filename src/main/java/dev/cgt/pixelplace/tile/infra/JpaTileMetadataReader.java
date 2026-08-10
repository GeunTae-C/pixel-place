package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/*
 * 기본 profile runtime bootstrap 판정용 전체 tile key JPA adapter
 * BLOB snapshot loader와 분리하여 매 flush의 대용량 data 조회 방지
 */
@Component
@Profile("!stub")
public class JpaTileMetadataReader implements TileMetadataReader {

    private final TileJpaRepository tileJpaRepository;

    public JpaTileMetadataReader(TileJpaRepository tileJpaRepository) {
        this.tileJpaRepository = tileJpaRepository;
    }

    /* repository projection 결과를 외부 변경 불가능한 key 목록으로 전달 */
    @Override
    public List<TileKey> readAllTileKeys() {
        return List.copyOf(tileJpaRepository.findAllTileKeysOrderByZTyTx());
    }
}
