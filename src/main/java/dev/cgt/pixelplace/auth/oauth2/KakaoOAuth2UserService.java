package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.user.application.UserProvisioningService;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.math.BigInteger;
import java.util.LinkedHashMap;

/** Spring userinfo HTTP와 users provisioning 사이의 ID·실패 경계. 호출 전체 transaction 금지 */
public final class KakaoOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final DefaultOAuth2UserService delegate;
    private final UserProvisioningService users;

    public KakaoOAuth2UserService(DefaultOAuth2UserService delegate, UserProvisioningService users) {
        this.delegate = delegate;
        this.users = users;
        delegate.setAttributesConverter(request -> attributes -> {
            // DefaultOAuth2User 생성보다 먼저 입력 오류를 고정 인증 실패로 전환
            long id = kakaoId(attributes.get("id"));
            var converted = new LinkedHashMap<>(attributes);
            converted.put("id", id);
            return converted;
        });
    }

    /** registration → converter/HTTP → 방어 검증 → 종료된 provisioning 결과 → 내부 principal */
    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        if (!"kakao".equals(request.getClientRegistration().getRegistrationId())) throw invalidUser();
        OAuth2User external = delegate.loadUser(request);
        long kakaoId = kakaoId(external.getAttributes().get("id"));
        final long internalId;
        try {
            internalId = users.provision(kakaoId);
        } catch (RuntimeException failure) {
            // repository transaction 실패만 callback failure handler로 연결. delegate 오류·Error는 숨기지 않음
            // Spring 필터의 ERROR 로그가 cause까지 출력하므로 DB/provider 원문 cause 전달 금지
            throw new InternalAuthenticationServiceException("User provisioning failed");
        }
        return new KakaoPrincipal(internalId, kakaoId);
    }

    private static long kakaoId(Object raw) {
        try {
            long value;
            if (raw instanceof String text && text.matches("[1-9][0-9]*")) value = Long.parseLong(text);
            else if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)
                value = ((Number) raw).longValue();
            else if (raw instanceof BigInteger integer) value = integer.longValueExact();
            else throw invalidUser();
            if (value > 0) return value;
        } catch (NumberFormatException | ArithmeticException ignored) {
            // overflow를 손실 변환하거나 provider 원문을 실패 메시지로 전달하지 않음
            throw invalidUser();
        }
        throw invalidUser();
    }

    private static OAuth2AuthenticationException invalidUser() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info", "Invalid Kakao identity", null));
    }
}
