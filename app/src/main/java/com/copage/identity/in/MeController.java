package com.copage.identity.in;

import com.copage.identity.app.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 정보 (docs/09-API.md 11.1). */
@RestController
@RequestMapping("/api/me")
class MeController {

    record RenameRequest(@NotBlank @Size(max = 50) String name) {
    }

    private final AuthService auth;

    MeController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    AuthController.UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return AuthController.UserResponse.of(auth.me(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping
    AuthController.UserResponse rename(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RenameRequest request) {
        return AuthController.UserResponse.of(auth.rename(UUID.fromString(jwt.getSubject()), request.name()));
    }
}
