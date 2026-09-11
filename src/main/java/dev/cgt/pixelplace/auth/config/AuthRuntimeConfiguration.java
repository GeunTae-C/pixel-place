package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.HandoffExchangeService;
import dev.cgt.pixelplace.user.application.UserProvisioningService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import java.time.Clock;

/** 검증된 인증 기반과 선행 로그인 구성요소의 production 조립. 목적별 decoder와 저장 금지 경계 명시 */
@Configuration(proxyBeanMethods = false)
public class AuthRuntimeConfiguration {
    @Bean ServiceJwtTokens serviceJwtTokens(AuthProperties properties, Clock clock) {
        return new ServiceJwtTokens(properties, clock);
    }
    @Bean("accessJwtDecoder") JwtDecoder accessJwtDecoder(ServiceJwtTokens tokens) { return tokens.accessDecoder(); }
    @Bean("handoffJwtDecoder") JwtDecoder handoffJwtDecoder(ServiceJwtTokens tokens) { return tokens.handoffDecoder(); }
    @Bean AuthorizationRequestCookieCodec authorizationRequestCookieCodec(AuthProperties properties, OriginPolicy origins, Clock clock) {
        return new AuthorizationRequestCookieCodec(properties, origins, clock);
    }
    @Bean CookieAuthorizationRequestRepository authorizationRequests(AuthorizationRequestCookieCodec codec, AuthProperties properties, OriginPolicy origins) {
        return new CookieAuthorizationRequestRepository(codec, properties, origins);
    }
    @Bean HandoffCookie handoffCookie(AuthProperties properties, OriginPolicy origins) { return new HandoffCookie(properties, origins); }
    @Bean OAuthLoginFailureHandler oauthLoginFailureHandler(CookieAuthorizationRequestRepository requests, HandoffCookie cookie, OriginPolicy origins) {
        return new OAuthLoginFailureHandler(requests, cookie, origins);
    }
    @Bean OAuthLoginSuccessHandler oauthLoginSuccessHandler(ServiceJwtTokens tokens, CookieAuthorizationRequestRepository requests,
            HandoffCookie cookie, OAuthLoginFailureHandler failure, OriginPolicy origins) {
        return new OAuthLoginSuccessHandler(tokens, requests, cookie, failure, origins);
    }
    @Bean KakaoAuthorizationRequestResolver kakaoAuthorizationRequestResolver(ClientRegistrationRepository registrations, OriginPolicy origins) {
        return new KakaoAuthorizationRequestResolver(registrations, origins);
    }
    @Bean DefaultOAuth2UserService kakaoUserInfoDelegate() { return new DefaultOAuth2UserService(); }
    @Bean KakaoOAuth2UserService kakaoOAuth2UserService(DefaultOAuth2UserService delegate, UserProvisioningService users) {
        return new KakaoOAuth2UserService(delegate, users);
    }
    @Bean RestClientAuthorizationCodeTokenResponseClient kakaoTokenResponseClient() { return new RestClientAuthorizationCodeTokenResponseClient(); }
    @Bean NoOpAuthorizedClientRepository authorizedClients() { return new NoOpAuthorizedClientRepository(); }
    @Bean NullSecurityContextRepository authenticationContexts() { return new NullSecurityContextRepository(); }
    @Bean NullRequestCache authenticationRequestCache() { return new NullRequestCache(); }
    @Bean HandoffExchangeService handoffExchangeService(@Qualifier("handoffJwtDecoder") JwtDecoder decoder, ServiceJwtTokens tokens, Clock clock) {
        return new HandoffExchangeService(decoder, tokens, clock);
    }
}
