package com.copage.workspace.app;

import java.time.Instant;

/**
 * 초대 메일을 보낸다 (W-1, D-50). 메일 서버를 설정하지 않으면 보내지 않고(NONE), OWNER 가 링크를 직접 건넨다(D-40).
 */
public interface InviteMailer {

    InviteMailer NONE = new InviteMailer() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void send(Invitation invitation) {
        }
    };

    /** @param token 초대 링크의 토큰. 메일 본문의 링크를 만든다 */
    record Invitation(String email, String workspaceName, String inviterName, String token, Instant expiresAt) {
    }

    boolean enabled();

    /** 보내기를 맡긴다. 메일 서버의 응답을 기다리지 않는다. 실패는 로그로 남긴다. */
    void send(Invitation invitation);
}
