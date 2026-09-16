package com.screenshare.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final long TOKEN_TTL_MS = Duration.ofHours(24).toMillis();

    @Value("${app.access-password:amigos123}")
    private String accessPassword;

    @Value("${app.admin-key:admin_super_secret_9981}")
    private String adminKey;

    // Token de Sessão -> Timestamp de expiração (ms)
    private final Map<String, Long> sessionTokens = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if ("amigos123".equals(accessPassword.trim())) {
            log.warn("ATENÇÃO DE SEGURANÇA: A plataforma está utilizando a senha padrão 'amigos123'. Recomenda-se definir APP_ACCESS_PASSWORD em produção.");
        }
    }

    /**
     * Validação em tempo constante contra Timing Attacks
     */
    public boolean isValidPassword(String password) {
        if (password == null || accessPassword == null) return false;
        byte[] expected = accessPassword.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = password.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Validação de chave de administração em tempo constante
     */
    public boolean isValidAdminKey(String key) {
        if (key == null || adminKey == null) return false;
        byte[] expected = adminKey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = key.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Gera um token efêmero de sessão para o cliente autenticado
     */
    public String createSessionToken() {
        cleanExpiredTokens();
        String token = UUID.randomUUID().toString().replace("-", "");
        sessionTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    /**
     * Valida se um token de sessão está ativo e não expirado
     */
    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        Long expiry = sessionTokens.get(token.trim());
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            sessionTokens.remove(token.trim());
            return false;
        }
        return true;
    }

    /**
     * Aceita tanto um token de sessão válido quanto a senha mestre
     */
    public boolean isValidTokenOrPassword(String credential) {
        if (credential == null || credential.isBlank()) return false;
        return isValidToken(credential) || isValidPassword(credential);
    }

    public void revokeToken(String token) {
        if (token != null) {
            sessionTokens.remove(token.trim());
        }
    }

    private void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        sessionTokens.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
