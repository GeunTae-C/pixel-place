package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.oauth2.CookieAuthorizationRequestRepository;
import dev.cgt.pixelplace.auth.oauth2.KakaoAuthorizationRequestResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** CORS 처리 이후 OAuth namespace의 method/raw path 선차단. cookie 저장소와 HTML entry point 우회 방지 */
public final class OAuthSurfaceGuard extends OncePerRequestFilter {
    public static boolean exactCallbackGet(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && (request.getContextPath()+CookieAuthorizationRequestRepository.COOKIE_PATH).equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean exact=path.equals(KakaoAuthorizationRequestResolver.START_PATH)||path.equals(CookieAuthorizationRequestRepository.COOKIE_PATH);
        if(exact && !"GET".equals(request.getMethod())) {
            response.setStatus(405); response.setHeader("Allow","GET"); return;
        }
        if(!exact && (path.equals("/oauth2")||path.startsWith("/oauth2/")||path.equals("/login/oauth2")||path.startsWith("/login/oauth2/"))) {
            // resolver가 null을 반환한 뒤 HTML entry point가 새 로그인을 시작하는 경로 차단
            response.setStatus(404); return;
        }
        chain.doFilter(request,response);
    }
}
