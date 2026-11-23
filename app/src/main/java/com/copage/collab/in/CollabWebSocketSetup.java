package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.config.CollabProperties;
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
    private final CollabProperties properties;

    CollabWebSocketSetup(CollabWebSocketHandler handler, TicketService tickets, CollabProperties properties) {
        this.handler = handler;
        this.tickets = tickets;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/docs/*")
                .addInterceptors(new TicketHandshakeInterceptor(tickets))
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
