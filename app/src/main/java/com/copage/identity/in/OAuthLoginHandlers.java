package com.copage.identity.in;

import com.copage.identity.app.AuthException;
import com.copage.identity.app.AuthService;
import com.copage.identity.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 구글, 깃허브 로그인이 끝났을 때 (A-4, D-54). 성공하면 비밀번호 로그인과 같은 Refresh 쿠키를 주고 화면 첫 주소로 보낸다. 화면은
 * 뜰 때 그 쿠키로 Access 토큰을 받는다(새로고침과 같은 길). 실패하면 로그인 화면으로 까닭을 실어 보낸다.
 */
public final class OAuthLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final System.Logger LOG = System.getLogger(OAuthLoginHandlers.class.getName());

    private final AuthService auth;
    private final AuthProperties properties;

    public OAuthLoginHandlers(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            response.sendRedirect("/login?oauthError=failed");
            return;
        }
        try {
            AuthService.Session session = auth.loginWithProvider(login(token));
            response.addHeader(HttpHeaders.SET_COOKIE,
                    AuthController.refreshCookie(properties, session.refreshToken()).toString());
            response.sendRedirect("/");
        } catch (AuthException e) {
            response.sendRedirect("/login?oauthError=" + e.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // 사용자가 동의 화면에서 취소했거나, 상태 값이 맞지 않거나(오래되었거나 꾸민 요청), 서비스에 닿지 못했다.
        LOG.log(System.Logger.Level.INFO, "다른 서비스 계정 로그인 실패: " + exception.getMessage());
        response.sendRedirect("/login?oauthError=failed");
    }

    /** 서비스마다 다른 사용자 정보를 한 모양으로. 구글은 OIDC 클레임, 깃허브는 OAuthUserService 가 채운 속성이다. */
    static AuthService.ProviderLogin login(OAuth2AuthenticationToken token) {
        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2User user = token.getPrincipal();
        if (user instanceof OidcUser oidc) {
            return new AuthService.ProviderLogin(provider, oidc.getSubject(), oidc.getEmail(),
                    Boolean.TRUE.equals(oidc.getEmailVerified()), oidc.getFullName());
        }
        Map<String, Object> attributes = user.getAttributes();
        Object name = attributes.get("name") != null ? attributes.get("name") : attributes.get("login");
        return new AuthService.ProviderLogin(provider, user.getName(),
                attributes.get("email") instanceof String email ? email : null,
                Boolean.TRUE.equals(attributes.get("email_verified")),
                name == null ? null : name.toString());
    }
}
