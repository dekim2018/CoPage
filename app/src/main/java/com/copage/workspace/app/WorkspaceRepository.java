package com.copage.workspace.app;

import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 워크스페이스, 멤버, 페이지 저장소 포트. */
public interface WorkspaceRepository {

    record Membership(Workspace workspace, WorkspaceRole role) {
    }

    record Member(UUID userId, WorkspaceRole role) {
    }

    /** @param hasChildren 보관되지 않은 하위 페이지가 있는지. 사이드바가 펼침 단추를 그릴지 정한다 */
    record PageSummary(Page page, boolean hasChildren) {
    }

    /** 워크스페이스를 만들고 만든 사람을 OWNER 로 넣는다. 한 트랜잭션이다. */
    void create(Workspace workspace);

    List<Membership> findByMember(UUID userId);

    Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId);

    List<Member> members(UUID workspaceId);

    /** @return 이미 멤버였으면 false 이고 아무것도 바꾸지 않는다 */
    boolean addMember(UUID workspaceId, UUID userId, WorkspaceRole role);

    /** 보관되지 않은 페이지. 다른 워크스페이스의 페이지면 비어 있다. */
    Optional<Page> findPage(UUID workspaceId, UUID pageId);

    /** 보관되지 않은 페이지들. 없는 ID 는 뺀다. 순서는 정하지 않는다. */
    List<Page> findPages(UUID workspaceId, Collection<UUID> pageIds);

    /** parentId 의 한 단계 하위 페이지를 순서대로. 보관된 페이지는 뺀다. parentId 가 null 이면 최상위 페이지다. */
    List<PageSummary> children(UUID workspaceId, UUID parentId);

    /**
     * 페이지를 만든다. 순서 키는 afterId 바로 뒤(없으면 형제의 맨 뒤)다. 워크스페이스 단위로 잠그고 부모와 앞 형제를 확인한 뒤
     * 넣으므로, 동시에 같은 자리에 만들어도 키가 겹치지 않는다.
     *
     * @throws WorkspaceException NOT_FOUND 부모가 없거나 보관됨, BAD_REQUEST afterId 가 그 부모의 하위가 아님
     */
    Page createPage(UUID workspaceId, UUID pageId, UUID parentId, UUID afterId, UUID createdBy, Instant createdAt);
}
