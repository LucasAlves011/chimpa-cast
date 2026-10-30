package com.screenshare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;

/**
 * Integração com o Discord: login OAuth2 (restrito a membros do servidor) e aviso de live no canal.
 * Cada recurso só é ativado quando suas variáveis de ambiente estão configuradas.
 */
@Service
public class DiscordService {

    private static final Logger log = LoggerFactory.getLogger(DiscordService.class);
    private static final String API_BASE = "https://discord.com/api/v10";
    private static final String USER_AGENT = "DiscordBot (https://github.com/chimpacast, 1.0)";
    private static final long STATE_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long TICKET_TTL_MS = Duration.ofMinutes(1).toMillis();
    private static final int EMBED_COLOR = 0x5865F2;
    private static final int MAX_MENTIONS = 80;
    private static final long MEMBERSHIP_RECHECK_MS = Duration.ofHours(1).toMillis();

    @Value("${discord.client-id:}")
    private String clientId;

    @Value("${discord.client-secret:}")
    private String clientSecret;

    @Value("${discord.bot-token:}")
    private String botToken;

    @Value("${discord.guild-id:}")
    private String guildId;

    @Value("${discord.channel-id:}")
    private String channelId;

    @Value("${discord.redirect-uri:}")
    private String redirectUri;

    @Value("${discord.required-role-id:}")
    private String requiredRoleId;

    @Value("${discord.public-url:}")
    private String publicUrl;

    @Value("${discord.notify-cooldown-seconds:600}")
    private long notifyCooldownSeconds;

    // Ausente quando a aplicação é montada sem Spring (testes) ou sem guild-id
    @Autowired(required = false)
    private DiscordVoiceService voiceService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService notifier = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "discord-notifier");
        t.setDaemon(true);
        return t;
    });

    // state OAuth -> expiração (uso único, protege contra CSRF no callback)
    private final Map<String, Long> oauthStates = new ConcurrentHashMap<>();
    // ticket de uso único -> sessão (evita expor o token de sessão na URL)
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    // id do usuário -> instante da última confirmação de que ainda é membro
    private final Map<String, Long> membershipChecked = new ConcurrentHashMap<>();
    // chave do usuário -> instante do último aviso (cooldown anti-spam)
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();

    private record Ticket(String token, long expiry) {}

    @PreDestroy
    void shutdown() {
        notifier.shutdownNow();
    }

    public boolean isLoginEnabled() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(botToken)
                && notBlank(guildId) && notBlank(redirectUri);
    }

    public boolean isNotifyEnabled() {
        return notBlank(botToken) && notBlank(channelId);
    }

    // ==========================================
    // Login OAuth2
    // ==========================================

    public String buildAuthorizeUrl() {
        cleanExpired();
        String state = randomHex(16);
        oauthStates.put(state, System.currentTimeMillis() + STATE_TTL_MS);
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&scope=identify"
                + "&prompt=none"
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state);
    }

    public boolean consumeState(String state) {
        if (state == null) return false;
        Long expiry = oauthStates.remove(state);
        return expiry != null && System.currentTimeMillis() <= expiry;
    }

    /**
     * Troca o code pelo usuário e confirma que ele é membro do servidor (e tem a role exigida, se houver).
     */
    public DiscordUser authenticate(String code) throws DiscordLoginException {
        try {
            String accessToken = exchangeCode(code);
            JsonNode me = getJson("/users/@me", "Bearer " + accessToken);
            if (me == null) {
                throw new DiscordLoginException("discord_error", "Não foi possível obter seu perfil do Discord.");
            }
            String userId = me.path("id").asText("");
            if (!userId.matches("\\d{5,25}")) {
                throw new DiscordLoginException("discord_error", "Resposta inválida do Discord.");
            }

            JsonNode member = fetchMember(userId);
            membershipChecked.put(userId, System.currentTimeMillis());

            String displayName = firstNonBlank(
                    member.path("nick").asText(""),
                    me.path("global_name").asText(""),
                    me.path("username").asText(""));
            return new DiscordUser(userId, sanitizeName(displayName), avatarUrl(userId, me.path("avatar").asText("")));
        } catch (DiscordLoginException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscordLoginException("discord_error", "Login interrompido.");
        } catch (Exception e) {
            log.error("Erro no login via Discord: {}", e.getMessage());
            throw new DiscordLoginException("discord_error", "Falha ao comunicar com o Discord.");
        }
    }

    /**
     * Rechecagem periódica de quem já tem sessão: quem saiu do servidor (ou perdeu o cargo) perde o acesso
     * em até {@code MEMBERSHIP_RECHECK_MS}. Falhas do Discord (rede, 5xx, rate limit) não derrubam ninguém.
     */
    public boolean verifyMembership(String userId) {
        if (!isLoginEnabled() || userId == null) return true;

        Long last = membershipChecked.get(userId);
        if (last != null && System.currentTimeMillis() - last < MEMBERSHIP_RECHECK_MS) return true;

        try {
            fetchMember(userId);
            membershipChecked.put(userId, System.currentTimeMillis());
            return true;
        } catch (DiscordLoginException e) {
            if ("not_member".equals(e.getCode()) || "missing_role".equals(e.getCode())) {
                membershipChecked.remove(userId);
                return false;
            }
            log.warn("Não foi possível rechecar membership de {}; mantendo acesso: {}", userId, e.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private JsonNode fetchMember(String userId) throws DiscordLoginException, InterruptedException {
        try {
            HttpResponse<String> memberRes = send(HttpRequest.newBuilder(
                            URI.create(API_BASE + "/guilds/" + enc(guildId) + "/members/" + enc(userId)))
                    .header("Authorization", "Bot " + botToken));
            if (memberRes.statusCode() == 404) {
                throw new DiscordLoginException("not_member", "Você precisa ser membro do servidor do Discord para entrar.");
            }
            if (memberRes.statusCode() != 200) {
                log.error("Falha ao consultar membership no Discord: HTTP {}", memberRes.statusCode());
                throw new DiscordLoginException("discord_error", "Não foi possível verificar sua participação no servidor.");
            }
            JsonNode member = mapper.readTree(memberRes.body());

            if (notBlank(requiredRoleId)) {
                boolean hasRole = false;
                for (JsonNode role : member.path("roles")) {
                    if (requiredRoleId.equals(role.asText())) {
                        hasRole = true;
                        break;
                    }
                }
                if (!hasRole) {
                    throw new DiscordLoginException("missing_role", "Seu usuário não tem o cargo necessário para entrar.");
                }
            }
            return member;
        } catch (IOException e) {
            throw new DiscordLoginException("discord_error", "Falha ao comunicar com o Discord.");
        }
    }

    public String createTicket(String sessionToken) {
        String ticket = randomHex(24);
        tickets.put(ticket, new Ticket(sessionToken, System.currentTimeMillis() + TICKET_TTL_MS));
        return ticket;
    }

    /** Devolve o token de sessão associado ao ticket (uso único) ou null se inválido/expirado. */
    public String redeemTicket(String ticket) {
        if (ticket == null) return null;
        Ticket t = tickets.remove(ticket);
        if (t == null || System.currentTimeMillis() > t.expiry()) return null;
        return t.token();
    }

    // ==========================================
    // Aviso de live no canal
    // ==========================================

    /**
     * Avisa no canal que {@code userId} começou a compartilhar tela, marcando quem está em canais de voz.
     */
    public void notifyScreenStarted(String userId, String username, String avatarUrl, String room, String quality) {
        if (!isNotifyEnabled()) return;

        long now = System.currentTimeMillis();
        long cooldownMs = notifyCooldownSeconds * 1000L;
        boolean[] allowed = {false};
        lastNotified.compute(userId, (k, last) -> {
            if (last == null || now - last >= cooldownMs) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        if (!allowed[0]) {
            log.debug("Aviso de live suprimido pelo cooldown para {}", userId);
            return;
        }

        notifier.execute(() -> postLiveMessage(userId, username, avatarUrl, room, quality));
    }

    private void postLiveMessage(String userId, String username, String avatarUrl, String room, String quality) {
        try {
            // Discord aceita no máximo 100 menções por mensagem e 2000 caracteres de conteúdo
            List<String> mentionIds = voiceService == null
                    ? List.of()
                    : voiceService.getVoiceMemberIds(userId).stream().limit(MAX_MENTIONS).toList();

            String description = "Sala **" + escapeMarkdown(room) + "** · " + escapeMarkdown(quality);
            if (notBlank(publicUrl)) {
                description += "\n[Entrar no ChimpaCast](" + publicUrl.trim() + ")";
            }

            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("title", "🖥️ " + escapeMarkdown(username) + " está compartilhando tela");
            embed.put("description", description);
            embed.put("color", EMBED_COLOR);
            embed.put("timestamp", Instant.now().toString());
            if (notBlank(avatarUrl)) {
                embed.put("thumbnail", Map.of("url", avatarUrl));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            if (!mentionIds.isEmpty()) {
                body.put("content", mentionIds.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(" ")));
            }
            body.put("embeds", List.of(embed));
            body.put("allowed_mentions", Map.of("parse", List.of(), "users", mentionIds));

            HttpResponse<String> res = send(HttpRequest.newBuilder(
                            URI.create(API_BASE + "/channels/" + enc(channelId) + "/messages"))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));

            if (res.statusCode() / 100 != 2) {
                log.warn("Discord recusou o aviso de live: HTTP {}", res.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Falha ao enviar aviso de live ao Discord: {}", e.getMessage());
        }
    }

    // ==========================================
    // Utilitários
    // ==========================================

    private String exchangeCode(String code) throws IOException, InterruptedException, DiscordLoginException {
        String form = "client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&grant_type=authorization_code"
                + "&code=" + enc(code == null ? "" : code)
                + "&redirect_uri=" + enc(redirectUri);
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(API_BASE + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        if (res.statusCode() != 200) {
            log.warn("Discord recusou a troca do code: HTTP {}", res.statusCode());
            throw new DiscordLoginException("discord_error", "O Discord recusou o login. Tente novamente.");
        }
        String token = mapper.readTree(res.body()).path("access_token").asText("");
        if (token.isBlank()) {
            throw new DiscordLoginException("discord_error", "Resposta inválida do Discord.");
        }
        return token;
    }

    private JsonNode getJson(String path, String authorization) throws IOException, InterruptedException {
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(API_BASE + path))
                .header("Authorization", authorization));
        return res.statusCode() == 200 ? mapper.readTree(res.body()) : null;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpRequest request = builder
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue() < now);
        tickets.entrySet().removeIf(e -> e.getValue().expiry() < now);
    }

    static String avatarUrl(String userId, String avatarHash) {
        if (avatarHash != null && !avatarHash.isBlank()) {
            return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png?size=128";
        }
        int index = new BigInteger(userId).shiftRight(22).mod(BigInteger.valueOf(6)).intValue();
        return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
    }

    static String sanitizeName(String input) {
        String cleaned = input == null ? "" : input.replaceAll("[<>&\"'/\\\\]", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 30) cleaned = cleaned.substring(0, 30).trim();
        return cleaned.isEmpty() ? "Discord" : cleaned;
    }

    static String escapeMarkdown(String text) {
        return text == null ? "" : text.replaceAll("([\\\\*_~`|>\\[\\]])", "\\\\$1");
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) return v;
        }
        return "";
    }
}
