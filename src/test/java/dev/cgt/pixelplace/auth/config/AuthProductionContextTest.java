package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.*;
import dev.cgt.pixelplace.board.web.BoardController;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.overview.web.OverviewController;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.pixel.web.PixelController;
import dev.cgt.pixelplace.pixel.websocket.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.web.*;
import dev.cgt.pixelplace.tile.application.TileReadService;
import dev.cgt.pixelplace.tile.application.TileReadResult;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.web.TileController;
import dev.cgt.pixelplace.user.application.UserProvisioningService;
import dev.cgt.pixelplace.user.infra.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 YAML·Boot 자동 설정·production 인증/HTTP/WS 동시 연결. DB·write/read 저장 collaborator만 대체 */
class AuthProductionContextTest {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
    @Import({AuthConfiguration.class,AuthRuntimeConfiguration.class,AuthSecurityConfiguration.class,UserProvisioningService.class,
            BoardController.class,TileController.class,OverviewController.class,PixelController.class,PixelWebSocketConfig.class,
            PixelWebSocketHandler.class,PixelWebSocketSessionRegistry.class,ServiceReadiness.class,ReadinessGuardInterceptor.class,
            ReadinessWebConfig.class,ServiceNotReadyExceptionHandler.class})
    @ComponentScan(basePackages={"dev.cgt.pixelplace.auth.jwt","dev.cgt.pixelplace.auth.oauth2","dev.cgt.pixelplace.auth.web"})
    static class WebApplication { }
    @Test void productionConfigurationLoadsCompleteAuthenticationAndJwtPixelTogether() {
        new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(WebApplication.class)
                // 제한 graph에도 production TileController의 공유 계측 의존 제공. 인증/HTTP 의미는 기존 그대로 검증
                .withBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,dev.cgt.pixelplace.measurement.Measurements::disabled)
                .withBean(UserJpaRepository.class,()->mock(UserJpaRepository.class)).withBean(PixelCommandService.class,()->mock(PixelCommandService.class))
                .withBean(TileReadService.class,()->mock(TileReadService.class)).withBean(OverviewService.class,()->mock(OverviewService.class))
                .withPropertyValues("spring.config.import=","KAKAO_CLIENT_ID=c-test-client","KAKAO_CLIENT_SECRET=c-test-secret",
                        "pixel-place.auth.jwt-secret="+AuthConfigurationTest.key(32,'j'),"pixel-place.auth.oauth-cookie-secret="+AuthConfigurationTest.key(32,'k'))
                .run(c->{
                    assertNull(c.getStartupFailure());
                    var registration=c.getBean(ClientRegistrationRepository.class).findByRegistrationId("kakao");
                    assertEquals(c.getBean(OriginPolicy.class).providerCallbackUri().toString(),registration.getRedirectUri());
                    assertEquals(2,c.getBeansOfType(JwtDecoder.class).size());assertEquals(1,c.getBeansOfType(SecurityFilterChain.class).size());
                    assertNotNull(c.getBean(AuthorizationRequestCookieCodec.class));assertNotNull(c.getBean(CookieAuthorizationRequestRepository.class));
                    assertNotNull(c.getBean(KakaoAuthorizationRequestResolver.class));assertNotNull(c.getBean(KakaoOAuth2UserService.class));
                    assertNotNull(c.getBean(OAuthLoginSuccessHandler.class));assertNotNull(c.getBean(HandoffExchangeService.class));
                    assertNotNull(c.getBean(HandoffTokenController.class));assertTrue(c.getBean(WebSocketHandlerMapping.class).getHandlerMap().containsKey("/ws"));
                    var filters=c.getBean(SecurityFilterChain.class).getFilters();
                    assertFalse(filters.stream().anyMatch(f->f instanceof BasicAuthenticationFilter||f instanceof UsernamePasswordAuthenticationFilter||f instanceof DefaultLoginPageGeneratingFilter));
                    var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
                    c.getBean(ServiceReadiness.class).markReady();var tokens=c.getBean(ServiceJwtTokens.class);
                    var command=c.getBean(PixelCommandService.class);when(command.writePixel(42,1,2,3)).thenReturn(new PixelWriteResult(1,new TileKey(0,0,0),1,1,2,3));
                    mvc.perform(post("/api/pixels").header("Authorization","Bearer "+tokens.issueAccess(42).getTokenValue()).header("X-User-Id","999")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}")).andExpect(status().isOk());
                    verify(command).writePixel(42,1,2,3);
                    mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000")
                            .cookie(new jakarta.servlet.http.Cookie(HandoffCookie.NAME,tokens.issueHandoff(42).getTokenValue())))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"));
                    mvc.perform(get("/api/board")).andExpect(status().isOk());
                    when(c.getBean(TileReadService.class).readTile(0,0,0)).thenReturn(new TileReadResult(new byte[65536],17));
                    mvc.perform(get("/api/tiles/0/0/0").header("Origin","http://localhost:3000"))
                            .andExpect(status().isOk()).andExpect(header().string("X-Tile-Version","17"))
                            .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,"X-Tile-Version"))
                            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,"http://localhost:3000"))
                            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,"true"));
                    mvc.perform(get("/api/tiles/0/0/0").header("Authorization","Bearer invalid"))
                            .andExpect(status().isUnauthorized());
                    // public WS는 전역 Bearer filter에서도 JWT 인증·만료 판정 없이 기존 handshake로 연결
                    var socket=(WebSocketHttpRequestHandler)c.getBean(WebSocketHandlerMapping.class).getHandlerMap().get("/ws");
                    var upgrade=mock(RequestUpgradeStrategy.class);
                    when(upgrade.getSupportedVersions()).thenReturn(new String[]{"13"});
                    when(upgrade.getSupportedExtensions(any())).thenReturn(java.util.List.of());
                    doAnswer(call->{
                        assertTrue(call.getArgument(4)==null,"공개 WS에 JWT principal을 공급하면 안 됨");
                        org.springframework.http.server.ServerHttpResponse response=call.getArgument(1);
                        response.setStatusCode(HttpStatus.SWITCHING_PROTOCOLS);return null;
                    }).when(upgrade).upgrade(any(),any(),any(),any(),any(),any(),any());
                    ReflectionTestUtils.setField(socket.getHandshakeHandler(),"requestUpgradeStrategy",upgrade);
                    var expiredTokens=new ServiceJwtTokens(c.getBean(AuthProperties.class),java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofSeconds(-931)));
                    for(String bearer:new String[]{"invalid",tokens.issueAccess(42).getTokenValue(),tokens.issueHandoff(42).getTokenValue(),expiredTokens.issueAccess(42).getTokenValue()}) {
                        mvc.perform(get("/ws").header("Origin","http://localhost:3000").header("Authorization","Bearer "+bearer)
                                .queryParam("access_token",bearer).header("Sec-WebSocket-Protocol","unrecognized-protocol")
                                .header("Upgrade","websocket").header("Connection","Upgrade").header("Sec-WebSocket-Version","13")
                                .header("Sec-WebSocket-Key","dGhlIHNhbXBsZSBub25jZQ=="))
                                .andExpect(status().isSwitchingProtocols());
                    }
                    verify(upgrade,times(4)).upgrade(any(),any(),any(),any(),any(),any(),any());
                    when(c.getBean(OverviewService.class).currentPng()).thenReturn(Optional.of(new byte[]{1,2,3}));
                    mvc.perform(get("/api/overview")).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
                    mvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl(KakaoAuthorizationRequestResolver.START_PATH));
                });
    }
}
