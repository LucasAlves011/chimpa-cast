package com.screenshare.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class CloudflareCallsService {

    private static final Logger log = LoggerFactory.getLogger(CloudflareCallsService.class);

    @Value("${cloudflare.calls.app-id}")
    private String appId;

    @Value("${cloudflare.calls.app-secret}")
    private String appSecret;

    @Value("${cloudflare.calls.api-url:https://rtc.live.cloudflare.com/v1}")
    private String apiUrl;

    private final HttpClient httpClient;

    public CloudflareCallsService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Cria uma nova sessão WebRTC na Cloudflare Calls
     * Endpoint: POST /v1/apps/{appId}/sessions/new
     */
    public String createSession() throws IOException, InterruptedException {
        validateConfig();
        String url = String.format("%s/apps/%s/sessions/new", apiUrl, appId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appSecret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

        log.debug("Criando sessão na Cloudflare: {}", url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("Erro ao criar sessão na Cloudflare [{}]: {}", response.statusCode(), response.body());
            throw new RuntimeException("Falha ao criar sessão na Cloudflare Calls: " + response.body());
        }

        return response.body();
    }

    /**
     * Publica ou se inscreve em tracks de áudio/vídeo
     * Endpoint: POST /v1/apps/{appId}/sessions/{sessionId}/tracks/new
     */
    public String newTracks(String sessionId, String payloadJson) throws IOException, InterruptedException {
        validateConfig();
        String url = String.format("%s/apps/%s/sessions/%s/tracks/new", apiUrl, appId, sessionId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appSecret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

        log.debug("Enviando tracks para Cloudflare: {}", url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("Erro em newTracks [{}]: {}", response.statusCode(), response.body());
            throw new RuntimeException("Falha em newTracks na Cloudflare Calls: " + response.body());
        }

        return response.body();
    }

    /**
     * Renogocia a sessão WebRTC (ex: ao responder à oferta de inscrição de track)
     * Endpoint: PUT /v1/apps/{appId}/sessions/{sessionId}/renegotiate
     */
    public String renegotiate(String sessionId, String payloadJson) throws IOException, InterruptedException {
        validateConfig();
        String url = String.format("%s/apps/%s/sessions/%s/renegotiate", apiUrl, appId, sessionId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appSecret)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

        log.debug("Renegociando sessão na Cloudflare: {}", url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("Erro em renegotiate [{}]: {}", response.statusCode(), response.body());
            throw new RuntimeException("Falha em renegotiate na Cloudflare Calls: " + response.body());
        }

        return response.body();
    }

    /**
     * Fecha tracks específicos
     * Endpoint: PUT /v1/apps/{appId}/sessions/{sessionId}/tracks/close
     */
    public String closeTracks(String sessionId, String payloadJson) throws IOException, InterruptedException {
        validateConfig();
        String url = String.format("%s/apps/%s/sessions/%s/tracks/close", apiUrl, appId, sessionId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appSecret)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private void validateConfig() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("CLOUDFLARE_CALLS_APP_ID ou CLOUDFLARE_CALLS_APP_SECRET não estão configurados! Verifique o application.properties ou variáveis de ambiente.");
        }
    }
}
