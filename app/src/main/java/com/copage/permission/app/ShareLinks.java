package com.copage.permission.app;

import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.app.PermissionException.Kind;
import com.copage.permission.app.ShareLinkRepository.ShareLink;
import com.copage.permission.domain.PageLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 페이지 공유 링크 (docs/09-API.md 11.3, docs/07-SECURITY.md 9.4 의 4번, D-41).
 *
 * <ul>
 *   <li>링크를 가진 사람은 로그인하지 않아도 그 페이지 하나를 링크의 등급(VIEW, COMMENT, EDIT)으로 연다. 하위 페이지로는 내려가지
 *       않는다. 로그인한 사람은 자기 등급과 링크 등급 중 높은 것을 받는다
 *   <li>설정은 그 페이지의 FULL 만 바꾼다. 토큰은 무작위 32 바이트로, 만들거나 새로 만들 때만 돌려주고 해시만 둔다
 *   <li>끄거나, 새로 만들거나, 등급과 만료일을 바꾸면 그 페이지를 연 연결이 등급을 다시 계산한다. 링크로 들어온 연결은 링크가
 *       사라졌으면 닫힌다
 *   <li>페이지나 조상이 휴지통에 있으면 링크로도 열 수 없다
 * </ul>
 */
public final class ShareLinks {

    /** 링크를 만들거나 바꾼 결과. token 은 새로 만들었을 때만 있다. */
    public record Saved(ShareLink link, String token) {
    }

    /** 링크로 연 페이지. */
    public record Opened(ShareLink link, String title) {
    }

    private final ShareLinkRepository links;
    private final PagePermissions permissions;
    private final Consumer<PermissionChange> publisher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ShareLinks(
            ShareLinkRepository links, PagePermissions permissions, Consumer<PermissionChange> publisher, Clock clock) {
        this.links = links;
        this.permissions = permissions;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Optional<ShareLink> get(UUID actor, UUID pageId) {
        requireFull(actor, pageId);
        return links.findByPage(pageId);
    }

    /**
     * 링크를 켜거나 바꾼다. 링크가 없거나 regenerate 면 새 토큰을 만든다(예전 링크는 더 쓸 수 없다).
     *
     * @param expiresAt 없으면 끌 때까지
     */
    public Saved save(UUID actor, UUID pageId, PageLevel level, Instant expiresAt, boolean regenerate) {
        requireFull(actor, pageId);
        if (level == PageLevel.FULL) {
            throw new PermissionException(Kind.BAD_REQUEST, "링크로는 전체 권한을 줄 수 없다");
        }
        Instant now = now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new PermissionException(Kind.BAD_REQUEST, "만료일이 이미 지났다");
        }
        Optional<ShareLink> existing = links.findByPage(pageId);
        Saved saved;
        if (existing.isEmpty() || regenerate) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            ShareLink link = new ShareLink(UUID.randomUUID(), pageId, level, expiresAt, actor, now);
            links.replace(link, hash(token));
            saved = new Saved(link, token);
        } else {
            links.update(pageId, level, expiresAt);
            ShareLink old = existing.get();
            saved = new Saved(new ShareLink(old.id(), pageId, level, expiresAt, old.createdBy(), old.createdAt()), null);
        }
        publisher.accept(new PermissionChange(Set.of(pageId), null));
        return saved;
    }

    public void disable(UUID actor, UUID pageId) {
        requireFull(actor, pageId);
        if (links.delete(pageId)) {
            publisher.accept(new PermissionChange(Set.of(pageId), null));
        }
    }

    /**
     * 토큰으로 링크를 연다. 없거나, 만료되었거나, 페이지가 휴지통에 있으면 NOT_FOUND. 누구나 부를 수 있다.
     */
    public Opened open(String token) {
        ShareLink link = links.findByTokenHash(hash(token))
                .filter(found -> found.liveAt(now()) && !links.trashedOrMissing(found.pageId()))
                .orElseThrow(() -> new PermissionException(Kind.NOT_FOUND, "링크가 없거나 만료되었다"));
        return new Opened(link, links.title(link.pageId()));
    }

    /**
     * 링크로 열린 연결의 등급을 다시 계산할 때 쓴다. 링크가 그대로 있고(새로 만들면 ID 가 바뀐다), 그 페이지의 것이고, 만료되지
     * 않았고, 페이지가 휴지통에 없으면 링크의 등급.
     */
    public Optional<PageLevel> levelOf(UUID linkId, UUID pageId) {
        return links.findById(linkId)
                .filter(link -> link.pageId().equals(pageId) && link.liveAt(now()) && !links.trashedOrMissing(pageId))
                .map(ShareLink::level);
    }

    private void requireFull(UUID actor, UUID pageId) {
        switch (permissions.access(actor, pageId)) {
            case Access.NotFound notFound -> throw new PermissionException(Kind.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new PermissionException(Kind.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> {
                if (!granted.level().atLeast(PageLevel.FULL)) {
                    throw new PermissionException(Kind.FORBIDDEN, "공유 설정은 전체 권한이 있어야 바꿀 수 있다");
                }
            }
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
