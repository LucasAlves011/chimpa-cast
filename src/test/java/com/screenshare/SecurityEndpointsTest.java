package com.screenshare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

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
                        .header("X-Access-Password", "amigos123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedGb").exists());
    }

    @Test
    @DisplayName("Reset de cota sem chave de administrador deve retornar HTTP 403")
    void testQuotaResetForbiddenWithoutAdminKey() throws Exception {
        mockMvc.perform(post("/api/quota/reset")
                        .header("X-Access-Password", "amigos123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Acesso negado: operação de reset de cota é restrita exclusivamente ao administrador."));
    }

    @Test
    @DisplayName("Chamada com sessionId malicioso ou inválido deve ser rejeitada com HTTP 400")
    void testCallsInvalidSessionId() throws Exception {
        mockMvc.perform(post("/api/calls/sessions/sessaoInvalida$$$ComCaracteresProibidos/tracks/new")
                        .header("X-Access-Password", "amigos123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracks\":[]}"))
                .andExpect(status().isBadRequest());
    }
}