package dev.cgt.pixelplace.user.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** repository proxy 호출별 transaction 경계. 실패한 insert 이후 새 조회 snapshot 확보 */
public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    // 선언 query에는 CRUD의 기본 transaction이 자동 적용되지 않으므로 명시
    @Transactional(readOnly = true)
    Optional<UserEntity> findByKakaoUserId(long kakaoUserId);

    @Override
    @Transactional
    <S extends UserEntity> S saveAndFlush(S entity);
}
