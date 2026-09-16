package com.screenshare.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    private static final int MAX_AUTH_ATTEMPTS_PER_MINUTE = 5;
    private static final int MAX_API_CALLS_PER_MINUTE = 120;
    private static final long WINDOW_MS = 60_000L;

    // IP -> Lista de timestamps de tentativas de autenticação
    private final Map<String, Deque<Long>> authRateLimits = new ConcurrentHashMap<>();

    // IP -> Lista de timestamps de requisições de API
    private final Map<String, Deque<Long>> apiRateLimits = new ConcurrentHashMap<>();

    /**
     * Tenta registrar uma tentativa de login/autenticação.
     * @return true se permitido, false se excedeu o limite (brute force mitigation)
     */
    public synchronized boolean tryAcquireAuth(String clientIp) {
        return checkRateLimit(authRateLimits, clientIp, MAX_AUTH_ATTEMPTS_PER_MINUTE);
    }

    /**
     * Tenta registrar uma chamada de API geral.
     * @return true se permitido, false se excedeu o limite
     */
    public synchronized boolean tryAcquireApi(String clientIp) {
        return checkRateLimit(apiRateLimits, clientIp, MAX_API_CALLS_PER_MINUTE);
    }

    private boolean checkRateLimit(Map<String, Deque<Long>> map, String clientIp, int maxAllowed) {
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = "unknown";
        }
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = map.computeIfAbsent(clientIp, k -> new ArrayDeque<>());

        // Remove entradas fora da janela deslizante de 1 minuto
        while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > WINDOW_MS) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxAllowed) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    public long getRetryAfterSeconds() {
        return 60L;
    }
}
