package com.copage.permission.app;

/**
 * 권한 변경을 듣는 포트 (docs/review/DECISIONS.md D-17). collab 이 구현해 열린 연결의 등급을 다시 계산한다. 알리는 쪽의 요청
 * 스레드에서 불리므로 오래 걸리는 일은 받는 쪽이 다른 스레드로 넘긴다.
 */
public interface PermissionChangeListener {

    void changed(PermissionChange change);
}
