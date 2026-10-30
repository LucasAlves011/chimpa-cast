package com.screenshare.controller;

import com.screenshare.config.SecurityInterceptor;
import com.screenshare.service.AuthService;
import com.screenshare.service.DiscordLoginException;
import com.screenshare.service.DiscordService;
import com.screenshare.service.DiscordUser;
import com.screenshare.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Login com Discord (OAuth2). Rotas públicas: a autenticação é feita pelo próprio fluxo.
 */
@RestController
@RequestMapping("/api/auth")
public class DiscordAuthController {

    private final DiscordService discordService;
    private final AuthService authService;
    private final RateLimitService rateLimitService;

    public DiscordAuthController(DiscordService discordService, AuthService authService, RateLimitService rateLimitService) {
        this.discordService = discordService;
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("discordLogin", discordService.isLoginEnabled());
    }

    @GetMapping("/discord/login")
    public ResponseEntity<Void> login() {
        if (!discordService.isLoginEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return redirect(discordService.buildAuthorizeUrl());
    }

    @GetMapping("/discord/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        if (!discordService.isLoginEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!rateLimitService.tryAcquireAuth(SecurityInterceptor.extractClientIp(request))) {
            return redirect("/?discord_error=rate_limited");
        }
        if (error != null || code == null || !discordService.consumeState(state)) {
            return redirect("/?discord_error=invalid_request");
        }

        try {
            DiscordUser user = discordService.authenticate(code);
            String token = authService.createSessionToken(user);
            return redirect("/?discord_ticket=" + discordService.createTicket(token));
        } catch (DiscordLoginException e) {
            return redirect("/?discord_error=" + URLEncoder.encode(e.getCode(), StandardCharsets.UTF_8));
        }
    }

    @PostMapping("/discord/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!rateLimitService.tryAcquireAuth(SecurityInterceptor.extractClientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "valid", false,
                    "message", "Muitas tentativas. Aguarde 1 minuto antes de tentar novamente."
            ));
        }

        String token = discordService.redeemTicket(body.get("ticket"));
        DiscordUser user = authService.getDiscordUser(token);
        if (token == null || user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "valid", false,
                    "message", "Login com Discord expirado. Tente novamente."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "valid", true,
                "token", token,
                "user", Map.of(
                        "id", user.id(),
                        "name", user.name(),
                        "avatarUrl", user.avatarUrl()
                )
        ));
    }

    /** Valida a sessão salva no navegador e devolve a identidade (protegida pelo SecurityInterceptor). */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        DiscordUser user = authService.getDiscordUser(token);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("valid", false));
        }
        if (!discordService.verifyMembership(user.id())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "valid", false,
                    "message", "Você não faz mais parte do servidor do Discord."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "valid", true,
                "user", Map.of("id", user.id(), "name", user.name(), "avatarUrl", String.valueOf(user.avatarUrl()))
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        authService.revokeToken(token);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(location).toString()).build();
    }
}
