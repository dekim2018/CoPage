package com.copage.permission.app;

import com.copage.permission.domain.PageLevel;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 공유 링크 저장소 포트 (D-41). */
public interface ShareLinkRepository {

    /**
     * 공유 링크. 토큰은 들고 있지 않는다(해시만 저장한다).
     *
     * @param expiresAt 없으면 null
     */
    record ShareLink(UUID id, UUID pageId, PageLevel level, Instant expiresAt, UUID createdBy, Instant createdAt) {

        public boolean liveAt(Instant now) {
            return expiresAt == null || now.isBefore(expiresAt);
        }
    }

    /** 페이지의 링크를 새로 만들거나 바꾼다. 같은 페이지의 예전 링크(와 토큰)는 사라진다. */
    void replace(ShareLink link, String tokenHash);

    /** 등급과 만료일만 바꾼다. 토큰은 그대로다. @return 바꿨는지(링크가 있었는지) */
    boolean update(UUID pageId, PageLevel level, Instant expiresAt);

    Optional<ShareLink> findByPage(UUID pageId);

    Optional<ShareLink> findByTokenHash(String tokenHash);

    Optional<ShareLink> findById(UUID id);

    /** @return 지웠는지 */
    boolean delete(UUID pageId);

    /** 페이지 자신이나 그 조상이 휴지통에 있는지. 없는 페이지도 true. 휴지통의 페이지는 링크로도 볼 수 없다. */
    boolean trashedOrMissing(UUID pageId);

    /** 링크로 연 사람에게 보일 페이지 제목. */
    String title(UUID pageId);
}
