package dev.cgt.pixelplace.checkpoint.infra;

import org.springframework.data.jpa.repository.JpaRepository;

// "main" checkpoint row를 읽기 위한 최소 JPA repository
// checkpoint는 WAL replay 시작 범위를 결정하므로, recovery에서 조용히 기본값으로 대체하면 안됨
public interface WalCheckpointJpaRepository extends JpaRepository<WalCheckpointEntity, String> {
}
