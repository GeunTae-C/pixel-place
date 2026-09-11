package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import dev.cgt.pixelplace.auth.oauth2.KakaoAuthorizationRequestResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import java.io.IOException;
import java.util.Set;

/** filter 계층의 고정 JSON 오류와 non-API HTML 진입 분리. token·decoder 상세 사유의 외부 노출 금지 */
public final class AuthenticationErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final MediaTypeRequestMatcher html=new MediaTypeRequestMatcher(new HeaderContentNegotiationStrategy(),MediaType.TEXT_HTML);
    private final OriginPolicy origins;
    public AuthenticationErrors(OriginPolicy origins) {
        this.origins=origins; html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
    }

    /** API는 Accept와 무관한 401. 같은 Origin의 callback 목적지를 재로그인으로 순환시키면 안 됨 */
    @Override public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean callback=origins.frontendOrigin().equals(origins.publicApiOrigin())
                && origins.frontendAuthCallbackUri().getRawPath().equals(path);
        if (!path.equals("/api") && !path.startsWith("/api/") && !callback && html.matches(request)) {
            response.setHeader("Referrer-Policy","no-referrer");
            response.sendRedirect(request.getContextPath()+KakaoAuthorizationRequestResolver.START_PATH);
            return;
        }
        bearerUnauthorized(request,response,failure);
    }

    /** Resource Server가 명시적 Bearer를 거부한 응답. 상세 challenge description 대신 scheme 보존 */
    public void bearerUnauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
        write(response,401,"Unauthorized.");
    }
    @Override public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
        write(response,403,"Forbidden.");
    }
    private static void write(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
