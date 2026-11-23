package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** WebSocket 경로 /ws/docs/{docId} 에 핸들러와 핸드셰이크 검사를 건다. 핸드셰이크 검사는 이 패키지 밖에 드러내지 않는다. */
@Configuration
@EnableWebSocket
class CollabWebSocketSetup implements WebSocketConfigurer {

    private final CollabWebSocketHandler handler;
    private final TicketService tickets;
    private final WebProperties web;

    CollabWebSocketSetup(CollabWebSocketHandler handler, TicketService tickets, WebProperties web) {
        this.handler = handler;
        this.tickets = tickets;
        this.web = web;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/docs/*")
                .addInterceptors(new TicketHandshakeInterceptor(tickets))
                .setAllowedOrigins(web.allowedOrigins().toArray(String[]::new));
    }
}
