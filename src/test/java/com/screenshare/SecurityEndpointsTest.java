package com.screenshare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.screenshare.service.AuthService;
import com.screenshare.service.DiscordUser;
import org.junit.jupiter.api.BeforeEach;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"discord.client-id=", "discord.client-secret=", "discord.bot-token=", "discord.guild-id=", "discord.channel-id=", "discord.redirect-uri="})
@AutoConfigureMockMvc
class SecurityEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    private String token;

    @BeforeEach
    void setUp() {
        token = authService.createSessionToken(new DiscordUser("111222333", "Tester", "https://cdn.discordapp.com/embed/avatars/0.png"));
    }

    @Test
    @DisplayName("Acesso a /api/quota sem credencial deve retornar HTTP 401")
    void testQuotaUnauthorized() throws Exception {
        mockMvc.perform(get("/api/quota"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Acesso a /api/quota com credencial válida deve retornar HTTP 200")
    void testQuotaAuthorized() throws Exception {
        mockMvc.perform(get("/api/quota")
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedGb").exists());
    }

    @Test
    @DisplayName("Reset de cota sem chave de administrador deve retornar HTTP 403")
    void testQuotaResetForbiddenWithoutAdminKey() throws Exception {
        mockMvc.perform(post("/api/quota/reset")
                        .header("X-Session-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Acesso negado: operação de reset de cota é restrita exclusivamente ao administrador."));
    }

    @Test
    @DisplayName("Chamada com sessionId malicioso ou inválido deve ser rejeitada com HTTP 400")
    void testCallsInvalidSessionId() throws Exception {
        mockMvc.perform(post("/api/calls/sessions/sessaoInvalida$$$ComCaracteresProibidos/tracks/new")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracks\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Alteração de remote-kick sem credencial válida deve retornar HTTP 401")
    void testSettingsRemoteKickUnauthorized() throws Exception {
        mockMvc.perform(post("/api/settings/remote-kick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Alteração de remote-kick com senha válida deve retornar HTTP 200 e alterar status")
    void testSettingsRemoteKickAuthorized() throws Exception {
        mockMvc.perform(post("/api/settings/remote-kick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.allowRemoteScreenKick").value(false));

        // Reverte para true
        mockMvc.perform(post("/api/settings/remote-kick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowRemoteScreenKick").value(true));
    }

    @Test
    @DisplayName("Configuração de login é pública e informa se o Discord está disponível")
    void testAuthConfigIsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discordLogin").value(false));
    }

    @Test
    @DisplayName("Login com Discord não configurado deve retornar HTTP 404 e não vazar acesso")
    void testDiscordLoginDisabledWithoutConfig() throws Exception {
        mockMvc.perform(get("/api/auth/discord/login"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/discord/callback").param("code", "x").param("state", "y"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A antiga senha única não dá mais acesso à API")
    void testLegacyPasswordHeaderRejected() throws Exception {
        mockMvc.perform(get("/api/quota")
                        .header("X-Access-Password", "amigos123"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"amigos123\"}"))
                .andExpect(status().isUnauthorized());
    }
}