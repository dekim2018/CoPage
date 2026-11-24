package com.copage.identity.in;

import com.copage.identity.app.AuthService;
import com.copage.identity.domain.CursorColors;
import com.copage.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 내 정보 (docs/09-API.md 11.1). */
@RestController
@RequestMapping("/api/me")
class MeController {

    /** 바꿀 것만 담는다. 이름은 비울 수 없고, 색은 정해 둔 12가지 중 하나다 (A-3). */
    record ProfileRequest(@Size(max = 50) String name, String color) {
    }

    private final AuthService auth;

    MeController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    AuthController.UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return AuthController.UserResponse.of(auth.me(UUID.fromString(jwt.getSubject())));
    }

    /** 바꾼 이름과 색은 다음에 여는 편집 연결부터 남에게 보인다. */
    @PatchMapping
    AuthController.UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (request.name() == null && request.color() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "바꿀 것이 없다");
        }
        if (request.name() != null && request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 비울 수 없다");
        }
        if (request.color() != null && !CursorColors.PALETTE.contains(request.color())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "고를 수 있는 색이 아니다");
        }
        User user = auth.me(userId);
        if (request.name() != null) {
            user = auth.rename(userId, request.name());
        }
        if (request.color() != null) {
            user = auth.recolor(userId, request.color());
        }
        return AuthController.UserResponse.of(user);
    }
}
