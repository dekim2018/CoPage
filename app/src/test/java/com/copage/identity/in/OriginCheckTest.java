package com.copage.identity.in;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 쿠키로 인증하는 경로의 Origin 검사 (진단 SEC-4). CORS 설정과 따로 동작한다. */
class OriginCheckTest {

    private final OriginCheck check = new OriginCheck(List.of("http://localhost:5173"));

    private static MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.setScheme("https");
        request.addHeader("Host", "copage.example");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    @Test
    void Origin_이_없거나_같은_출처거나_허용_목록에_있으면_받는다() {
        assertTrue(check.allows(request(null)));
        assertTrue(check.allows(request("https://copage.example")));
        assertTrue(check.allows(request("http://localhost:5173")));
    }

    @Test
    void 다른_출처는_막는다() {
        assertFalse(check.allows(request("https://evil.example")));
        assertFalse(check.allows(request("http://copage.example")), "scheme 이 다르면 다른 출처다");
        assertFalse(check.allows(request("https://copage.example.evil.example")));
        assertFalse(check.allows(request("null")), "샌드박스 iframe 의 Origin");
    }
}
