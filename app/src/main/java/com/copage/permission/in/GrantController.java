package com.copage.permission.in;

import com.copage.permission.app.PageGrants;
import com.copage.permission.domain.PageLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 페이지 공유와 권한 (docs/09-API.md 11.3). 모두 그 페이지의 FULL 이 필요하다. */
@RestController
@RequestMapping("/api/pages/{pageId}")
class GrantController {

    record LevelRequest(@NotNull PageLevel level) {
    }

    record EmailGrantRequest(@NotBlank @Email String email, @NotNull PageLevel level) {
    }

    record RestrictedRequest(@NotNull Boolean restricted) {
    }

    record RestrictedResponse(boolean restricted) {
    }

    private final PageGrants grants;

    GrantController(PageGrants grants) {
        this.grants = grants;
    }

    @GetMapping("/grants")
    List<PageGrants.GrantView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        return grants.list(userId(jwt), pageId);
    }

    @PutMapping("/grants/{userId}")
    PageGrants.GrantView grant(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID pageId,
            @PathVariable UUID userId,
            @Valid @RequestBody LevelRequest request) {
        return grants.grant(userId(jwt), pageId, userId, request.level());
    }

    /** 이메일로 준다. 화면의 공유 창이 쓴다. */
    @PostMapping("/grants")
    PageGrants.GrantView grantByEmail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID pageId,
            @Valid @RequestBody EmailGrantRequest request) {
        return grants.grantByEmail(userId(jwt), pageId, request.email(), request.level());
    }

    @DeleteMapping("/grants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId, @PathVariable UUID userId) {
        grants.revoke(userId(jwt), pageId, userId);
    }

    @PutMapping("/restricted")
    RestrictedResponse restricted(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID pageId,
            @Valid @RequestBody RestrictedRequest request) {
        grants.setRestricted(userId(jwt), pageId, request.restricted());
        return new RestrictedResponse(request.restricted());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
