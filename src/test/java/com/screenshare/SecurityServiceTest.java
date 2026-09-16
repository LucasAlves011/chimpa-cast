package com.screenshare;

import com.screenshare.service.AuthService;
import com.screenshare.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SecurityServiceTest {

    private AuthService authService;
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        ReflectionTestUtils.setField(authService, "accessPassword", "amigos123");
        ReflectionTestUtils.setField(authService, "adminKey", "admin_super_secret_9981");

        rateLimitService = new RateLimitService();
    }

    @Test
    @DisplayName("Validação de senha deve ser case-sensitive e correta via MessageDigest")
    void testPasswordValidation() {
        assertTrue(authService.isValidPassword("amigos123"));
        assertFalse(authService.isValidPassword("Amigos123"));
        assertFalse(authService.isValidPassword("senhaErrada"));
        assertFalse(authService.isValidPassword(null));
        assertFalse(authService.isValidPassword(""));
    }

    @Test
    @DisplayName("Geração e ciclo de vida de tokens de sessão efêmeros")
    void testSessionTokenLifecycle() {
        String token = authService.createSessionToken();
        assertNotNull(token);
        assertFalse(token.isBlank());

        assertTrue(authService.isValidToken(token));
        assertTrue(authService.isValidTokenOrPassword(token));

        // Token inexistente
        assertFalse(authService.isValidToken("token-inexistente-12345"));

        // Revogação
        authService.revokeToken(token);
        assertFalse(authService.isValidToken(token));
    }

    @Test
    @DisplayName("Validação de chave de administração para endpoints restritos")
    void testAdminKeyValidation() {
        assertTrue(authService.isValidAdminKey("admin_super_secret_9981"));
        assertFalse(authService.isValidAdminKey("chave_incorreta"));
        assertFalse(authService.isValidAdminKey(null));
    }

    @Test
    @DisplayName("Rate Limiting deve bloquear após 5 tentativas consecutivas de login por IP")
    void testAuthRateLimiter() {
        String ip = "192.168.1.100";

        for (int i = 1; i <= 5; i++) {
            assertTrue(rateLimitService.tryAcquireAuth(ip), "Tentativa " + i + " deveria ser permitida");
        }

        // 6ª tentativa deve ser bloqueada
        assertFalse(rateLimitService.tryAcquireAuth(ip), "6ª tentativa deveria ser bloqueada pelo rate limit");

        // Outro IP não deve ser afetado
        assertTrue(rateLimitService.tryAcquireAuth("10.0.0.50"), "IP diferente deve ter cota independente");
    }
}