package dev.cgt.pixelplace.pixel.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/* assigned eventSeq primary key로 append-only pixel_events persistence를 제공하는 JPA repository */
public interface PixelEventJpaRepository extends JpaRepository<PixelEventEntity, Long> {

    /* 감사 시각과 물리 insert 순서가 아닌 eventSeq 논리 순서의 명시적 조회 */
    @Query("select event from PixelEventEntity event order by event.eventSeq asc")
    List<PixelEventEntity> findAllOrderByEventSeq();
}
