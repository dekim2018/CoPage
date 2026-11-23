package com.copage.collab.app;

import com.copage.collab.domain.WsTicket;
import java.time.Duration;
import java.util.Optional;

/** 1회용 티켓 저장소 포트. 지금은 메모리 구현만 있고, Redis 구현(SET EX, GETDEL)이 뒤에 붙는다. */
public interface TicketStore {

    void save(String ticket, WsTicket value, Duration ttl);

    /** 티켓을 꺼내면서 지운다. 없거나 만료되었으면 비어 있다. 같은 티켓은 두 번 꺼낼 수 없다. */
    Optional<WsTicket> take(String ticket);
}
