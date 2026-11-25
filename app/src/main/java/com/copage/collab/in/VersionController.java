package com.copage.collab.in;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.RevisionGoneException;
import com.copage.collab.app.VersionLog;
import com.copage.collab.domain.OpJson;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.domain.PageLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * 버전 기록 (docs/09-API.md 11.4, docs/08-FEATURES.md 10.1, V-1, V-2, D-44). 목록과 과거 시점 보기는 VIEW, 이름 붙이기와 복원
 * 기록은 EDIT 다. 복원 자체는 클라이언트가 지금 문서를 과거 시점으로 바꾸는 편집으로 보낸다(보통 편집과 같은 길).
 */
@RestController
class VersionController {

    private static final int PAGE_SIZE = 50;

    record VersionResponse(UUID id, long rev, VersionLog.Kind kind, String name, Instant createdAt, List<String> editors) {
        static VersionResponse of(VersionLog.Version version) {
            return new VersionResponse(version.id(), version.rev(), version.kind(), version.name(), version.createdAt(),
                    version.editors());
        }
    }

    record DocumentAt(long rev, JsonNode document) {
    }

    /** @param restoredFrom 복원이면 되돌아간 시점의 리비전. 이름에 적는다 */
    record RecordRequest(@NotNull VersionLog.Kind kind, @Size(max = 100) String name, Long restoredFrom) {
    }

    private final VersionLog versions;
    private final DocumentStore store;
    private final PagePermissions permissions;

    VersionController(VersionLog versions, DocumentStore store, PagePermissions permissions) {
        this.versions = versions;
        this.store = store;
        this.permissions = permissions;
    }

    @GetMapping("/api/docs/{docId}/versions")
    List<VersionResponse> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId, @RequestParam(required = false) Long before) {
        require(jwt, docId, PageLevel.VIEW);
        return versions.list(docId, before, PAGE_SIZE).stream().map(VersionResponse::of).toList();
    }

    @GetMapping("/api/docs/{docId}/versions/{rev}")
    DocumentAt at(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId, @PathVariable long rev) {
        require(jwt, docId, PageLevel.VIEW);
        if (rev < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리비전은 0 이상이다");
        }
        try {
            return new DocumentAt(rev, OpJson.document(store.loadAt(docId, rev)));
        } catch (RevisionGoneException e) {
            // 연산 로그를 정리한 구간의 시점이다. 남겨 둔 버전의 시점은 열린다 (D-52).
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        }
    }

    /** 지금 시점에 이름을 붙이거나(NAMED), 복원한 직후의 시점을 남긴다(RESTORE). AUTO 는 서버만 남긴다. */
    @PostMapping("/api/docs/{docId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    VersionResponse record(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId, @Valid @RequestBody RecordRequest request) {
        UUID userId = require(jwt, docId, PageLevel.EDIT);
        String name = switch (request.kind()) {
            case AUTO -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 버전은 서버가 남긴다");
            case NAMED -> {
                if (request.name() == null || request.name().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름이 비었다");
                }
                yield request.name().strip();
            }
            case RESTORE -> request.restoredFrom() == null
                    ? "복원"
                    : "리비전 " + request.restoredFrom() + " 으로 복원";
        };
        return VersionResponse.of(versions.record(docId, request.kind(), name, userId));
    }

    private UUID require(Jwt jwt, UUID docId, PageLevel level) {
        UUID userId = UUID.fromString(jwt.getSubject());
        switch (permissions.access(userId, docId)) {
            case Access.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> {
                if (!granted.level().atLeast(level)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 모자란다");
                }
            }
        }
        return userId;
    }
}
