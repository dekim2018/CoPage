package com.copage.collab.app;

import java.util.UUID;

/**
 * 사용자 이름을 묻는 포트. 접속자 목록과 남의 커서에 보일 이름을 티켓에 담을 때 쓴다 (docs/05-PROTOCOL.md 7.6). 협업 모듈은
 * 계정 모듈을 직접 참조하지 않고, 설정이 둘을 잇는다.
 */
public interface UserNames {

    String nameOf(UUID userId);

    /** 프로필에서 고른 커서 색. 없으면 null (A-3). */
    default String colorOf(UUID userId) {
        return null;
    }
}
