package dev.cgt.pixelplace.auth.oauth2;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 카카오 ID와 내부 users.id를 분리한 최소 principal. 원본 profile·provider token 보관 책임 없음 */
public final class KakaoPrincipal implements OAuth2User {
    private final long internalUserId;
    private final long kakaoUserId;

    public KakaoPrincipal(long internalUserId, long kakaoUserId) {
        if (internalUserId <= 0 || kakaoUserId <= 0) {
            // JWT subject에 외부 ID나 비정상 저장 결과가 전달되지 않도록 경계 고정
            throw new IllegalArgumentException("Invalid login identity");
        }
        this.internalUserId = internalUserId;
        this.kakaoUserId = kakaoUserId;
    }

    public long internalUserId() { return internalUserId; }
    public long kakaoUserId() { return kakaoUserId; }
    /** 인증 이름과 JWT subject의 공급원은 내부 ID */
    @Override public String getName() { return Long.toString(internalUserId); }
    @Override public Map<String, Object> getAttributes() { return Map.of("id", kakaoUserId); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("OAUTH2_USER"));
    }
}
