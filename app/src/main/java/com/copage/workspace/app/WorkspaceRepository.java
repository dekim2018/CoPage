package com.copage.workspace.app;

import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** 휴지통의 페이지. 휴지통으로 보낸 페이지 자신이다. 그 하위는 부모를 따라 보이지 않을 뿐 따로 보관되지 않는다. */
    record TrashedPage(Page page, Instant archivedAt) {
    }

    /** 워크스페이스를 만들고 만든 사람을 OWNER 로 넣는다. 한 트랜잭션이다. */
    void create(Workspace workspace);

    List<Membership> findByMember(UUID userId);

    Optional<Workspace> findWorkspace(UUID workspaceId);

    Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId);

    List<Member> members(UUID workspaceId);

    /** @return 이미 멤버였으면 false 이고 아무것도 바꾸지 않는다 */
    boolean addMember(UUID workspaceId, UUID userId, WorkspaceRole role);

    /** 역할 변경과 내보내기의 결과. */
    enum MemberUpdate {
        UPDATED,
        NOT_MEMBER,
        /** 마지막 OWNER 를 내리거나 내보내려 했다. 아무것도 바꾸지 않았다 (D-15). */
        LAST_OWNER
    }

    /** 워크스페이스 단위로 잠그고 마지막 OWNER 인지 확인한 뒤 바꾼다. */
    MemberUpdate changeRole(UUID workspaceId, UUID userId, WorkspaceRole role);

    /** 워크스페이스 단위로 잠그고 마지막 OWNER 인지 확인한 뒤 내보낸다. */
    MemberUpdate removeMember(UUID workspaceId, UUID userId);

    /** 제목을 바꾼다. 저장된 title_rev 가 rev 보다 작을 때만 바꾼다. @return 바꿨는지 */
    boolean updateTitle(UUID pageId, String title, long rev);

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

    /**
     * 페이지를 새 부모 아래 afterId 바로 뒤(없으면 맨 뒤)로 옮긴다. 워크스페이스 단위로 잠그고 새 부모의 조상을 거슬러 올라가
     * 자기 자신이나 하위로 옮기려는지 확인한 뒤 바꾼다 (docs/06-DATA-MODEL.md 8.6). 두 사람이 동시에 서로의 아래로 옮겨도
     * 고리가 생기지 않는다.
     *
     * @param newParentId 최상위면 null
     * @throws WorkspaceException NOT_FOUND 페이지나 새 부모가 없거나 보관됨, BAD_REQUEST 자기 자신이나 하위로 옮기려 함,
     *     afterId 가 새 부모의 하위가 아님
     */
    Page movePage(UUID workspaceId, UUID pageId, UUID newParentId, UUID afterId);

    /** 휴지통으로 보낸다. 이미 보관되었거나 없으면 false. */
    boolean archive(UUID workspaceId, UUID pageId, Instant archivedAt);

    /** 휴지통에 있는 페이지 자신(archived_at 이 채워진 것). 하위로 숨겨졌을 뿐인 페이지는 아니다. */
    Optional<TrashedPage> findTrashed(UUID workspaceId, UUID pageId);

    /**
     * 휴지통에서 꺼낸다. 부모나 그 조상이 아직 휴지통에 있으면 최상위 맨 뒤로 옮긴다. 그대로 두면 꺼내도 보이지 않는다.
     *
     * @return 꺼낸 페이지. 휴지통에 없으면 비어 있다
     */
    Optional<Page> restore(UUID workspaceId, UUID pageId);

    /** 워크스페이스의 휴지통. 최근에 보낸 것부터. */
    List<TrashedPage> trash(UUID workspaceId);

    /** 이 페이지와 하위 전부(보관된 것 포함). 권한이 바뀌었을 수 있는 범위를 알릴 때 쓴다. */
    Set<UUID> subtree(UUID pageId);
}
