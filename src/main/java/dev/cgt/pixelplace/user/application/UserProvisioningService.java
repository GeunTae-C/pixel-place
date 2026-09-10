package dev.cgt.pixelplace.user.application;

import dev.cgt.pixelplace.user.infra.UserEntity;
import dev.cgt.pixelplace.user.infra.UserJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** UNIQUE 경쟁을 repository transaction 바깥에서 조정. HTTP 성공 전에 insert 확정 필요 */
@Service
public class UserProvisioningService {
    private final UserJpaRepository repository;

    public UserProvisioningService(UserJpaRepository repository) { this.repository = repository; }

    /** 같은 kakao ID를 기존 또는 새 내부 ID로 수렴. 호출자 ambient transaction 금지 */
    public long provision(long kakaoUserId) {
        if (kakaoUserId <= 0) throw new IllegalArgumentException("Kakao user id must be positive");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 외부 transaction에 합류하면 실패 rollback-only와 재조회 snapshot이 분리되지 않음
            throw new IllegalStateException("User provisioning requires no ambient transaction");
        }
        var existing = repository.findByKakaoUserId(kakaoUserId);
        if (existing.isPresent()) return internalId(existing.get());
        try {
            return internalId(repository.saveAndFlush(new UserEntity(kakaoUserId)));
        } catch (DataIntegrityViolationException conflict) {
            // insert proxy의 rollback 완료 뒤 같은 카카오 row가 확인된 경우만 재사용
            return repository.findByKakaoUserId(kakaoUserId).map(UserProvisioningService::internalId)
                    .orElseThrow(() -> conflict);
        }
    }

    private static long internalId(UserEntity user) {
        if (user.getId() == null || user.getId() <= 0) {
            throw new IllegalStateException("Stored user id must be positive");
        }
        return user.getId();
    }
}
