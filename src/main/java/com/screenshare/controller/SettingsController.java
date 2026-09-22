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

    @GetMapping("/remote-kick")
    public ResponseEntity<Map<String, Object>> getRemoteKickStatus() {
        return ResponseEntity.ok(Map.of(
                "allowRemoteScreenKick", roomSettingsService.isAllowRemoteScreenKick()
        ));
    }

    @PostMapping("/remote-kick")
    public ResponseEntity<Map<String, Object>> updateRemoteKickStatus(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Access-Password", required = false) String headerPassword,
            @RequestHeader(value = "X-Admin-Key", required = false) String headerAdminKey) {

        // Extrai a senha ou chave (pode vir no corpo JSON ou nos cabeçalhos HTTP)
        String password = body.get("password") != null ? String.valueOf(body.get("password")).trim() : "";
        String adminKey = body.get("adminKey") != null ? String.valueOf(body.get("adminKey")).trim() : "";

        if (password.isEmpty() && headerPassword != null) {
            password = headerPassword.trim();
        }
        if (adminKey.isEmpty() && headerAdminKey != null) {
            adminKey = headerAdminKey.trim();
        }

        boolean authorized = authService.isValidPassword(password) || 
                             authService.isValidAdminKey(adminKey) ||
                             authService.isValidToken(password);

        if (!authorized) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Credencial inválida: informe a senha da aplicação ou a admin key para alterar esta configuração."
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
