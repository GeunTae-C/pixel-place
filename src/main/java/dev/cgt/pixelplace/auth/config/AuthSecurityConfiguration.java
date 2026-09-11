package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.AuthenticationErrors;
import dev.cgt.pixelplace.auth.web.TrustedOriginCorsProcessor;
import dev.cgt.pixelplace.auth.web.OAuthSurfaceGuard;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
import java.util.List;

/** OAuth 로그인과 Access Bearer의 단일 production chain. 서버 세션·provider token 저장 및 기본 login UI 금지 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AuthSecurityConfiguration {
    @Bean AuthenticationErrors authenticationErrors(OriginPolicy origins) { return new AuthenticationErrors(origins); }

    /** 인증은 MVC readiness보다 선행. ERROR는 원래 서버 오류 보존, FORWARD에는 일반 접근 정책 유지 */
    @Bean public SecurityFilterChain authenticationChain(HttpSecurity http, OriginPolicy origins,
            @Qualifier("accessJwtDecoder") JwtDecoder accessDecoder,
            CookieAuthorizationRequestRepository requests, KakaoAuthorizationRequestResolver resolver,
            KakaoOAuth2UserService users, NoOpAuthorizedClientRepository clients,
            RestClientAuthorizationCodeTokenResponseClient tokenClient,
            OAuthLoginSuccessHandler success, OAuthLoginFailureHandler failure,
            NullSecurityContextRepository contexts, NullRequestCache cache, AuthenticationErrors errors) throws Exception {
        var headerBearer=new DefaultBearerTokenResolver();
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .rememberMe(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                // API는 header Bearer만 사용. 교환은 strict Origin, OAuth는 state·PKCE로 보호
                .csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(s -> s.securityContextRepository(contexts)).requestCache(c -> c.requestCache(cache))
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(r -> r.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/board","/api/tiles/**","/api/overview").permitAll()
                        .requestMatchers(HttpMethod.GET,KakaoAuthorizationRequestResolver.START_PATH,CookieAuthorizationRequestRepository.COOKIE_PATH,"/ws").permitAll()
                        .requestMatchers(HttpMethod.POST,HandoffCookie.PATH).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2Login(o -> o.loginPage(KakaoAuthorizationRequestResolver.START_PATH).authorizedClientRepository(clients)
                        .authorizationEndpoint(a -> a.authorizationRequestRepository(requests).authorizationRequestResolver(resolver))
                        .userInfoEndpoint(u -> u.userService(users)).tokenEndpoint(t -> t.accessTokenResponseClient(tokenClient))
                        .successHandler(success).failureHandler(failure)
                        .withObjectPostProcessor(new OAuthStartFilterConfigurer(failure))
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                            @Override public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                                filter.setRequiresAuthenticationRequestMatcher(OAuthSurfaceGuard::exactCallbackGet);
                                return filter;
                            }
                        }))
                .oauth2ResourceServer(r -> r.jwt(j -> j.decoder(accessDecoder))
                        // 공개 broadcast handshake에 JWT principal·만료 인증을 도입하지 않는 경계
                        .bearerTokenResolver(request -> "GET".equals(request.getMethod())
                                && (request.getContextPath()+"/ws").equals(request.getRequestURI())
                                ? null : headerBearer.resolve(request))
                        .authenticationEntryPoint(errors::bearerUnauthorized).accessDeniedHandler(errors));
        // servlet bean으로 등록하지 않아 container/Security chain 중복 실행 방지
        var configuration=new CorsConfiguration();
        configuration.setAllowedOrigins(origins.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET","POST","OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION,HttpHeaders.CONTENT_TYPE));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-Tile-Version"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",configuration);
        var cors=new CorsFilter(source); cors.setCorsProcessor(new TrustedOriginCorsProcessor(origins));
        http.addFilterAt(cors,CorsFilter.class);
        http.addFilterAfter(new OAuthSurfaceGuard(),CorsFilter.class);
        return http.build();
    }
}
