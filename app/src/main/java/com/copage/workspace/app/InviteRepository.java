package com.copage.workspace.app;

import com.copage.workspace.domain.Invite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 워크스페이스 초대 저장소 포트. */
public interface InviteRepository {

    void create(Invite invite, String tokenHash);

    Optional<Invite> findByTokenHash(String tokenHash);

    /** 받지 않았고 만료되지 않은 초대. 최근에 만든 것부터. */
    List<Invite> pending(UUID workspaceId, Instant now);

    /** 받지 않은 초대를 지운다. @return 지웠는지 */
    boolean revoke(UUID workspaceId, UUID inviteId);

    /** 받았다고 적는다. 이미 받았으면 아무것도 바꾸지 않는다. @return 바꿨는지 */
    boolean markAccepted(UUID inviteId, UUID userId, Instant at);

    /** 만료된 초대를 지운다(받은 것도). 만료되면 어느 쪽이든 쓸 수 없다 (W-4, D-47). @return 지운 수 */
    int purgeExpired(Instant now);
}
