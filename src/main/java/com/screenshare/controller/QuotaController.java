package com.screenshare.controller;

import com.screenshare.service.AuthService;
import com.screenshare.service.QuotaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;
    private final AuthService authService;

    public QuotaController(QuotaService quotaService, AuthService authService) {
        this.quotaService = quotaService;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getQuota() {
        return ResponseEntity.ok(quotaService.getQuotaData());
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncQuota() {
        quotaService.syncWithCloudflareGraphQL();
        return ResponseEntity.ok(quotaService.getQuotaData());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetQuota(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        if (!authService.isValidAdminKey(adminKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Acesso negado: operação de reset de cota é restrita exclusivamente ao administrador."
            ));
        }
        quotaService.resetQuota();
        return ResponseEntity.ok(quotaService.getQuotaData());
    }
}
