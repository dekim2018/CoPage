package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.domain.WsTicket;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * WebSocket 핸드셰이크에서 문서 ID 와 1회용 티켓을 확인한다 (docs/07-SECURITY.md 9.3).
 *
 * <p>티켓과 규약 버전이 틀려도 핸드셰이크는 받아들인다. 브라우저는 핸드셰이크의 HTTP 상태를 읽을 수 없어서, 연결을 연 뒤
 * 종료 코드(4001, 4010)로 알려야 클라이언트가 다음 동작을 고를 수 있다. 판정 결과는 속성에 담아 핸들러로 넘긴다.
 * 주소 모양이 틀린 요청만 핸드셰이크에서 400 으로 거절한다.
 */
final class TicketHandshakeInterceptor implements HandshakeInterceptor {

    static final String DOC_ID = "copage.docId";
    static final String TICKET = "copage.ticket";
    static final String VERSION = "copage.version";

    private final TicketService tickets;

    TicketHandshakeInterceptor(TicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        UriComponents uri = UriComponentsBuilder.fromUri(request.getURI()).build();
        Optional<UUID> docId = docId(uri);
        if (docId.isEmpty()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        Optional<WsTicket> ticket = tickets.redeem(uri.getQueryParams().getFirst("ticket"), docId.get());
        attributes.put(DOC_ID, docId.get());
        ticket.ifPresent(value -> attributes.put(TICKET, value));
        String version = uri.getQueryParams().getFirst("v");
        if (version != null) {
            attributes.put(VERSION, version);
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
    }

    /** 경로 /ws/docs/{docId} 의 마지막 부분. */
    private static Optional<UUID> docId(UriComponents uri) {
        var segments = uri.getPathSegments();
        if (segments.size() != 3 || !segments.get(0).equals("ws") || !segments.get(1).equals("docs")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(segments.get(2)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
