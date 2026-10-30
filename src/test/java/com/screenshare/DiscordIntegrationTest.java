package com.screenshare;

import com.screenshare.service.AuthService;
import com.screenshare.service.DiscordService;
import com.screenshare.service.DiscordUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class DiscordIntegrationTest {

    private AuthService authService;
    private DiscordService discordService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        ReflectionTestUtils.setField(authService, "adminKey", "admin");

        discordService = new DiscordService();
        ReflectionTestUtils.setField(discordService, "clientId", "123");
        ReflectionTestUtils.setField(discordService, "clientSecret", "secret");
        ReflectionTestUtils.setField(discordService, "botToken", "bot");
        ReflectionTestUtils.setField(discordService, "guildId", "456");
        ReflectionTestUtils.setField(discordService, "redirectUri", "https://site.test/api/auth/discord/callback");
    }

    @Test
    @DisplayName("Sessão criada via Discord expõe a identidade e some ao revogar o token")
    void testSessionCarriesDiscordUser() {
        DiscordUser user = new DiscordUser("111222333", "Lucas", "https://cdn.discordapp.com/embed/avatars/0.png");
        String token = authService.createSessionToken(user);

        assertTrue(authService.isValidToken(token));
        assertEquals(user, authService.getDiscordUser(token));

        authService.revokeToken(token);
        assertNull(authService.getDiscordUser(token));
    }

    @Test
    @DisplayName("URL de autorização inclui escopo identify, redirect e state de uso único")
    void testAuthorizeUrlAndState() {
        assertTrue(discordService.isLoginEnabled());
        String url = discordService.buildAuthorizeUrl();

        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?client_id=123"));
        assertTrue(url.contains("scope=identify"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fsite.test%2Fapi%2Fauth%2Fdiscord%2Fcallback"));

        String state = url.substring(url.indexOf("state=") + 6);
        assertTrue(discordService.consumeState(state));
        assertFalse(discordService.consumeState(state), "state deve ser de uso único");
        assertFalse(discordService.consumeState("inventado"));
    }

    @Test
    @DisplayName("Ticket de login é de uso único")
    void testTicketSingleUse() {
        String ticket = discordService.createTicket("tok");
        assertEquals("tok", discordService.redeemTicket(ticket));
        assertNull(discordService.redeemTicket(ticket));
        assertNull(discordService.redeemTicket(null));
    }

    @Test
    @DisplayName("Login e aviso ficam desativados sem configuração")
    void testDisabledWithoutConfig() {
        DiscordService empty = new DiscordService();
        assertFalse(empty.isLoginEnabled());
        assertFalse(empty.isNotifyEnabled());
        // não deve lançar nem tentar rede quando desativado
        empty.notifyScreenStarted("k", "Lucas", null, "principal", "1080p");
    }

    @Test
    @DisplayName("Não é possível criar sessão sem usuário do Discord")
    void testSessionRequiresDiscordUser() {
        assertThrows(IllegalArgumentException.class, () -> authService.createSessionToken(null));
        assertNull(authService.getDiscordUser("token-inexistente"));
    }

    private AuthService authWithSecret(String secret) {
        AuthService service = new AuthService();
        ReflectionTestUtils.setField(service, "sessionSecret", secret);
        return service;
    }

    @Test
    @DisplayName("Sessão continua válida após reinício quando o segredo é o mesmo")
    void testSessionSurvivesRestart() {
        DiscordUser user = new DiscordUser("111222333", "Lucas", "https://cdn.discordapp.com/embed/avatars/0.png");
        String token = authWithSecret("segredo-de-teste").createSessionToken(user);

        AuthService afterRestart = authWithSecret("segredo-de-teste");
        assertTrue(afterRestart.isValidToken(token));
        assertEquals(user, afterRestart.getDiscordUser(token));
    }

    @Test
    @DisplayName("Token assinado com outro segredo ou adulterado é rejeitado")
    void testTamperedOrForeignTokenRejected() {
        DiscordUser user = new DiscordUser("111222333", "Lucas", null);
        String token = authWithSecret("segredo-a").createSessionToken(user);

        assertFalse(authWithSecret("segredo-b").isValidToken(token));

        AuthService service = authWithSecret("segredo-a");
        String[] parts = token.split("\\.");
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"i\":\"999\",\"n\":\"Intruso\",\"e\":99999999999999}".getBytes());
        assertFalse(service.isValidToken(forgedBody + "." + parts[1]));
        assertFalse(service.isValidToken(token + "x"));
        assertFalse(service.isValidToken("lixo"));
        assertFalse(service.isValidToken(""));
    }

    @Test
    @DisplayName("Token expirado é rejeitado e o prazo padrão é de 60 dias")
    void testExpiration() {
        AuthService service = authWithSecret("segredo");
        ReflectionTestUtils.setField(service, "sessionTtlDays", -1L);
        String expired = service.createSessionToken(new DiscordUser("111222333", "Lucas", null));
        assertFalse(service.isValidToken(expired));

        assertEquals(60L, ReflectionTestUtils.getField(new AuthService(), "sessionTtlDays"));
    }

    @Test
    @DisplayName("Sem Discord configurado, a rechecagem de membro não bloqueia ninguém")
    void testMembershipRecheckDisabledWithoutConfig() {
        assertTrue(new DiscordService().verifyMembership("111222333"));
    }
}
