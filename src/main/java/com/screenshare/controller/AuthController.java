package com.screenshare.controller;

import com.screenshare.config.SecurityInterceptor;
import com.screenshare.service.AuthService;
import com.screenshare.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    public AuthController(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validatePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String clientIp = SecurityInterceptor.extractClientIp(request);

        // Mitigação de Força Bruta por IP
        if (!rateLimitService.tryAcquireAuth(clientIp)) {
            return ResponseEntity.status(429).body(Map.of(
                    "valid", false,
                    "message", "Muitas tentativas incorretas. Por favor, aguarde 1 minuto antes de tentar novamente."
            ));
        }

        String password = body.get("password");
        boolean valid = authService.isValidPassword(password);
        if (valid) {
            String token = authService.createSessionToken();
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "token", token,
                    "message", "Acesso autorizado com sucesso!"
            ));
        } else {
            return ResponseEntity.status(401).body(Map.of(
                    "valid", false,
                    "message", "Senha de acesso incorreta! Verifique com o administrador da sala."
            ));
        }
    }
}
