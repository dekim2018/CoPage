package com.copage.collab.app;

import java.util.UUID;

/**
 * 문서 소유 리스 (docs/03-ARCHITECTURE.md 5.3 의 2번, ADR 0004). 한 문서의 세션은 리스를 쥔 노드 하나에만 있다. 리스는 빠른
 * 길잡이일 뿐 정합성의 마지막 방어선은 연산 로그의 기본 키다(ADR 0013). 리스가 겹치는 짧은 순간에도 문서는 갈라지지 않는다.
 */
public interface OwnershipLease {

    enum Renewal {
        /** 이어 쥐었다. */
        RENEWED,
        /** 다른 노드가 쥐었거나 만료되었다. 이 노드의 세션은 버려야 한다. */
        LOST,
        /** 저장소에 닿지 못해 모른다. 세션은 그대로 두고 다음에 다시 본다 (D-43). */
        UNKNOWN
    }

    /** 리스를 잡는다. 이미 이 노드가 쥐고 있어도 true. 다른 노드가 쥐고 있으면 false. */
    boolean tryAcquire(UUID docId);

    Renewal renew(UUID docId);

    /** 이 노드가 쥔 리스면 푼다. 아니면 아무것도 하지 않는다. */
    void release(UUID docId);

    /** 리스를 쥔 노드에 세션을 넘겨 달라고 알린다(노드가 늘었을 때). */
    void requestHandoff(UUID docId);
}
