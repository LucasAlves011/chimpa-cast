package com.screenshare.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.screenshare.service.CloudflareCallsService;
import com.screenshare.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/calls")
public class CallsProxyController {

    private static final Logger log = LoggerFactory.getLogger(CallsProxyController.class);
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");

    private final CloudflareCallsService callsService;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CallsProxyController(CloudflareCallsService callsService, QuotaService quotaService) {
        this.callsService = callsService;
        this.quotaService = quotaService;
    }

    private ResponseEntity<String> buildErrorResponse(int status, String message) {
        try {
            Map<String, String> map = Map.of("error", message);
            return ResponseEntity.status(status).body(objectMapper.writeValueAsString(map));
        } catch (Exception ex) {
            return ResponseEntity.status(status).body("{\"error\":\"Erro interno\"}");
        }
    }

    private boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId.trim()).matches();
    }

    private boolean isValidJsonPayload(String payload) {
        if (payload == null || payload.isBlank()) return false;
        try {
            objectMapper.readTree(payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Inicia uma nova sessão na Cloudflare Calls (com bloqueio rígido de cota)
     */
    @PostMapping(value = "/sessions/new", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createSession() {
        if (!quotaService.isQuotaAvailable()) {
            return buildErrorResponse(403, "COTA_ESGOTADA: O limite gratuito de 1 TB foi atingido! Para garantir custo zero, novas transmissões estão suspensas até o próximo mês.");
        }

        try {
            String result = callsService.createSession();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Erro ao criar sessão: {}", e.getMessage());
            return buildErrorResponse(500, e.getMessage());
        }
    }

    /**
     * Registra ou subscreve em tracks WebRTC
     */
    @PostMapping(value = "/sessions/{sessionId}/tracks/new", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> newTracks(@PathVariable String sessionId, @RequestBody String payload) {
        if (!isValidSessionId(sessionId)) {
            return buildErrorResponse(400, "Identificador de sessão WebRTC inválido.");
        }
        if (!isValidJsonPayload(payload)) {
            return buildErrorResponse(400, "Formato de payload JSON inválido.");
        }

        try {
            String result = callsService.newTracks(sessionId, payload);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Erro ao adicionar tracks para sessão {}: {}", sessionId, e.getMessage());
            return buildErrorResponse(400, e.getMessage());
        }
    }

    /**
     * Renegociação SDP
     */
    @PutMapping(value = "/sessions/{sessionId}/renegotiate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> renegotiate(@PathVariable String sessionId, @RequestBody String payload) {
        if (!isValidSessionId(sessionId)) {
            return buildErrorResponse(400, "Identificador de sessão WebRTC inválido.");
        }
        if (!isValidJsonPayload(payload)) {
            return buildErrorResponse(400, "Formato de payload JSON inválido.");
        }

        try {
            String result = callsService.renegotiate(sessionId, payload);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Erro ao renegociar sessão {}: {}", sessionId, e.getMessage());
            return buildErrorResponse(400, e.getMessage());
        }
    }

    /**
     * Encerramento de tracks
     */
    @PutMapping(value = "/sessions/{sessionId}/tracks/close", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> closeTracks(@PathVariable String sessionId, @RequestBody String payload) {
        if (!isValidSessionId(sessionId)) {
            return buildErrorResponse(400, "Identificador de sessão WebRTC inválido.");
        }
        if (!isValidJsonPayload(payload)) {
            return buildErrorResponse(400, "Formato de payload JSON inválido.");
        }

        try {
            String result = callsService.closeTracks(sessionId, payload);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Erro ao fechar tracks da sessão {}: {}", sessionId, e.getMessage());
            return buildErrorResponse(400, e.getMessage());
        }
    }
}
