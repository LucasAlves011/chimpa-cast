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

        // Rotas públicas (estáticos e rota de validação de autenticação que tem seu próprio rate limiter)
        if (path.startsWith("/api/auth/validate") || !path.startsWith("/api/")) {
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

        // 2. Validação por Token de Sessão ou Senha Mestra
        String token = request.getHeader("X-Session-Token");
        String password = request.getHeader("X-Access-Password");

        String credential = (token != null && !token.isBlank()) ? token : password;

        if (!authService.isValidTokenOrPassword(credential)) {
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
