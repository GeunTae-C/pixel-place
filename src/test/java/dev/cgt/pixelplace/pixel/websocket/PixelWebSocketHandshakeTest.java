package dev.cgt.pixelplace.pixel.websocket;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.support.*;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 /ws 등록→Origin interceptor→기본 handshake→기존 handler 검증. container upgrade만 대체, 인증 key 불필요 */
class PixelWebSocketHandshakeTest {
    @ParameterizedTest
    @ValueSource(strings={"http://localhost:3000","http://localhost","http://localhost:80","https://localhost","https://localhost:443","http://localhost:8080","http://[::1]:3000"})
    void registeredHandshakeEnforcesSharedOriginPolicyAndKeepsPublicHandler(String frontend) {
        var front=URI.create(frontend);String api=front.getScheme()+"://"+front.getHost()+":"+(front.getScheme().equals("https")?8443:8080);
        var origins=new OriginPolicy(frontend,api,frontend+"/",api+"/login/oauth2/code/kakao",front.getScheme().equals("https"));
        new WebApplicationContextRunner().withUserConfiguration(PixelWebSocketConfig.class)
                .withBean(OriginPolicy.class,()->origins)
                .withBean(PixelWebSocketHandler.class,()->new PixelWebSocketHandler(new PixelWebSocketSessionRegistry()))
                .run(c->{
                    assertNull(c.getStartupFailure());
                    var mapping=c.getBean(WebSocketHandlerMapping.class);
                    assertEquals(Set.of("/ws"),mapping.getHandlerMap().keySet());
                    var requestHandler=(WebSocketHttpRequestHandler)mapping.getHandlerMap().get("/ws");
                    assertSame(c.getBean(PixelWebSocketHandler.class),WebSocketHandlerDecorator.unwrap(requestHandler.getWebSocketHandler()));
                    assertInstanceOf(FrontendOriginHandshakeInterceptor.class,requestHandler.getHandshakeInterceptors().getFirst());
                    var upgrade=mock(RequestUpgradeStrategy.class);
                    when(upgrade.getSupportedVersions()).thenReturn(new String[]{"13"});
                    when(upgrade.getSupportedExtensions(any())).thenReturn(List.of());
                    doAnswer(call->{
                        org.springframework.http.server.ServerHttpResponse response=call.getArgument(1);response.setStatusCode(HttpStatus.SWITCHING_PROTOCOLS);
                        WebSocketHandler handler=call.getArgument(5);assertSame(c.getBean(PixelWebSocketHandler.class),WebSocketHandlerDecorator.unwrap(handler));return null;
                    }).when(upgrade).upgrade(any(),any(),any(),any(),any(),any(),any());
                    ReflectionTestUtils.setField(requestHandler.getHandshakeHandler(),"requestUpgradeStrategy",upgrade);
                    var allowed=new ArrayList<>(origins.allowedOrigins());allowed.add(frontend.toUpperCase(Locale.ROOT));allowed.add(null);
                    for(String origin:allowed) {
                        clearInvocations(upgrade);var request=request(api);if(origin!=null)request.addHeader("Origin",origin);
                        var response=new MockHttpServletResponse();requestHandler.handleRequest(request,response);
                        assertEquals(101,response.getStatus());verify(upgrade).upgrade(any(),any(),any(),any(),any(),any(),any());
                    }
                    var rejected=new ArrayList<>(List.of(frontend+"/",frontend+"?q=x",frontend+"#x","null",frontend+", "+frontend,
                            front.getScheme()+"://user@"+front.getRawAuthority(),front.getScheme()+"://other.example:3000"));
                    rejected.add(front.getScheme()+"://"+front.getHost()+":"+(origins.frontendOrigin().port()+1));
                    rejected.add((front.getScheme().equals("http")?"https":"http")+"://"+front.getRawAuthority());
                    if(!origins.allows(api))rejected.add(api);
                    // scheme·port를 유지한 하위 도메인 거부 검증. IPv6 literal은 기존 입력 유지
                    if ("localhost".equals(front.getHost())) {
                        rejected.add(front.getScheme() + "://sub." + front.getRawAuthority());
                    }
                    for(String origin:rejected) {
                        clearInvocations(upgrade);var request=request(api);request.addHeader("Origin",origin);var response=new MockHttpServletResponse();
                        requestHandler.handleRequest(request,response);assertEquals(403,response.getStatus());verifyNoInteractions(upgrade);
                    }
                    var duplicate=request(api);duplicate.addHeader("Origin",frontend);duplicate.addHeader("Origin",frontend);
                    var response=new MockHttpServletResponse();clearInvocations(upgrade);requestHandler.handleRequest(duplicate,response);
                    assertEquals(403,response.getStatus());verifyNoInteractions(upgrade);
                    // Origin 부재를 handshake 유효성 면제로 사용하지 않는 경계
                    var invalidUpgrade=request(api);invalidUpgrade.removeHeader("Upgrade");response=new MockHttpServletResponse();
                    requestHandler.handleRequest(invalidUpgrade,response);assertEquals(400,response.getStatus());verify(upgrade,never()).upgrade(any(),any(),any(),any(),any(),any(),any());
                });
    }
    private static MockHttpServletRequest request(String api) {
        var uri=URI.create(api);var request=new MockHttpServletRequest("GET","/ws");request.setServletPath("/ws");
        request.setScheme(uri.getScheme());request.setSecure(uri.getScheme().equals("https"));request.setServerName(uri.getHost());request.setServerPort(uri.getPort());
        request.addHeader("Upgrade","websocket");request.addHeader("Connection","Upgrade");request.addHeader("Sec-WebSocket-Version","13");
        request.addHeader("Sec-WebSocket-Key","dGhlIHNhbXBsZSBub25jZQ==");return request;
    }
}
