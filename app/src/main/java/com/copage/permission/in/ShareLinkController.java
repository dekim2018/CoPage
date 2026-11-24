package com.copage.permission.in;

import com.copage.permission.app.ShareLinkRepository.ShareLink;
import com.copage.permission.app.ShareLinks;
import com.copage.permission.domain.PageLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 페이지 공유 링크 (docs/09-API.md 11.3, D-41). 설정은 FULL, 링크로 열기는 누구나. */
@RestController
class ShareLinkController {

    /**
     * @param expiresAt 없으면 끌 때까지
     * @param regenerate 새 토큰을 만든다. 예전 링크는 더 쓸 수 없다
     */
    record SaveRequest(@NotNull PageLevel level, Instant expiresAt, boolean regenerate) {
    }

    /** @param token 새로 만들었을 때만 있다. 링크는 /s/{token} */
    record ShareLinkResponse(PageLevel level, Instant expiresAt, Instant createdAt, String token) {
        static ShareLinkResponse of(ShareLink link, String token) {
            return new ShareLinkResponse(link.level(), link.expiresAt(), link.createdAt(), token);
        }
    }

    record SharedPage(UUID pageId, String title, PageLevel level) {
    }

    private final ShareLinks links;

    ShareLinkController(ShareLinks links) {
        this.links = links;
    }

    /** 링크가 없으면 204. 토큰은 돌려주지 않는다(해시만 있다). */
    @GetMapping("/api/pages/{pageId}/share-link")
    ResponseEntity<ShareLinkResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        return links.get(userId(jwt), pageId)
                .map(link -> ResponseEntity.ok(ShareLinkResponse.of(link, null)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/api/pages/{pageId}/share-link")
    ShareLinkResponse save(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId, @Valid @RequestBody SaveRequest request) {
        ShareLinks.Saved saved =
                links.save(userId(jwt), pageId, request.level(), request.expiresAt(), request.regenerate());
        return ShareLinkResponse.of(saved.link(), saved.token());
    }

    @DeleteMapping("/api/pages/{pageId}/share-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        links.disable(userId(jwt), pageId);
    }

    /** 링크로 연 페이지의 정보. 로그인하지 않아도 된다. */
    @GetMapping("/api/share/{token}")
    SharedPage open(@PathVariable String token) {
        ShareLinks.Opened opened = links.open(token);
        return new SharedPage(opened.link().pageId(), opened.title(), opened.link().level());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
