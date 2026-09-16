package com.screenshare.config;

import com.screenshare.websocket.AuthHandshakeInterceptor;
import com.screenshare.websocket.RoomWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomWebSocketHandler roomWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    public WebSocketConfig(RoomWebSocketHandler roomWebSocketHandler, AuthHandshakeInterceptor authHandshakeInterceptor) {
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .toArray(String[]::new);

        registry.addHandler(roomWebSocketHandler, "/ws/rooms")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins(origins);
    }

    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024); // 64 KB máximo por mensagem
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(20 * 1000);
    }
}
