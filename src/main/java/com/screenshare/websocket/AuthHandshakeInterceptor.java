package com.screenshare.websocket;

import com.screenshare.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);
    private final AuthService authService;

    public AuthHandshakeInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = null;
        String password = null;

        // 1. Tenta extrair da Query String (ex: /ws/rooms?token=xyz ou ?password=xyz)
        URI uri = request.getURI();
        if (uri.getQuery() != null) {
            String[] pairs = uri.getQuery().split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    if ("token".equalsIgnoreCase(kv[0])) {
                        token = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    } else if ("password".equalsIgnoreCase(kv[0])) {
                        password = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }
            }
        }

        // 2. Se não estiver na query, tenta extrair dos cabeçalhos HTTP
        if (token == null && request.getHeaders().containsKey("X-Session-Token")) {
            token = request.getHeaders().getFirst("X-Session-Token");
        }
        if (password == null && request.getHeaders().containsKey("X-Access-Password")) {
            password = request.getHeaders().getFirst("X-Access-Password");
        }

        String credential = (token != null && !token.isBlank()) ? token : password;

        if (credential == null || !authService.isValidTokenOrPassword(credential)) {
            log.warn("Handshake WebSocket rejeitado: credencial ausente ou inválida. URI: {}", uri.getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put("auth_validated", true);
        attributes.put("credential", credential);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Sem pós-processamento necessário
    }
}
