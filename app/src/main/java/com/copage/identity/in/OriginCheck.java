package com.copage.identity.in;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Refresh 쿠키를 쓰는 요청(재발급, 로그아웃)의 Origin 을 직접 검사한다 (docs/07-SECURITY.md 9.2, 진단 SEC-4).
 *
 * <p>지금도 CORS 필터가 허용하지 않은 출처를 막고 쿠키는 SameSite=Strict 다. 다만 그 막힘은 CORS 설정에 기댄 것이라 설정을
 * 바꾸거나 필터를 빼면 조용히 풀린다. 쿠키로 인증하는 경로만큼은 컨트롤러가 한 번 더 본다.
 *
 * <ul>
 *   <li>Origin 이 없으면 받는다. 브라우저는 다른 출처의 POST 에 늘 Origin 을 붙이므로, 없다는 것은 브라우저가 아니거나 같은
 *       출처다
 *   <li>같은 출처(요청의 scheme 과 Host 헤더)면 받는다
 *   <li>허용 목록(copage.web.allowed-origins)에 있으면 받는다
 * </ul>
 */
final class OriginCheck {

    private final List<String> allowed;

    OriginCheck(List<String> allowed) {
        this.allowed = List.copyOf(allowed);
    }

    boolean allows(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host != null && origin.equalsIgnoreCase(request.getScheme() + "://" + host)) {
            return true;
        }
        return allowed.contains(origin);
    }
}
