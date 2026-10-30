package com.screenshare.controller;

import com.screenshare.service.AuthService;
import com.screenshare.service.RoomSettingsService;
import com.screenshare.websocket.RoomWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final RoomSettingsService roomSettingsService;
    private final AuthService authService;
    private final RoomWebSocketHandler roomWebSocketHandler;

    public SettingsController(RoomSettingsService roomSettingsService, 
                              AuthService authService, 
                              RoomWebSocketHandler roomWebSocketHandler) {
        this.roomSettingsService = roomSettingsService;
        this.authService = authService;
        this.roomWebSocketHandler = roomWebSocketHandler;
    }

    @GetMapping("/rooms-summary")
    public ResponseEntity<Map<String, Object>> getRoomsSummary() {
        return ResponseEntity.ok(Map.of(
                "rooms", roomWebSocketHandler.getRoomsCounts()
        ));
    }

    @GetMapping("/remote-kick")
    public ResponseEntity<Map<String, Object>> getRemoteKickStatus() {
        return ResponseEntity.ok(Map.of(
                "allowRemoteScreenKick", roomSettingsService.isAllowRemoteScreenKick()
        ));
    }

    @PostMapping("/remote-kick")
    public ResponseEntity<Map<String, Object>> updateRemoteKickStatus(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Token", required = false) String headerToken,
            @RequestHeader(value = "X-Admin-Key", required = false) String headerAdminKey) {

        // Extrai o token de sessão ou a chave de admin (corpo JSON ou cabeçalhos HTTP)
        String token = body.get("token") != null ? String.valueOf(body.get("token")).trim() : "";
        String adminKey = body.get("adminKey") != null ? String.valueOf(body.get("adminKey")).trim() : "";

        if (token.isEmpty() && headerToken != null) {
            token = headerToken.trim();
        }
        if (adminKey.isEmpty() && headerAdminKey != null) {
            adminKey = headerAdminKey.trim();
        }

        boolean authorized = authService.isValidAdminKey(adminKey) || authService.isValidToken(token);

        if (!authorized) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Credencial inválida: faça login com o Discord ou informe a admin key para alterar esta configuração."
            ));
        }

        if (!body.containsKey("enabled")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Campo 'enabled' (boolean) é obrigatório."
            ));
        }

        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        roomSettingsService.setAllowRemoteScreenKick(enabled);

        // Notifica em tempo real todas as salas conectadas
        roomWebSocketHandler.broadcastSettingsUpdate();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "allowRemoteScreenKick", enabled,
                "message", enabled 
                    ? "Encerramento remoto de telas ATIVADO com sucesso." 
                    : "Encerramento remoto de telas DESATIVADO com sucesso."
        ));
    }
}
