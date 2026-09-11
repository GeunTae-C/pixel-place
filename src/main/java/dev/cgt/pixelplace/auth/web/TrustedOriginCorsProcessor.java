package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import java.io.IOException;
import java.util.Collections;

/** 기본 processor의 same-origin·trailing slash 처리보다 먼저 원본 Origin 문법과 공용 허용 정책 확인 */
public final class TrustedOriginCorsProcessor extends DefaultCorsProcessor {
    private final OriginPolicy origins;
    public TrustedOriginCorsProcessor(OriginPolicy origins) { this.origins=origins; }

    /** Origin 부재는 endpoint 계약으로 위임. 존재하는 불신 입력은 OAuth·인증·cookie 처리 전에 차단 */
    @Override public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        var values=Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (!values.isEmpty() && (values.size()!=1 || !origins.allows(values.getFirst()))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return super.processRequest(configuration,request,response);
    }
}
