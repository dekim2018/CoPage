package com.copage.workspace.domain;

/** 워크스페이스 역할 (docs/07-SECURITY.md 9.4). */
public enum WorkspaceRole {
    /** 워크스페이스 삭제, 멤버 관리, 모든 페이지 전체 권한. */
    OWNER,
    /** 페이지 생성, 제한되지 않은 페이지 편집. */
    MEMBER,
    /** 초대받은 페이지만 접근. 기본 권한이 없다. */
    GUEST;

    public boolean managesMembers() {
        return this == OWNER;
    }

    /** 페이지 권한을 따로 받지 않아도 워크스페이스의 페이지를 본다. GUEST 는 권한을 받은 페이지만 본다. */
    public boolean seesAllPages() {
        return this != GUEST;
    }
}
