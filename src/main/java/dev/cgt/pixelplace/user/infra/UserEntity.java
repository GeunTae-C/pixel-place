package dev.cgt.pixelplace.user.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 카카오 식별자와 내부 ID의 최소 매핑. 개인정보·provider token 저장 책임 없음 */
@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kakao_user_id", nullable = false, unique = true, updatable = false)
    private long kakaoUserId;

    // DB DATETIME(3) 기본값·ON UPDATE가 소유. INSERT의 NULL로 기본값을 덮어쓰지 않음
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected UserEntity() { }

    public UserEntity(long kakaoUserId) {
        if (kakaoUserId <= 0) throw new IllegalArgumentException("Kakao user id must be positive");
        this.kakaoUserId = kakaoUserId;
    }

    public Long getId() { return id; }
    public long getKakaoUserId() { return kakaoUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
