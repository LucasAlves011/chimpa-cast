package com.screenshare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessões stateless: o token carrega o usuário do Discord e a expiração, assinados com HMAC-SHA256.
 * Assim a sessão sobrevive a reinícios/deploys, desde que o segredo (APP_SESSION_SECRET) seja o mesmo.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${app.admin-key:admin_super_secret_9981}")
    private String adminKey;

    @Value("${app.session-secret:}")
    private String sessionSecret;

    @Value("${app.session-ttl-days:60}")
    private long sessionTtlDays = 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64Decoder = Base64.getUrlDecoder();

    private volatile byte[] signingKey;

    // Assinatura do token revogado (logout) -> expiração (ms); evita crescer indefinidamente
    private final Map<String, Long> revokedTokens = new ConcurrentHashMap<>();

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
     * Gera um token de sessão assinado para um membro do Discord autenticado
     */
    public String createSessionToken(DiscordUser discordUser) {
        if (discordUser == null) {
            throw new IllegalArgumentException("Sessão exige um usuário do Discord autenticado");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("i", discordUser.id());
        payload.put("n", discordUser.name());
        payload.put("a", discordUser.avatarUrl());
        payload.put("e", System.currentTimeMillis() + Duration.ofDays(sessionTtlDays).toMillis());
        try {
            String body = b64.encodeToString(mapper.writeValueAsBytes(payload));
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar token de sessão", e);
        }
    }

    /**
     * Identidade do Discord dona da sessão, ou null se o token é inválido/expirado/revogado
     */
    public DiscordUser getDiscordUser(String token) {
        JsonNode payload = verify(token);
        if (payload == null) return null;
        return new DiscordUser(payload.path("i").asText(), payload.path("n").asText(), payload.path("a").asText(null));
    }

    /**
     * Valida se um token de sessão é autêntico, não expirou e não foi revogado
     */
    public boolean isValidToken(String token) {
        return verify(token) != null;
    }

    public void revokeToken(String token) {
        JsonNode payload = verify(token);
        if (payload == null) return;
        long now = System.currentTimeMillis();
        revokedTokens.entrySet().removeIf(e -> e.getValue() < now);
        revokedTokens.put(signatureOf(token), payload.path("e").asLong());
    }

    private JsonNode verify(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        int dot = t.indexOf('.');
        if (dot <= 0 || dot == t.length() - 1) return null;

        try {
            String body = t.substring(0, dot);
            byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
            byte[] actual = t.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) return null;

            JsonNode payload = mapper.readTree(b64Decoder.decode(body));
            if (System.currentTimeMillis() > payload.path("e").asLong(0)) return null;
            if (revokedTokens.containsKey(signatureOf(t))) return null;
            if (payload.path("i").asText("").isEmpty()) return null;
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    private static String signatureOf(String token) {
        String t = token.trim();
        return t.substring(t.indexOf('.') + 1);
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(key(), HMAC_ALGORITHM));
        return b64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] key() {
        byte[] k = signingKey;
        if (k == null) {
            synchronized (this) {
                if (signingKey == null) {
                    if (sessionSecret != null && !sessionSecret.isBlank()) {
                        signingKey = sessionSecret.trim().getBytes(StandardCharsets.UTF_8);
                    } else {
                        byte[] random = new byte[32];
                        new SecureRandom().nextBytes(random);
                        signingKey = random;
                        log.warn("APP_SESSION_SECRET não definido: as sessões serão perdidas a cada reinício. Defina um segredo longo e aleatório em produção.");
                    }
                }
                k = signingKey;
            }
        }
        return k;
    }
}
