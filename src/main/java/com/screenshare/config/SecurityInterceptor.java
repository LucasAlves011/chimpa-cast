package com.screenshare.config;

import com.screenshare.service.AuthService;
import com.screenshare.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SecurityInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    public SecurityInterceptor(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Permite pre-flight OPTIONS do CORS
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        // Rotas públicas ou com autenticação própria (estáticos, login com Discord, resumo de salas e settings com admin key/token)
        if (path.equals("/api/auth/config")
                || path.startsWith("/api/auth/discord/")
                || path.startsWith("/api/settings/remote-kick")
                || path.startsWith("/api/settings/rooms-summary")
                || !path.startsWith("/api/")) {
            return true;
        }

        String clientIp = extractClientIp(request);

        // 1. Rate Limiting para APIs protegidas
        if (!rateLimitService.tryAcquireApi(clientIp)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", String.valueOf(rateLimitService.getRetryAfterSeconds()));
            response.getWriter().write("{\"error\":\"Muitas requisições. Aguarde um momento antes de tentar novamente.\"}");
            return false;
        }

        // 2. Validação do Token de Sessão (emitido no login com Discord)
        String token = request.getHeader("X-Session-Token");

        if (!authService.isValidToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Acesso negado: token ou credencial inválida!\"}");
            return false;
        }

        return true;
    }

    public static String extractClientIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp.trim();
        }
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
