package com.copage.collab.app;

/** 연결 종료 코드 (docs/05-PROTOCOL.md 7.4). */
public final class CloseCodes {

    /** 티켓이 없거나 만료됨. 티켓을 다시 받아 재접속한다. */
    public static final int TICKET = 4001;
    /** 권한 없음 또는 권한 회수됨. */
    public static final int FORBIDDEN = 4003;
    /** 규약 위반 또는 속도 제한 초과. */
    public static final int PROTOCOL = 4008;
    /** 문서 세션이 옮겨 가거나 버려짐. 즉시 재접속한다. */
    public static final int MOVED = 4009;
    /** 규약 버전 불일치. */
    public static final int VERSION = 4010;
    /** 받는 속도가 느려 보낼 메시지가 너무 쌓임, 또는 보낸 묶음의 기준 리비전이 기록 창보다 오래됨. 재접속해 다시 맞춘다. */
    public static final int SLOW = 4011;

    private CloseCodes() {
    }
}
