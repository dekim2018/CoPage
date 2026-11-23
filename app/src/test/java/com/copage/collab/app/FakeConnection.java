package com.copage.collab.app;

import com.copage.collab.domain.ServerMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 받은 메시지와 닫힌 코드를 기록하는 가짜 연결. */
final class FakeConnection implements Connection {

    private final String id;
    private final UUID userId;
    final List<ServerMessage> received = new ArrayList<>();
    /** 프레즌스(입장, 커서, 퇴장)를 뺀 메시지. 편집 규칙을 보는 테스트가 쓴다. */
    final List<ServerMessage> sync = new ArrayList<>();
    Integer closedWith;

    FakeConnection(String id) {
        this.id = id;
        this.userId = UUID.nameUUIDFromBytes(id.getBytes());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public void send(ServerMessage message) {
        received.add(message);
        if (!(message instanceof ServerMessage.Presence)) {
            sync.add(message);
        }
    }

    @Override
    public void close(int code, String reason) {
        closedWith = code;
    }

    ServerMessage last() {
        return received.get(received.size() - 1);
    }

    <T extends ServerMessage> T last(Class<T> type) {
        return type.cast(last());
    }
}
