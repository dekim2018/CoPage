package com.copage.permission.app;

import com.copage.permission.domain.PageLevel;
import java.util.UUID;

/** 누군가에게 페이지 권한을 주었다 (docs/08-FEATURES.md 10.3 의 페이지 초대 알림, D-50). 받는 쪽은 알림을 보낸다. */
public interface GrantNotices {

    GrantNotices NONE = (actor, pageId, userId, level) -> { };

    void granted(UUID actor, UUID pageId, UUID userId, PageLevel level);
}
